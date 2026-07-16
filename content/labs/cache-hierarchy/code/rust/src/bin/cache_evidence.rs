//! Publication harness for the native-Linux evidence runner — the Rust
//! counterpart of `CacheHierarchyLinuxEvidenceBenchmark`, with strict
//! methodology parity: identical chase table (same xorshift64 + Sattolo,
//! fixture-checksum-verified), identical operation (1,000,000 dependent
//! loads), working sets sized against the DETECTED cache topology
//! (`/sys/devices/system/cpu/cpu0/cache`), single pinned worker, no
//! allocation in the measured interval. Prints one JSON document; non-zero
//! exit on any correctness/placement violation.

use cache_hierarchy_lab::{random_cycle, sequential_cycle};
use std::time::{Duration, Instant};

const CHASE_STEPS: usize = 1_000_000;
const RANDOM_SEED: u64 = 42;

struct Args {
    pattern: String,
    working_set: String,
    cpus: Vec<usize>,
    seconds: u64,
    warmup_seconds: u64,
}

fn parse_args() -> Args {
    let mut args = Args {
        pattern: "sequential".into(),
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
            "--pattern" => args.pattern = value,
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
/// (flagged) elsewhere — same policy and same size derivation as the Java
/// `CacheTopology`.
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
        "memory" => llc * 4,
        other => panic!("unknown working set: {other}"),
    };
    (bytes / 8) as usize
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
    let elements = working_set_elements(&args.working_set, l1, l2, llc);
    let table = match args.pattern.as_str() {
        "sequential" => sequential_cycle(elements),
        "random" => random_cycle(elements, RANDOM_SEED),
        other => panic!("unknown pattern: {other}"),
    };

    // Correctness before timing: single-cycle oracle (index sum n(n-1)/2).
    let mut probe: u64 = 0;
    let mut index_sum: u64 = 0;
    for _ in 0..elements {
        probe = table[probe as usize];
        index_sum = index_sum.wrapping_add(probe);
    }
    assert_eq!(probe, 0, "cycle must return to start");
    assert_eq!(
        index_sum,
        (elements as u64 * (elements as u64 - 1)) / 2,
        "chase table failed the single-cycle oracle"
    );

    // Warm-up, then measure whole operations (1M dependent loads each).
    let mut idx: u64 = 0;
    let warm_until = Instant::now() + Duration::from_secs(args.warmup_seconds);
    while Instant::now() < warm_until {
        for _ in 0..CHASE_STEPS {
            idx = table[idx as usize];
        }
    }
    let start = Instant::now();
    let stop_after = Duration::from_secs(args.seconds);
    let mut ops: u64 = 0;
    while start.elapsed() < stop_after {
        for _ in 0..CHASE_STEPS {
            idx = table[idx as usize];
        }
        ops += 1;
    }
    let elapsed = start.elapsed();
    let ns_per_op = elapsed.as_nanos() as f64 / ops as f64;

    println!(
        "{{\n  \"harness\": \"cache_evidence (single pinned worker)\",\n  \"pattern\": \"{}\",\n  \"workingSet\": \"{}\",\n  \"elements\": {},\n  \"workingSetBytes\": {},\n  \"topology\": {{ \"detected\": {}, \"l1dBytes\": {}, \"l2Bytes\": {}, \"llcBytes\": {} }},\n  \"chaseStepsPerOp\": {},\n  \"ops\": {},\n  \"elapsedNs\": {},\n  \"nsPerOp\": {:.1},\n  \"nsPerAccess\": {:.4},\n  \"finalIndex\": {},\n  \"pinned\": {},\n  \"observedCpuAtEnd\": {}\n}}",
        args.pattern, args.working_set, elements, elements * 8,
        detected, l1, l2, llc, CHASE_STEPS, ops, elapsed.as_nanos(),
        ns_per_op, ns_per_op / CHASE_STEPS as f64, idx, pinned,
        affinity::current_cpu()
    );

    if ops == 0 {
        eprintln!("no operations completed");
        std::process::exit(1);
    }
}
