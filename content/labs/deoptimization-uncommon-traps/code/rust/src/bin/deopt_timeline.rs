//! The Rust counterpart of `DeoptTimelineHarness` (Java): the same
//! batched per-call latency timeline (never per-call — a single
//! arithmetic op is far smaller than `Instant::now()`'s own cost),
//! split into pre-shift / shift-window / post-shift windows. Where
//! Java's timeline shows a real spike at `SHIFT_POINT` for
//! `profileShiftAfterWarmup` and `lateSubtypeLoading`, this binary is
//! expected to show a FLAT timeline throughout — see this crate's
//! `lib.rs` doc comment for why, and rust.md for what was actually
//! measured during development.

use deopt_lab::{
    inputs_for, run, Strategy, TypeA, TypeB, TypeC, FALLBACK_EXCEPTION, FALLBACK_NULL, N,
    NULL_STRIDE, RARE_EXCEPTION_INDEX, SHIFT_POINT,
};
use std::time::Instant;

const BATCH_SIZE: usize = 100;
const WARMUP_EXCLUDE_CALLS: usize = 50_000;
const SHIFT_WINDOW_CALLS: usize = 10_000;

struct Percentiles {
    p50: u64,
    p99: u64,
    p999: u64,
    max: u64,
    count: usize,
}

impl Percentiles {
    fn of(sorted_batch_nanos: &[u64]) -> Percentiles {
        let n = sorted_batch_nanos.len();
        if n == 0 {
            return Percentiles {
                p50: 0,
                p99: 0,
                p999: 0,
                max: 0,
                count: 0,
            };
        }
        let idx = |frac: f64| -> usize { ((n as f64 * frac) as usize).min(n - 1) };
        Percentiles {
            p50: sorted_batch_nanos[idx(0.50)],
            p99: sorted_batch_nanos[idx(0.99)],
            p999: sorted_batch_nanos[idx(0.999)],
            max: sorted_batch_nanos[n - 1],
            count: n,
        }
    }
}

fn timed_run(variant: &str, inputs: &[i64], batch_nanos: &mut [u64]) -> i64 {
    let a = TypeA;
    let b = TypeB;
    let mut sum: i64 = 0;
    let batches = inputs.len() / BATCH_SIZE;
    let mut idx = 0usize;
    for batch in batch_nanos.iter_mut().take(batches) {
        let start = Instant::now();
        for _ in 0..BATCH_SIZE {
            let v = inputs[idx];
            let contribution: i64 = match variant {
                "stableTypeProfile" => a.apply(v),
                "profileShiftAfterWarmup" => {
                    if idx < SHIFT_POINT || idx % 2 == 0 {
                        a.apply(v)
                    } else {
                        b.apply(v)
                    }
                }
                "rareExceptionPath" => {
                    if idx == RARE_EXCEPTION_INDEX {
                        FALLBACK_EXCEPTION
                    } else {
                        a.apply(v)
                    }
                }
                "lateSubtypeLoading" => {
                    if idx < SHIFT_POINT {
                        a.apply(v)
                    } else {
                        TypeC.apply(v)
                    }
                }
                "nullabilityShift" => {
                    if idx >= SHIFT_POINT && (idx - SHIFT_POINT) % NULL_STRIDE == 0 {
                        FALLBACK_NULL
                    } else {
                        a.apply(v)
                    }
                }
                other => panic!("unknown variant: {other}"),
            };
            sum = sum.wrapping_add(contribution);
            idx += 1;
        }
        *batch = start.elapsed().as_nanos() as u64;
    }
    sum
}

fn main() {
    let mut variant = "stableTypeProfile".to_string();
    let mut dataset = "strategyDispatch".to_string();
    let argv: Vec<String> = std::env::args().collect();
    let mut i = 1;
    while i + 1 < argv.len() {
        match argv[i].as_str() {
            "--variant" => variant = argv[i + 1].clone(),
            "--dataset" => dataset = argv[i + 1].clone(),
            other => panic!("unknown option: {other}"),
        }
        i += 2;
    }

    let inputs = inputs_for(&dataset, N);
    let batches = inputs.len() / BATCH_SIZE;
    let mut batch_nanos = vec![0u64; batches];

    let actual = timed_run(&variant, &inputs, &mut batch_nanos);
    let expected = run(&variant, &inputs);
    assert_eq!(
        actual, expected,
        "correctness oracle failed for {variant}/{dataset}"
    );

    let warmup_batches = WARMUP_EXCLUDE_CALLS / BATCH_SIZE;
    let shift_batch_start = SHIFT_POINT / BATCH_SIZE;
    let shift_window_batches = SHIFT_WINDOW_CALLS / BATCH_SIZE;

    let pre_shift = &batch_nanos[warmup_batches..shift_batch_start];
    let shift_end = (shift_batch_start + shift_window_batches).min(batches);
    let shift_window = &batch_nanos[shift_batch_start..shift_end];
    let post_shift = &batch_nanos[shift_end..batches];

    let mut pre_sorted = pre_shift.to_vec();
    let mut post_sorted = post_shift.to_vec();
    pre_sorted.sort_unstable();
    post_sorted.sort_unstable();
    let pre = Percentiles::of(&pre_sorted);
    let post = Percentiles::of(&post_sorted);
    let spike_max = shift_window.iter().copied().max().unwrap_or(0);

    println!(
        "{{\n  \"harness\": \"deopt_timeline\",\n  \"variant\": \"{}\",\n  \"dataset\": \"{}\",\n  \"batchSize\": {},\n  \"totalBatches\": {},\n  \"checksum\": {},\n  \"preShift\": {{ \"batches\": {}, \"p50Ns\": {}, \"p99Ns\": {}, \"p999Ns\": {}, \"maxNs\": {} }},\n  \"shiftWindow\": {{ \"batches\": {}, \"maxNs\": {} }},\n  \"postShift\": {{ \"batches\": {}, \"p50Ns\": {}, \"p99Ns\": {}, \"p999Ns\": {}, \"maxNs\": {} }}\n}}",
        variant, dataset, BATCH_SIZE, batches, actual,
        pre.count, pre.p50, pre.p99, pre.p999, pre.max,
        shift_window.len(), spike_max,
        post.count, post.p50, post.p99, post.p999, post.max
    );
}
