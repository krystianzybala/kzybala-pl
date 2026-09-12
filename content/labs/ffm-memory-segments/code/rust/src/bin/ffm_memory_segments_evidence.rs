//! Publication harness for the native-Linux evidence runner — the Rust
//! counterpart of `FfmMemorySegmentsLinuxEvidenceBenchmark`, with strict
//! methodology parity: identical dataset generation, identical
//! two-operation contract (`sequentialSum`, `randomAccess`), single
//! pinned worker. Dataset generation and the correctness oracle both run
//! once, before any timing. Prints one JSON document; non-zero exit on
//! any correctness/placement violation.

use ffm_memory_segments_lab::{binary_frames, fixed_records, large_numeric_buffers, random_indices, RecordStorage};
use std::time::{Duration, Instant};

const RANDOM_ACCESS_K: usize = 10_000;

struct Args {
    variant: String,
    dataset: String,
    op: String,
    cpus: Vec<usize>,
    seconds: u64,
    warmup_seconds: u64,
}

fn parse_args() -> Args {
    let mut args = Args {
        variant: "heapPrimitiveArray".into(),
        dataset: "fixedRecords".into(),
        op: "sequentialSum".into(),
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
            "--op" => args.op = value,
            "--cpus" => args.cpus = value.split(',').map(|c| c.parse().expect("--cpus")).collect(),
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
        assert_eq!(observed, cpu as i32, "pinned to {cpu} but running on {observed}");
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

fn measure(warmup_seconds: u64, seconds: u64, mut op: impl FnMut() -> u64) -> (u64, u64, Duration) {
    let warm_until = Instant::now() + Duration::from_secs(warmup_seconds);
    let mut checksum: u64 = 0;
    while Instant::now() < warm_until {
        checksum = checksum.wrapping_add(op());
    }
    let start = Instant::now();
    let stop_after = Duration::from_secs(seconds);
    let mut ops: u64 = 0;
    while start.elapsed() < stop_after {
        checksum = checksum.wrapping_add(op());
        ops += 1;
    }
    (checksum, ops, start.elapsed())
}

fn run_storage(storage: &dyn RecordStorage, op: &str, indices: &[usize], args: &Args) -> (u64, u64, Duration, usize) {
    match op {
        "sequentialSum" => {
            let (c, ops, e) = measure(args.warmup_seconds, args.seconds, || storage.sequential_sum());
            (c, ops, e, 0)
        }
        "randomAccess" => {
            let (c, ops, e) = measure(args.warmup_seconds, args.seconds, || storage.random_access(indices));
            (c, ops, e, indices.len())
        }
        other => panic!("unknown op: {other}"),
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

    let (checksum, ops, elapsed, unit_per_op, n_for_rate) = match args.dataset.as_str() {
        "fixedRecords" => {
            let s = fixed_records::generate(fixed_records::N);
            let expected = fixed_records::expected_checksum(&s);
            let indices = random_indices(fixed_records::N, RANDOM_ACCESS_K, 951);
            let storage: Box<dyn RecordStorage> = match args.variant.as_str() {
                "heapPrimitiveArray" => Box::new(fixed_records::heap_primitive_array(&s)),
                "confinedSegment" => Box::new(fixed_records::confined_segment(&s)),
                "sharedSegment" => Box::new(fixed_records::shared_segment(&s)),
                "slicedView" => Box::new(fixed_records::sliced_view(&s)),
                "copiedBoundaryCrossing" => Box::new(fixed_records::copied_boundary_crossing(&s)),
                other => panic!("unknown variant: {other}"),
            };
            assert_eq!(
                storage.sequential_sum(),
                expected,
                "correctness oracle failed for {}/{}",
                args.variant,
                args.dataset
            );
            let (c, ops, e, upo) = run_storage(storage.as_ref(), &args.op, &indices, &args);
            (c, ops, e, upo, fixed_records::N)
        }
        "largeNumericBuffers" => {
            let value = large_numeric_buffers::generate(large_numeric_buffers::N);
            let expected = large_numeric_buffers::expected_checksum(&value);
            let indices = random_indices(large_numeric_buffers::N, RANDOM_ACCESS_K, 951);
            let storage: Box<dyn RecordStorage> = match args.variant.as_str() {
                "heapPrimitiveArray" => Box::new(large_numeric_buffers::heap_primitive_array(&value)),
                "confinedSegment" => Box::new(large_numeric_buffers::confined_segment(&value)),
                "sharedSegment" => Box::new(large_numeric_buffers::shared_segment(&value)),
                "slicedView" => Box::new(large_numeric_buffers::sliced_view(&value)),
                "copiedBoundaryCrossing" => Box::new(large_numeric_buffers::copied_boundary_crossing(&value)),
                other => panic!("unknown variant: {other}"),
            };
            assert_eq!(
                storage.sequential_sum(),
                expected,
                "correctness oracle failed for {}/{}",
                args.variant,
                args.dataset
            );
            let (c, ops, e, upo) = run_storage(storage.as_ref(), &args.op, &indices, &args);
            (c, ops, e, upo, large_numeric_buffers::N)
        }
        "binaryFrames" => {
            let s = binary_frames::generate(binary_frames::N);
            let expected = binary_frames::expected_checksum(&s);
            let indices = random_indices(binary_frames::N, RANDOM_ACCESS_K, 951);
            let storage: Box<dyn RecordStorage> = match args.variant.as_str() {
                "heapPrimitiveArray" => Box::new(binary_frames::heap_primitive_array(&s)),
                "confinedSegment" => Box::new(binary_frames::confined_segment(&s)),
                "sharedSegment" => Box::new(binary_frames::shared_segment(&s)),
                "slicedView" => Box::new(binary_frames::sliced_view(&s)),
                "copiedBoundaryCrossing" => Box::new(binary_frames::copied_boundary_crossing(&s)),
                other => panic!("unknown variant: {other}"),
            };
            assert_eq!(
                storage.sequential_sum(),
                expected,
                "correctness oracle failed for {}/{}",
                args.variant,
                args.dataset
            );
            let (c, ops, e, upo) = run_storage(storage.as_ref(), &args.op, &indices, &args);
            (c, ops, e, upo, binary_frames::N)
        }
        other => panic!("unknown dataset: {other}"),
    };

    let ns_per_op = elapsed.as_nanos() as f64 / ops.max(1) as f64;
    let unit_count = if args.op == "randomAccess" { unit_per_op } else { n_for_rate };
    let ns_per_unit = ns_per_op / unit_count as f64;

    println!(
        "{{\n  \"harness\": \"ffm_memory_segments_evidence (single pinned worker)\",\n  \"variant\": \"{}\",\n  \"dataset\": \"{}\",\n  \"op\": \"{}\",\n  \"unitsPerOp\": {},\n  \"ops\": {},\n  \"elapsedNs\": {},\n  \"nsPerOp\": {:.1},\n  \"nsPerUnit\": {:.4},\n  \"checksum\": {},\n  \"pinned\": {},\n  \"observedCpuAtEnd\": {}\n}}",
        args.variant, args.dataset, args.op, unit_count, ops, elapsed.as_nanos(),
        ns_per_op, ns_per_unit, checksum, pinned, affinity::current_cpu()
    );

    if ops == 0 {
        eprintln!("no operations completed");
        std::process::exit(1);
    }
}
