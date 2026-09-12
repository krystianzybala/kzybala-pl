//! Publication harness for the native-Linux evidence runner — the Rust
//! counterpart of `BranchPredictionLinuxEvidenceBenchmark`, with strict
//! methodology parity: identical dataset generation, identical operation
//! (one full pass over 1,000,000 elements), single pinned worker, no
//! allocation in the measured interval. Runs the permutation-invariance
//! correctness oracle before any timing. Prints one JSON document;
//! non-zero exit on any correctness/placement violation.

use branch_prediction_lab::{
    biased_records, biased_values, filtered_sum_branchless, filtered_sum_branchy,
    filtered_sum_records_branchless, filtered_sum_records_branchy, sorted_ascending,
    sorted_by_kind, Records, BYTE_DOMAIN, BYTE_THRESHOLD, INT_DOMAIN, INT_THRESHOLD, N, SEED,
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
        variant: "random5050".into(),
        dataset: "byteFlags".into(),
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

enum Dataset {
    Values(Vec<i64>, i64),
    Records(Records),
}

fn build_dataset(variant: &str, dataset: &str) -> Dataset {
    match dataset {
        "byteFlags" | "intThresholds" => {
            let (threshold, domain) = if dataset == "byteFlags" {
                (BYTE_THRESHOLD, BYTE_DOMAIN)
            } else {
                (INT_THRESHOLD, INT_DOMAIN)
            };
            let random5050 = biased_values(50, threshold, domain, SEED, N);
            let values = match variant {
                "biased9010" => biased_values(90, threshold, domain, SEED, N),
                "random5050" | "branchless" => random5050.clone(),
                "sorted" => sorted_ascending(&random5050),
                other => panic!("unknown variant: {other}"),
            };
            if variant != "biased9010" {
                let expected = filtered_sum_branchy(&random5050, threshold);
                let actual = if variant == "branchless" {
                    filtered_sum_branchless(&values, threshold)
                } else {
                    filtered_sum_branchy(&values, threshold)
                };
                assert_eq!(
                    actual, expected,
                    "correctness oracle failed for {variant}/{dataset}"
                );
            }
            Dataset::Values(values, threshold)
        }
        "mixedHotCold" => {
            let random5050 = biased_records(50, SEED, N);
            let records = match variant {
                "biased9010" => biased_records(90, SEED, N),
                "random5050" | "branchless" => random5050.clone(),
                "sorted" => sorted_by_kind(&random5050),
                other => panic!("unknown variant: {other}"),
            };
            if variant != "biased9010" {
                let expected = filtered_sum_records_branchy(&random5050);
                let actual = if variant == "branchless" {
                    filtered_sum_records_branchless(&records)
                } else {
                    filtered_sum_records_branchy(&records)
                };
                assert_eq!(
                    actual, expected,
                    "correctness oracle failed for {variant}/{dataset}"
                );
            }
            Dataset::Records(records)
        }
        other => panic!("unknown dataset: {other}"),
    }
}

fn run_once(variant: &str, data: &Dataset) -> i64 {
    match data {
        Dataset::Values(values, threshold) => {
            if variant == "branchless" {
                filtered_sum_branchless(values, *threshold)
            } else {
                filtered_sum_branchy(values, *threshold)
            }
        }
        Dataset::Records(r) => {
            if variant == "branchless" {
                filtered_sum_records_branchless(r)
            } else {
                filtered_sum_records_branchy(r)
            }
        }
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

    let data = build_dataset(&args.variant, &args.dataset);

    let mut checksum: i64 = 0;
    let warm_until = Instant::now() + Duration::from_secs(args.warmup_seconds);
    while Instant::now() < warm_until {
        checksum = checksum.wrapping_add(run_once(&args.variant, &data));
    }
    let start = Instant::now();
    let stop_after = Duration::from_secs(args.seconds);
    let mut ops: u64 = 0;
    while start.elapsed() < stop_after {
        checksum = checksum.wrapping_add(run_once(&args.variant, &data));
        ops += 1;
    }
    let elapsed = start.elapsed();
    let ns_per_op = elapsed.as_nanos() as f64 / ops as f64;

    println!(
        "{{\n  \"harness\": \"branch_evidence (single pinned worker)\",\n  \"variant\": \"{}\",\n  \"dataset\": \"{}\",\n  \"elementsPerOp\": {},\n  \"ops\": {},\n  \"elapsedNs\": {},\n  \"nsPerOp\": {:.1},\n  \"nsPerElement\": {:.4},\n  \"checksum\": {},\n  \"pinned\": {},\n  \"observedCpuAtEnd\": {}\n}}",
        args.variant, args.dataset, N, ops, elapsed.as_nanos(),
        ns_per_op, ns_per_op / N as f64, checksum, pinned, affinity::current_cpu()
    );

    if ops == 0 {
        eprintln!("no operations completed");
        std::process::exit(1);
    }
}
