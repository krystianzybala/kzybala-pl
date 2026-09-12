//! The real evidence binary for the Rust track. **A real language-
//! mechanism difference, not smoothed over** (this lab's "calling Rust
//! allocation-free by default" trap, benchmark.md): Rust has no
//! collector and no GC pause to report. What this binary measures
//! instead is honestly different, and labeled as such:
//!
//! - per-allocation latency (`Instant` around each `Box::new` that a
//!   variant actually performs — `lowAllocationReuse` performs zero, so
//!   its event count is zero, matching the Java track's zero GC count
//!   for the same variant), and
//! - for `growingLiveSet` only, the single synchronous cost of dropping
//!   the entire retained structure at the end of the run — Rust's
//!   closest analog to "reclaiming the live set," except it is one
//!   deterministic event timed once, never a background pause
//!   interrupting the workload.
//!
//! This is not a pause-percentile chart of the same phenomenon as the
//! Java track's JFR evidence, and no p50/p99/p999 pair here should be
//! read as a Rust GC pause distribution — there is no GC. Comparing
//! these numbers to the Java harness's `pauseNanos` as a language
//! speed contest is exactly the invalid conclusion this lab's design
//! forbids (see the "no Java-versus-Rust winner claim" rule); the
//! honest comparison is allocator-latency-tail vs GC-pause-tail as two
//! structurally different mechanisms, not two implementations of one.

use gc_tail_lab::{expected_total, extra_size_for, values_for, Node, N};
use std::time::{Duration, Instant};

struct Args {
    variant: String,
    dataset: String,
}

fn parse_args() -> Args {
    let mut args = Args {
        variant: "steadyHighAllocation".into(),
        dataset: "objectGraphChurn".into(),
    };
    let argv: Vec<String> = std::env::args().collect();
    let mut i = 1;
    while i + 1 < argv.len() {
        match argv[i].as_str() {
            "--variant" => args.variant = argv[i + 1].clone(),
            "--dataset" => args.dataset = argv[i + 1].clone(),
            other => panic!("unknown option: {other}"),
        }
        i += 2;
    }
    args
}

fn percentile(sorted: &[u64], frac: f64) -> u64 {
    if sorted.is_empty() {
        return 0;
    }
    let idx = ((sorted.len() as f64) * frac) as usize;
    sorted[idx.min(sorted.len() - 1)]
}

const BURST_QUIET_LEN: usize = 100;
const BURST_LEN: usize = 20;

fn main() {
    let args = parse_args();
    let values = values_for(&args.dataset, N);
    let extra_size = extra_size_for(&args.dataset);
    let expected = expected_total(&values);

    let mut alloc_events: Vec<Duration> = Vec::new();
    let mut reclamation_nanos: Option<u128> = None;
    let checksum: u64;

    match args.variant.as_str() {
        "lowAllocationReuse" => {
            let mut reused = Node::new(0, extra_size);
            let mut sum = 0u64;
            for &v in &values {
                reused.value = v;
                reused.extra[0] = v;
                sum = sum.wrapping_add(reused.value);
            }
            checksum = sum;
        }
        "steadyHighAllocation" | "collectorMatrix" => {
            let mut sum = 0u64;
            for &v in &values {
                let t0 = Instant::now();
                let n = Box::new(Node::new(v, extra_size));
                alloc_events.push(t0.elapsed());
                sum = sum.wrapping_add(n.value);
            }
            checksum = sum;
        }
        "burstyAllocation" => {
            let mut reused = Node::new(0, extra_size);
            let mut sum = 0u64;
            let mut i = 0usize;
            let n = values.len();
            while i < n {
                let quiet_end = (i + BURST_QUIET_LEN).min(n);
                while i < quiet_end {
                    reused.value = values[i];
                    sum = sum.wrapping_add(reused.value);
                    i += 1;
                }
                let burst_end = (i + BURST_LEN).min(n);
                while i < burst_end {
                    let t0 = Instant::now();
                    let fresh = Box::new(Node::new(values[i], extra_size * 8));
                    alloc_events.push(t0.elapsed());
                    sum = sum.wrapping_add(fresh.value);
                    i += 1;
                }
            }
            checksum = sum;
        }
        "growingLiveSet" => {
            let mut retained = Vec::with_capacity(values.len());
            let mut sum = 0u64;
            for &v in &values {
                let t0 = Instant::now();
                let n = Box::new(Node::new(v, extra_size));
                alloc_events.push(t0.elapsed());
                sum = sum.wrapping_add(n.value);
                retained.push(n);
            }
            let t0 = Instant::now();
            drop(retained);
            reclamation_nanos = Some(t0.elapsed().as_nanos());
            checksum = sum;
        }
        other => panic!("unknown variant: {other}"),
    }

    assert_eq!(
        checksum, expected,
        "correctness oracle failed for {}/{}",
        args.variant, args.dataset
    );

    let mut nanos: Vec<u64> = alloc_events.iter().map(|d| d.as_nanos() as u64).collect();
    nanos.sort_unstable();

    let reclamation_field = match reclamation_nanos {
        Some(n) => n.to_string(),
        None => "null".to_string(),
    };

    println!(
        "{{\n  \"harness\": \"gc_tail_harness\",\n  \"variant\": \"{}\",\n  \"dataset\": \"{}\",\n  \"checksum\": {},\n  \"allocEventCount\": {},\n  \"allocNanos\": {{ \"p50\": {}, \"p99\": {}, \"p999\": {}, \"max\": {} }},\n  \"reclamationNanos\": {}\n}}",
        args.variant,
        args.dataset,
        checksum,
        nanos.len(),
        percentile(&nanos, 0.50),
        percentile(&nanos, 0.99),
        percentile(&nanos, 0.999),
        nanos.last().copied().unwrap_or(0),
        reclamation_field,
    );
}
