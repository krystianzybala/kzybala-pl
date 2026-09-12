//! Publication harness for the native-Linux evidence runner — the Rust
//! counterpart of `InliningLinuxEvidenceBenchmark`, with strict
//! methodology parity: identical dataset generation, identical operation
//! (one full pass over 1,000,000 inputs), single pinned worker, no
//! allocation in the measured interval. Runs the pairwise correctness
//! oracle before any timing. Prints one JSON document; non-zero exit on
//! any correctness/placement violation.

use inlining_lab::{inputs_for, run, sum_bimorphic, sum_megamorphic, sum_monomorphic, N};
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
        variant: "monomorphic".into(),
        dataset: "pricingFunctions".into(),
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

fn main() {
    let args = parse_args();
    let pinned = if let Some(cpu) = args.cpus.first().copied() {
        affinity::pin_current_thread(cpu);
        true
    } else {
        false
    };

    let inputs = inputs_for(&args.dataset, N);

    let actual = run(&args.variant, &inputs);
    let reference = match args.variant.as_str() {
        "monomorphic" | "oversizedCallee" => sum_monomorphic(&inputs),
        "megamorphic" | "switchDispatch" => sum_megamorphic(&inputs),
        "bimorphic" => sum_bimorphic(&inputs),
        other => panic!("unknown variant: {other}"),
    };
    assert_eq!(
        actual, reference,
        "correctness oracle failed for {}/{}",
        args.variant, args.dataset
    );

    let warm_until = Instant::now() + Duration::from_secs(args.warmup_seconds);
    while Instant::now() < warm_until {
        run(&args.variant, &inputs);
    }
    let start = Instant::now();
    let stop_after = Duration::from_secs(args.seconds);
    let mut ops: u64 = 0;
    let mut checksum: i64 = 0;
    while start.elapsed() < stop_after {
        checksum = checksum.wrapping_add(run(&args.variant, &inputs));
        ops += 1;
    }
    let elapsed = start.elapsed();
    let ns_per_op = elapsed.as_nanos() as f64 / ops as f64;

    println!(
        "{{\n  \"harness\": \"inlining_evidence (single pinned worker)\",\n  \"variant\": \"{}\",\n  \"dataset\": \"{}\",\n  \"elementsPerOp\": {},\n  \"ops\": {},\n  \"elapsedNs\": {},\n  \"nsPerOp\": {:.1},\n  \"nsPerElement\": {:.4},\n  \"checksum\": {},\n  \"pinned\": {},\n  \"observedCpuAtEnd\": {}\n}}",
        args.variant, args.dataset, N, ops, elapsed.as_nanos(),
        ns_per_op, ns_per_op / N as f64, checksum, pinned, affinity::current_cpu()
    );

    if ops == 0 {
        eprintln!("no operations completed");
        std::process::exit(1);
    }
}
