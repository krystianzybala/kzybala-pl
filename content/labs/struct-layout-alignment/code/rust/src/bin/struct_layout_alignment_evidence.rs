//! Publication harness for the native-Linux evidence runner — Rust
//! counterpart of `StructLayoutLinuxEvidenceBenchmark`
//! (`--dataset mixedPrimitiveRecord|headerPlusPayload`) and
//! `CounterLayoutLinuxEvidenceBenchmark`
//! (`--dataset producerConsumerCounters`, two-thread throughput). Runs
//! the correctness oracle before any timing. Prints one JSON document;
//! non-zero exit on any correctness/placement violation.

use std::sync::atomic::Ordering;
use std::time::{Duration, Instant};
use struct_layout_alignment_lab::{header_payload, mixed_record, producer_consumer_counters};

struct Args {
    variant: String,
    dataset: String,
    cpus: Vec<usize>,
    seconds: u64,
    warmup_seconds: u64,
}

fn parse_args() -> Args {
    let mut args = Args {
        variant: "natural".into(),
        dataset: "mixedPrimitiveRecord".into(),
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

fn run_sequential(args: &Args) {
    let (checksum, ops, elapsed, n) = match args.dataset.as_str() {
        "mixedPrimitiveRecord" => {
            let source = mixed_record::generate(mixed_record::N);
            let expected = mixed_record::expected_checksum(&source);
            macro_rules! run_variant {
                ($build:expr, $sum:expr) => {{
                    let records = $build(&source);
                    assert_eq!(
                        $sum(&records),
                        expected,
                        "correctness oracle failed for {}/{}",
                        args.variant,
                        args.dataset
                    );
                    measure(args.warmup_seconds, args.seconds, || $sum(&records))
                }};
            }
            let (c, ops, e) = match args.variant.as_str() {
                "natural" => run_variant!(mixed_record::build_natural, mixed_record::sum_natural),
                "poorFieldOrder" => run_variant!(
                    mixed_record::build_poor_field_order,
                    mixed_record::sum_poor_field_order
                ),
                "optimizedFieldOrder" => {
                    run_variant!(
                        mixed_record::build_optimized_field_order,
                        mixed_record::sum_optimized_field_order
                    )
                }
                "cacheLineAligned" => run_variant!(
                    mixed_record::build_cache_line_aligned,
                    mixed_record::sum_cache_line_aligned
                ),
                "packedUnaligned" => run_variant!(
                    mixed_record::build_packed_unaligned,
                    mixed_record::sum_packed_unaligned
                ),
                other => panic!("unknown variant: {other}"),
            };
            (c, ops, e, mixed_record::N)
        }
        "headerPlusPayload" => {
            let source = header_payload::generate(header_payload::N);
            let expected = header_payload::expected_checksum(&source);
            macro_rules! run_variant {
                ($build:expr, $sum:expr) => {{
                    let records = $build(&source);
                    assert_eq!(
                        $sum(&records),
                        expected,
                        "correctness oracle failed for {}/{}",
                        args.variant,
                        args.dataset
                    );
                    measure(args.warmup_seconds, args.seconds, || $sum(&records))
                }};
            }
            let (c, ops, e) = match args.variant.as_str() {
                "natural" => {
                    run_variant!(header_payload::build_natural, header_payload::sum_natural)
                }
                "poorFieldOrder" => run_variant!(
                    header_payload::build_poor_field_order,
                    header_payload::sum_poor_field_order
                ),
                "optimizedFieldOrder" => {
                    run_variant!(
                        header_payload::build_optimized_field_order,
                        header_payload::sum_optimized_field_order
                    )
                }
                "cacheLineAligned" => {
                    run_variant!(
                        header_payload::build_cache_line_aligned,
                        header_payload::sum_cache_line_aligned
                    )
                }
                "packedUnaligned" => run_variant!(
                    header_payload::build_packed_unaligned,
                    header_payload::sum_packed_unaligned
                ),
                other => panic!("unknown variant: {other}"),
            };
            (c, ops, e, header_payload::N)
        }
        other => panic!("unknown dataset: {other}"),
    };

    let ns_per_op = elapsed.as_nanos() as f64 / ops.max(1) as f64;
    println!(
        "{{\n  \"harness\": \"struct_layout_alignment_evidence\",\n  \"variant\": \"{}\",\n  \"dataset\": \"{}\",\n  \"recordsPerOp\": {},\n  \"ops\": {},\n  \"elapsedNs\": {},\n  \"nsPerOp\": {:.1},\n  \"nsPerRecord\": {:.4},\n  \"checksum\": {}\n}}",
        args.variant, args.dataset, n, ops, elapsed.as_nanos(), ns_per_op, ns_per_op / n as f64, checksum
    );
}

fn run_pair_u64(
    warm_until: Duration,
    run_for: Duration,
    cpu_a: Option<usize>,
    cpu_b: Option<usize>,
    producer: &std::sync::atomic::AtomicU64,
    consumer: &std::sync::atomic::AtomicU64,
) -> (u64, u64) {
    std::thread::scope(|scope| {
        let start = Instant::now();
        let p = scope.spawn(move || {
            if let Some(cpu) = cpu_a {
                affinity::pin_current_thread(cpu);
            }
            while start.elapsed() < warm_until + run_for {
                producer.fetch_add(1, Ordering::Relaxed);
            }
        });
        let c = scope.spawn(move || {
            if let Some(cpu) = cpu_b {
                affinity::pin_current_thread(cpu);
            }
            while start.elapsed() < warm_until + run_for {
                consumer.fetch_add(1, Ordering::Relaxed);
            }
        });
        p.join().unwrap();
        c.join().unwrap();
    });
    (
        producer.load(Ordering::Relaxed),
        consumer.load(Ordering::Relaxed),
    )
}

fn run_pair_u32(
    warm_until: Duration,
    run_for: Duration,
    cpu_a: Option<usize>,
    cpu_b: Option<usize>,
    producer: &std::sync::atomic::AtomicU32,
    consumer: &std::sync::atomic::AtomicU32,
) -> (u32, u32) {
    std::thread::scope(|scope| {
        let start = Instant::now();
        let p = scope.spawn(move || {
            if let Some(cpu) = cpu_a {
                affinity::pin_current_thread(cpu);
            }
            while start.elapsed() < warm_until + run_for {
                producer.fetch_add(1, Ordering::Relaxed);
            }
        });
        let c = scope.spawn(move || {
            if let Some(cpu) = cpu_b {
                affinity::pin_current_thread(cpu);
            }
            while start.elapsed() < warm_until + run_for {
                consumer.fetch_add(1, Ordering::Relaxed);
            }
        });
        p.join().unwrap();
        c.join().unwrap();
    });
    (
        producer.load(Ordering::Relaxed),
        consumer.load(Ordering::Relaxed),
    )
}

fn run_counters(args: &Args) {
    if args.cpus.len() >= 2 {
        // Pinning happens on each spawned worker thread below, not here.
    }
    let warm_until = Duration::from_secs(args.warmup_seconds);
    let run_for = Duration::from_secs(args.seconds);
    let cpu_a = args.cpus.first().copied();
    let cpu_b = args.cpus.get(1).copied();

    let (producer_total, consumer_total): (u64, u64) = match args.variant.as_str() {
        "natural" => {
            let c = producer_consumer_counters::Natural::new();
            run_pair_u64(
                warm_until,
                run_for,
                cpu_a,
                cpu_b,
                &c.producer_count,
                &c.consumer_count,
            )
        }
        "poorFieldOrder" => {
            let c = producer_consumer_counters::PoorFieldOrder::new();
            run_pair_u64(
                warm_until,
                run_for,
                cpu_a,
                cpu_b,
                &c.producer_count,
                &c.consumer_count,
            )
        }
        "optimizedFieldOrder" => {
            let c = producer_consumer_counters::OptimizedFieldOrder::new();
            run_pair_u64(
                warm_until,
                run_for,
                cpu_a,
                cpu_b,
                &c.producer_count,
                &c.consumer_count,
            )
        }
        "cacheLineAligned" => {
            let c = producer_consumer_counters::CacheLineAligned::new();
            run_pair_u64(
                warm_until,
                run_for,
                cpu_a,
                cpu_b,
                &c.producer_count.0,
                &c.consumer_count.0,
            )
        }
        "packedUnaligned" => {
            let c = producer_consumer_counters::PackedUnaligned::new();
            let (p, cn) = run_pair_u32(
                warm_until,
                run_for,
                cpu_a,
                cpu_b,
                &c.producer_count,
                &c.consumer_count,
            );
            (p as u64, cn as u64)
        }
        other => panic!("unknown variant: {other}"),
    };

    if producer_total == 0 || consumer_total == 0 {
        eprintln!("a counter did not advance — run is invalid");
        std::process::exit(1);
    }

    println!(
        "{{\n  \"harness\": \"struct_layout_alignment_evidence (counters)\",\n  \"variant\": \"{}\",\n  \"dataset\": \"producerConsumerCounters\",\n  \"producerTotal\": {},\n  \"consumerTotal\": {},\n  \"seconds\": {}\n}}",
        args.variant, producer_total, consumer_total, args.seconds
    );
}

fn main() {
    let args = parse_args();
    if args.dataset == "producerConsumerCounters" {
        run_counters(&args);
    } else {
        run_sequential(&args);
    }
}
