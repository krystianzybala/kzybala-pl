//! Publication harness for the native-Linux evidence runner — the Rust
//! counterpart of `CacheLocalityLinuxEvidenceBenchmark`, with strict
//! methodology parity: identical traversal-order construction, identical
//! operation (one full pass over the resolved working set), working sets
//! sized against the DETECTED cache topology
//! (`/sys/devices/system/cpu/cpu0/cache`), single pinned worker, no
//! allocation in the measured interval. Runs the `n*(n-1)/2` correctness
//! oracle before any timing. Prints one JSON document; non-zero exit on
//! any correctness/placement violation.

use cache_locality_lab::{
    expected_total, random_permutation, sattolo_next, sum_blocked, sum_permuted, sum_pointer_chase,
    sum_sequential,
};
use std::time::{Duration, Instant};

const SEED: u64 = 42;

struct Args {
    variant: String,
    working_set: String,
    cpus: Vec<usize>,
    seconds: u64,
    warmup_seconds: u64,
}

fn parse_args() -> Args {
    let mut args = Args {
        variant: "sequential".into(),
        working_set: "l1".into(),
        cpus: vec![],
        seconds: 5,
        warmup_seconds: 2,
    };
    let argv: Vec<String> = std::env::args().collect();
    let mut i = 1;
    while i < argv.len() {
        let value = argv
            .get(i + 1)
            .unwrap_or_else(|| panic!("missing value for {}", argv[i]))
            .clone();
        match argv[i].as_str() {
            "--variant" => args.variant = value,
            "--working-set" => args.working_set = value,
            "--cpus" => {
                args.cpus = value
                    .split(',')
                    .map(|c| c.parse().expect("--cpus"))
                    .collect()
            }
            "--seconds" => args.seconds = value.parse().expect("--seconds"),
            "--warmup-seconds" => args.warmup_seconds = value.parse().expect("--warmup-seconds"),
            other => panic!("unknown option: {other}"),
        }
        i += 2;
    }
    args
}

#[cfg(target_os = "linux")]
mod affinity {
    pub fn pin_current_thread(cpu: usize) {
        unsafe {
            let mut set: libc::cpu_set_t = std::mem::zeroed();
            libc::CPU_SET(cpu, &mut set);
            let rc = libc::sched_setaffinity(0, std::mem::size_of::<libc::cpu_set_t>(), &set);
            assert_eq!(rc, 0, "sched_setaffinity(cpu={cpu}) failed");
        }
        std::thread::yield_now();
        let observed = current_cpu();
        assert_eq!(
            observed, cpu as i32,
            "pinned to {cpu} but running on {observed}"
        );
    }
    pub fn current_cpu() -> i32 {
        unsafe { libc::sched_getcpu() }
    }
}

#[cfg(not(target_os = "linux"))]
mod affinity {
    pub fn pin_current_thread(_cpu: usize) {
        panic!("worker pinning requires Linux — publication evidence comes only from the dedicated native-Linux host");
    }
    pub fn current_cpu() -> i32 {
        -1
    }
}

/// Detected cache topology from sysfs; documented development fallback
/// (flagged) elsewhere — same policy and derivation as the Java
/// `CacheTopology` (and content/labs/cache-hierarchy's Rust counterpart).
fn detect_topology() -> (u64, u64, u64, bool) {
    let base = "/sys/devices/system/cpu/cpu0/cache";
    let mut l1: i64 = -1;
    let mut l2: i64 = -1;
    let mut llc: i64 = -1;
    if std::path::Path::new(base).is_dir() {
        for index in 0..8 {
            let dir = format!("{base}/index{index}");
            let read = |f: &str| std::fs::read_to_string(format!("{dir}/{f}")).ok();
            let (Some(t), Some(level), Some(size)) = (read("type"), read("level"), read("size"))
            else {
                continue;
            };
            let level: i32 = level.trim().parse().unwrap_or(0);
            let size = parse_size(size.trim());
            let t = t.trim();
            if level == 1 && (t == "Data" || t == "Unified") {
                l1 = size;
            }
            if level == 2 && t != "Instruction" {
                l2 = size;
            }
            if level >= 3 && t != "Instruction" {
                llc = llc.max(size);
            }
        }
    }
    if l1 > 0 && l2 > 0 {
        let llc = if llc > 0 { llc } else { l2 };
        (l1 as u64, l2 as u64, llc as u64, true)
    } else {
        (32 * 1024, 1024 * 1024, 32 * 1024 * 1024, false)
    }
}

fn parse_size(text: &str) -> i64 {
    let upper = text.to_uppercase();
    if let Some(v) = upper.strip_suffix('K') {
        v.parse::<i64>().map(|n| n * 1024).unwrap_or(-1)
    } else if let Some(v) = upper.strip_suffix('M') {
        v.parse::<i64>().map(|n| n * 1024 * 1024).unwrap_or(-1)
    } else {
        upper.parse().unwrap_or(-1)
    }
}

fn working_set_elements(name: &str, l1: u64, l2: u64, llc: u64) -> usize {
    let bytes = match name {
        "l1" => l1 / 2,
        "l2" => (l2 / 2).max(l1 * 2),
        "llc" => (llc / 2).max(l2 * 2),
        "2xllc" => llc * 2,
        "large" => llc * 8,
        other => panic!("unknown working set: {other}"),
    };
    (bytes / 8) as usize
}

enum Order {
    Sequential(usize),
    Permuted(Vec<i64>),
    PointerChase(Vec<i64>),
    Blocked(usize, usize),
}

fn run_once(order: &Order) -> i64 {
    match order {
        Order::Sequential(n) => sum_sequential(*n),
        Order::Permuted(perm) => sum_permuted(perm),
        Order::PointerChase(next) => sum_pointer_chase(next),
        Order::Blocked(side, block) => sum_blocked(*side, *block),
    }
}

fn main() {
    let args = parse_args();
    let pinned = if let Some(cpu) = args.cpus.first().copied() {
        affinity::pin_current_thread(cpu);
        true
    } else {
        false
    };

    let (l1, l2, llc, detected) = detect_topology();
    let mut elements = working_set_elements(&args.working_set, l1, l2, llc);

    let order = match args.variant.as_str() {
        "sequential" => Order::Sequential(elements),
        "random" => Order::Permuted(random_permutation(SEED, elements)),
        "pointerChase" => Order::PointerChase(sattolo_next(SEED, elements)),
        "blocked" => {
            let side = (elements as f64).sqrt() as usize;
            elements = side * side;
            Order::Blocked(side, 32)
        }
        other => panic!("unknown variant: {other}"),
    };

    let expected = expected_total(elements);
    let actual = run_once(&order);
    assert_eq!(
        actual, expected,
        "correctness oracle failed for {}/{}",
        args.variant, args.working_set
    );

    let warm_until = Instant::now() + Duration::from_secs(args.warmup_seconds);
    while Instant::now() < warm_until {
        run_once(&order);
    }
    let start = Instant::now();
    let stop_after = Duration::from_secs(args.seconds);
    let mut ops: u64 = 0;
    let mut checksum: i64 = 0;
    while start.elapsed() < stop_after {
        checksum = checksum.wrapping_add(run_once(&order));
        ops += 1;
    }
    let elapsed = start.elapsed();
    let ns_per_op = elapsed.as_nanos() as f64 / ops as f64;

    println!(
        "{{\n  \"harness\": \"cache_locality_evidence (single pinned worker)\",\n  \"variant\": \"{}\",\n  \"workingSet\": \"{}\",\n  \"elements\": {},\n  \"workingSetBytes\": {},\n  \"topology\": {{ \"detected\": {}, \"l1dBytes\": {}, \"l2Bytes\": {}, \"llcBytes\": {} }},\n  \"ops\": {},\n  \"elapsedNs\": {},\n  \"nsPerOp\": {:.1},\n  \"nsPerElement\": {:.4},\n  \"checksum\": {},\n  \"pinned\": {},\n  \"observedCpuAtEnd\": {}\n}}",
        args.variant, args.working_set, elements, elements * 8,
        detected, l1, l2, llc, ops, elapsed.as_nanos(),
        ns_per_op, ns_per_op / elements as f64, checksum, pinned, affinity::current_cpu()
    );

    if ops == 0 {
        eprintln!("no operations completed");
        std::process::exit(1);
    }
}
