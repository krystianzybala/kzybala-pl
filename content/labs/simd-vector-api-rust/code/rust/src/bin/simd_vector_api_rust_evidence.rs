//! Publication harness for the native-Linux evidence runner — Rust
//! counterpart of `SimdLinuxEvidenceBenchmark`. Runs the correctness
//! oracle before any timing. Prints one JSON document; non-zero exit on
//! any correctness/placement violation.

use simd_vector_api_rust_lab::{
    byte_classification, dot_product, generate_byte_classification, generate_dot_product,
    generate_sum_min_max, generate_threshold, sum_min_max, threshold_filter, BYTE_CLASSIFICATION_N,
    DOT_PRODUCT_N, SMALL_TAIL_N, SUM_MIN_MAX_N, THRESHOLD, THRESHOLD_N,
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
        variant: "explicitSimd".into(),
        dataset: "sumMinMax".into(),
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
    }
}

#[cfg(not(target_os = "linux"))]
mod affinity {
    pub fn pin_current_thread(_cpu: usize) {
        panic!("worker pinning requires Linux — publication evidence comes only from the dedicated native-Linux host");
    }
}

fn slice_for<'a, T>(full: &'a [T], variant: &str) -> &'a [T] {
    match variant {
        "misalignedInput" => &full[1..],
        "smallTailHeavyInput" => &full[..SMALL_TAIL_N],
        _ => full,
    }
}

fn measure_f64(
    warmup_seconds: u64,
    seconds: u64,
    mut op: impl FnMut() -> f64,
) -> (f64, u64, Duration) {
    let warm_until = Instant::now() + Duration::from_secs(warmup_seconds);
    let mut acc = 0.0;
    while Instant::now() < warm_until {
        acc += op();
    }
    let start = Instant::now();
    let stop_after = Duration::from_secs(seconds);
    let mut ops: u64 = 0;
    while start.elapsed() < stop_after {
        acc += op();
        ops += 1;
    }
    (acc, ops, start.elapsed())
}

fn measure_u64(
    warmup_seconds: u64,
    seconds: u64,
    mut op: impl FnMut() -> u64,
) -> (u64, u64, Duration) {
    let warm_until = Instant::now() + Duration::from_secs(warmup_seconds);
    let mut acc = 0u64;
    while Instant::now() < warm_until {
        acc = acc.wrapping_add(op());
    }
    let start = Instant::now();
    let stop_after = Duration::from_secs(seconds);
    let mut ops: u64 = 0;
    while start.elapsed() < stop_after {
        acc = acc.wrapping_add(op());
        ops += 1;
    }
    (acc, ops, start.elapsed())
}

fn main() {
    let args = parse_args();
    if let Some(cpu) = args.cpus.first().copied() {
        affinity::pin_current_thread(cpu);
    }

    let (ops, elapsed, n, acc_str) = match args.dataset.as_str() {
        "sumMinMax" => {
            let full = generate_sum_min_max(SUM_MIN_MAX_N);
            let slice = slice_for(&full, &args.variant);
            let expected = sum_min_max::scalar_baseline(slice);
            let run = || match args.variant.as_str() {
                "scalarBaseline" => sum_min_max::scalar_baseline(slice),
                "autoVectorizedCandidate" => sum_min_max::auto_vectorized_candidate(slice),
                _ => sum_min_max::explicit_simd(slice),
            };
            assert_eq!(
                run(),
                expected,
                "correctness oracle failed for {}/{}",
                args.variant,
                args.dataset
            );
            let (acc, ops, e) = measure_u64(args.warmup_seconds, args.seconds, || run().sum as u64);
            (ops, e, slice.len(), acc.to_string())
        }
        "thresholdFilter" => {
            let full = generate_threshold(THRESHOLD_N);
            let slice = slice_for(&full, &args.variant);
            let expected = threshold_filter::scalar_baseline(slice, THRESHOLD);
            let run = || match args.variant.as_str() {
                "scalarBaseline" => threshold_filter::scalar_baseline(slice, THRESHOLD),
                "autoVectorizedCandidate" => {
                    threshold_filter::auto_vectorized_candidate(slice, THRESHOLD)
                }
                _ => threshold_filter::explicit_simd(slice, THRESHOLD),
            };
            assert_eq!(
                run(),
                expected,
                "correctness oracle failed for {}/{}",
                args.variant,
                args.dataset
            );
            let (acc, ops, e) = measure_u64(args.warmup_seconds, args.seconds, run);
            (ops, e, slice.len(), acc.to_string())
        }
        "dotProduct" => {
            let full = generate_dot_product(DOT_PRODUCT_N);
            let a = slice_for(&full.a, &args.variant);
            let b = slice_for(&full.b, &args.variant);
            let expected = dot_product::scalar_baseline(a, b);
            let run = || match args.variant.as_str() {
                "scalarBaseline" => dot_product::scalar_baseline(a, b),
                "autoVectorizedCandidate" => dot_product::auto_vectorized_candidate(a, b),
                _ => dot_product::explicit_simd(a, b),
            };
            assert_eq!(
                run(),
                expected,
                "correctness oracle failed for {}/{}",
                args.variant,
                args.dataset
            );
            let (acc, ops, e) = measure_f64(args.warmup_seconds, args.seconds, run);
            (ops, e, a.len(), acc.to_string())
        }
        "byteClassification" => {
            let full = generate_byte_classification(BYTE_CLASSIFICATION_N);
            let slice = slice_for(&full, &args.variant);
            let expected = byte_classification::scalar_baseline(slice);
            let run = || match args.variant.as_str() {
                "scalarBaseline" => byte_classification::scalar_baseline(slice),
                "autoVectorizedCandidate" => byte_classification::auto_vectorized_candidate(slice),
                _ => byte_classification::explicit_simd(slice),
            };
            assert_eq!(
                run(),
                expected,
                "correctness oracle failed for {}/{}",
                args.variant,
                args.dataset
            );
            let (acc, ops, e) = measure_u64(args.warmup_seconds, args.seconds, run);
            (ops, e, slice.len(), acc.to_string())
        }
        other => panic!("unknown dataset: {other}"),
    };

    let ns_per_op = elapsed.as_nanos() as f64 / ops.max(1) as f64;
    println!(
        "{{\n  \"harness\": \"simd_vector_api_rust_evidence\",\n  \"variant\": \"{}\",\n  \"dataset\": \"{}\",\n  \"elementsPerOp\": {},\n  \"ops\": {},\n  \"elapsedNs\": {},\n  \"nsPerOp\": {:.1},\n  \"nsPerElement\": {:.4},\n  \"accumulator\": \"{}\"\n}}",
        args.variant, args.dataset, n, ops, elapsed.as_nanos(), ns_per_op, ns_per_op / n as f64, acc_str
    );
}
