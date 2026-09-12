//! Publication harness for the native-Linux evidence runner — the Rust
//! counterpart of `BoundsChecksLinuxEvidenceBenchmark`, with strict
//! methodology parity: identical backing/slice/permutation construction,
//! identical operation (one full pass over the declared dataset), single
//! pinned worker, no allocation in the measured interval. Runs the
//! closed-form correctness oracle before any timing. Prints one JSON
//! document; non-zero exit on any correctness/placement violation.

use bounds_checks_lab::{
    build_backing, expected_total, primitive_canonical, primitive_irregular_index,
    primitive_opaque_limit, primitive_safe_iterator, random_permutation, slice_canonical,
    slice_irregular_index, slice_opaque_limit, slice_safe_iterator, strided_canonical,
    strided_irregular_index, strided_opaque_limit, strided_safe_iterator, unchecked, N, SEED,
    SLICE_START, STRIDE,
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
        variant: "canonical".into(),
        dataset: "primitiveArrays".into(),
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

fn run_once(variant: &str, dataset: &str, backing: &[i32], perm: &[usize]) -> i64 {
    match dataset {
        "primitiveArrays" => match variant {
            "canonical" => primitive_canonical(backing),
            "opaqueLimit" => primitive_opaque_limit(backing),
            "irregularIndex" => primitive_irregular_index(backing, perm),
            "safeIterator" => primitive_safe_iterator(backing),
            "unchecked" => unsafe { unchecked::sum_unchecked(backing, 0..backing.len()) },
            other => panic!("unknown variant: {other}"),
        },
        "slicesSubranges" => {
            let end = SLICE_START + N;
            match variant {
                "canonical" => slice_canonical(backing, SLICE_START, end),
                "opaqueLimit" => slice_opaque_limit(backing, SLICE_START, end),
                "irregularIndex" => slice_irregular_index(backing, perm, SLICE_START),
                "safeIterator" => slice_safe_iterator(backing, SLICE_START, end),
                "unchecked" => unsafe { unchecked::sum_unchecked(backing, SLICE_START..end) },
                other => panic!("unknown variant: {other}"),
            }
        }
        "stridedAccess" => match variant {
            "canonical" => strided_canonical(backing, N, STRIDE),
            "opaqueLimit" => strided_opaque_limit(backing, N, STRIDE),
            "irregularIndex" => strided_irregular_index(backing, perm, STRIDE),
            "safeIterator" => strided_safe_iterator(backing, N, STRIDE),
            "unchecked" => unsafe { unchecked::sum_unchecked(backing, (0..N).map(|i| i * STRIDE)) },
            other => panic!("unknown variant: {other}"),
        },
        other => panic!("unknown dataset: {other}"),
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

    let backing = match args.dataset.as_str() {
        "primitiveArrays" => build_backing(N),
        "slicesSubranges" => build_backing(2 * N),
        "stridedAccess" => build_backing(N * STRIDE),
        other => panic!("unknown dataset: {other}"),
    };
    let perm = if args.variant == "irregularIndex" {
        random_permutation(SEED, N)
    } else {
        Vec::new()
    };

    let expected = expected_total(&args.dataset);
    let actual = run_once(&args.variant, &args.dataset, &backing, &perm);
    assert_eq!(
        actual, expected,
        "correctness oracle failed for {}/{}",
        args.variant, args.dataset
    );

    let warm_until = Instant::now() + Duration::from_secs(args.warmup_seconds);
    while Instant::now() < warm_until {
        run_once(&args.variant, &args.dataset, &backing, &perm);
    }
    let start = Instant::now();
    let stop_after = Duration::from_secs(args.seconds);
    let mut ops: u64 = 0;
    let mut checksum: i64 = 0;
    while start.elapsed() < stop_after {
        checksum = checksum.wrapping_add(run_once(&args.variant, &args.dataset, &backing, &perm));
        ops += 1;
    }
    let elapsed = start.elapsed();
    let ns_per_op = elapsed.as_nanos() as f64 / ops as f64;

    println!(
        "{{\n  \"harness\": \"bounds_checks_evidence (single pinned worker)\",\n  \"variant\": \"{}\",\n  \"dataset\": \"{}\",\n  \"elementsPerOp\": {},\n  \"ops\": {},\n  \"elapsedNs\": {},\n  \"nsPerOp\": {:.1},\n  \"nsPerElement\": {:.4},\n  \"checksum\": {},\n  \"pinned\": {},\n  \"observedCpuAtEnd\": {}\n}}",
        args.variant, args.dataset, N, ops, elapsed.as_nanos(),
        ns_per_op, ns_per_op / N as f64, checksum, pinned, affinity::current_cpu()
    );

    if ops == 0 {
        eprintln!("no operations completed");
        std::process::exit(1);
    }
}
