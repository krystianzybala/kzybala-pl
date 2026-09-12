//! Publication harness for the native-Linux evidence runner — the Rust
//! counterpart of `AosVsSoaLinuxEvidenceBenchmark`, with strict
//! methodology parity: identical dataset generation, identical operation
//! (one full pass over 1,000,000 records touching only the hot fields),
//! single pinned worker, no allocation in the measured interval. Runs the
//! layout-invariance correctness oracle before any timing. Prints one
//! JSON document; non-zero exit on any correctness/placement violation.
//!
//! `AosPackedLayout<const COLD: usize>` needs `COLD` at compile time, so
//! the runtime `--dataset` string is matched ONCE to select the
//! monomorphized `run::<COLD>` instantiation — the same three cold-word
//! counts (2, 4, 8) as the Java side's dataset parameter.

use aos_vs_soa_lab::{
    expected_hot_sum, generate, AosHeapLayout, AosPackedLayout, Generated, HybridLayout, SoaLayout,
    N, SEED,
};
use std::time::{Duration, Instant};

struct Args {
    variant: String,
    dataset: String,
    cpus: Vec<usize>,
    seconds: u64,
    warmup_seconds: u64,
}

fn parse_args() -> Args {
    let mut args = Args {
        variant: "soa".into(),
        dataset: "marketQuotes".into(),
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
            "--dataset" => args.dataset = value,
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

enum Layout<const COLD: usize> {
    Heap(AosHeapLayout),
    Packed(AosPackedLayout<COLD>),
    Soa(SoaLayout),
    Hybrid(HybridLayout),
}

impl<const COLD: usize> Layout<COLD> {
    fn sum_hot(&self) -> i64 {
        match self {
            Layout::Heap(l) => l.sum_hot(),
            Layout::Packed(l) => l.sum_hot(),
            Layout::Soa(l) => l.sum_hot(),
            Layout::Hybrid(l) => l.sum_hot(),
        }
    }
}

fn build_layout<const COLD: usize>(variant: &str, data: &Generated) -> Layout<COLD> {
    match variant {
        "aosHeap" => Layout::Heap(AosHeapLayout::of(data)),
        "aosPacked" => Layout::Packed(AosPackedLayout::<COLD>::of(data)),
        "soa" => Layout::Soa(SoaLayout::of(data, COLD)),
        "hybrid" => Layout::Hybrid(HybridLayout::of(data, COLD)),
        other => panic!("unknown variant: {other}"),
    }
}

fn run<const COLD: usize>(args: &Args) {
    let pinned = if let Some(cpu) = args.cpus.first().copied() {
        affinity::pin_current_thread(cpu);
        true
    } else {
        false
    };

    let data = generate(COLD, SEED, N);
    let expected = expected_hot_sum(&data.hot_a, &data.hot_b);
    let layout = build_layout::<COLD>(&args.variant, &data);
    let actual = layout.sum_hot();
    assert_eq!(
        actual, expected,
        "layout-invariance oracle failed for {}/{}",
        args.variant, args.dataset
    );

    let warm_until = Instant::now() + Duration::from_secs(args.warmup_seconds);
    let mut checksum: i64 = 0;
    while Instant::now() < warm_until {
        checksum = checksum.wrapping_add(layout.sum_hot());
    }
    let start = Instant::now();
    let stop_after = Duration::from_secs(args.seconds);
    let mut ops: u64 = 0;
    while start.elapsed() < stop_after {
        checksum = checksum.wrapping_add(layout.sum_hot());
        ops += 1;
    }
    let elapsed = start.elapsed();
    let ns_per_op = elapsed.as_nanos() as f64 / ops as f64;

    println!(
        "{{\n  \"harness\": \"aos_soa_evidence (single pinned worker)\",\n  \"variant\": \"{}\",\n  \"dataset\": \"{}\",\n  \"coldWords\": {},\n  \"recordsPerOp\": {},\n  \"ops\": {},\n  \"elapsedNs\": {},\n  \"nsPerOp\": {:.1},\n  \"nsPerRecord\": {:.4},\n  \"checksum\": {},\n  \"pinned\": {},\n  \"observedCpuAtEnd\": {}\n}}",
        args.variant, args.dataset, COLD, N, ops, elapsed.as_nanos(),
        ns_per_op, ns_per_op / N as f64, checksum, pinned, affinity::current_cpu()
    );

    if ops == 0 {
        eprintln!("no operations completed");
        std::process::exit(1);
    }
}

fn main() {
    let args = parse_args();
    match args.dataset.as_str() {
        "marketQuotes" => run::<4>(&args),
        "positions" => run::<8>(&args),
        "spatialPoints" => run::<2>(&args),
        other => panic!("unknown dataset: {other}"),
    }
}
