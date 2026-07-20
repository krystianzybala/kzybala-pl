//! Rust counterpart of `CalibrationHarness`: cost, granularity and
//! monotonicity of `std::time::Instant` on THIS host. Prints one JSON
//! document; exits non-zero on a monotonicity violation (Instant is
//! specified monotonic — a violation is an infrastructure red flag).

use std::time::Instant;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let mut samples: u64 = 1_000_000;
    let mut i = 1;
    while i + 1 < args.len() {
        match args[i].as_str() {
            "--samples" => samples = args[i + 1].parse().expect("--samples"),
            other => panic!("unknown option {other}"),
        }
        i += 2;
    }

    // warm-up
    let mut sink: u128 = 0;
    let origin = Instant::now();
    for _ in 0..200_000 {
        sink = sink.wrapping_add(origin.elapsed().as_nanos());
    }

    // per-call cost
    let t0 = Instant::now();
    for _ in 0..samples {
        sink = sink.wrapping_add(origin.elapsed().as_nanos());
    }
    let elapsed = t0.elapsed().as_nanos();
    let ns_per_call = elapsed as f64 / samples as f64;

    // granularity + monotonicity
    let n = 100_000usize;
    let mut deltas = Vec::with_capacity(n);
    let mut violations = 0u64;
    let mut prev = origin.elapsed().as_nanos();
    for _ in 0..n {
        let now = origin.elapsed().as_nanos();
        if now < prev {
            violations += 1;
        }
        deltas.push(now.saturating_sub(prev) as u64);
        prev = now;
    }
    let zeros = deltas.iter().filter(|d| **d == 0).count();
    let min_non_zero = deltas
        .iter()
        .filter(|d| **d > 0)
        .min()
        .copied()
        .unwrap_or(0);
    let mut sorted = deltas.clone();
    sorted.sort_unstable();
    let median = sorted[sorted.len() / 2];

    println!("{{");
    println!(
        "  \"harness\": \"calibration (std::time::Instant cost/granularity/monotonicity, Rust)\","
    );
    println!("  \"samples\": {samples},");
    println!("  \"nsPerCall\": {ns_per_call:.3},");
    println!("  \"minNonZeroDeltaNs\": {min_non_zero},");
    println!("  \"medianDeltaNs\": {median},");
    println!("  \"zeroDeltas\": {zeros},");
    println!("  \"monotonicityViolations\": {violations},");
    println!("  \"sinkChecksum\": {},", sink as u64 as i64);
    println!("  \"note\": \"instrument calibration — Instant::now cost bounds what per-op timestamping can honestly resolve\"");
    println!("}}");
    if violations > 0 {
        std::process::exit(1);
    }
}
