//! **A real language-mechanism difference, not smoothed over** (this
//! lab's "claiming Rust has no pauses" trap, benchmark.md): Rust has no
//! JIT, no GC, and no runtime-inserted safepoint polling — there is
//! nothing for this binary to observe unless it builds stop-the-world
//! coordination itself. That is exactly what it does: a coordinator sets
//! a shared `AtomicBool` "pause requested" flag, and each worker thread
//! checks it at a chosen polling granularity, acknowledging via a shared
//! `AtomicUsize` counter. "Time to pause" here is the coordinator's own
//! measured wall-clock time from setting the flag to observing every
//! required worker's acknowledgment — a real, explicit measurement of
//! the same *idea* Java's JFR reports automatically, built by hand
//! because Rust has no runtime to build it for you.
//!
//! Workers repeat their chunked pass in a loop (checking a separate
//! `stop` flag) rather than running once: a single pass over this lab's
//! range is fast enough on modern hardware that a worker could finish
//! and exit before the coordinator ever requests a pause, which would
//! make the "wait for every acknowledgment" loop spin forever. Looping
//! keeps every worker perpetually available to acknowledge, and only the
//! FIRST completed pass's sum is kept for the correctness check (every
//! pass computes the identical value).
//!
//! This also means Rust's cost model is fundamentally different from
//! Java's: only threads this coordinator explicitly registers are ever
//! waited on. A thread blocked in native code, or one that was never
//! told to poll at all (this lab's idle threads), costs the coordinator
//! nothing — not because Rust is smarter about it, but because nobody
//! asked it to wait. Java's automatic per-thread handshake, by contrast,
//! costs something for every JVM thread whether or not it is relevant to
//! a specific pause (java.md's `manyIdleThreads` finding).

use safepoints_lab::{
    sum_range, FLEET_SIZE, IDLE_THREAD_COUNT, NATIVE_SLEEP_MICROS, NUM_CHUNKS,
    PER_FLEET_WORKER_ITERATIONS, TOTAL_ITERATIONS,
};
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

struct Args {
    variant: String,
    dataset: String,
}

fn parse_args() -> Args {
    let mut args = Args { variant: "cooperativeLoop".into(), dataset: "numericLoop".into() };
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

fn native_sleep(micros: u32) {
    unsafe {
        libc::usleep(micros);
    }
}

/// Repeats a chunked pass over `[start, start+count)` until `stop` is
/// set, checking `pause`/`acked` between chunks (or, for
/// `longLoopSparsePolls`, only once at the very end of each pass — a
/// deliberately sparse poll that makes "time to pause" as long as a
/// whole pass). `native_sleep_per_chunk` interleaves a real libc
/// downcall between chunks, matching the `nativeSleepDowncall` dataset.
/// Records only the first completed pass's sum for the correctness check.
#[allow(clippy::too_many_arguments)]
fn worker(
    start: u64,
    count: u64,
    sparse: bool,
    native_sleep_per_chunk: bool,
    pause: Arc<AtomicBool>,
    acked: Arc<AtomicUsize>,
    stop: Arc<AtomicBool>,
    first_pass_sum: Arc<AtomicU64>,
    recorded: Arc<AtomicBool>,
) {
    let chunk = count / NUM_CHUNKS;
    loop {
        let mut sum: u64 = 0;
        for c in 0..NUM_CHUNKS {
            sum = sum.wrapping_add(sum_range(start + c * chunk, chunk));
            if native_sleep_per_chunk {
                native_sleep(NATIVE_SLEEP_MICROS);
            }
            if !sparse && pause.load(Ordering::Acquire) {
                acked.fetch_add(1, Ordering::AcqRel);
                while pause.load(Ordering::Acquire) {
                    std::hint::spin_loop();
                }
            }
        }
        if sparse && pause.load(Ordering::Acquire) {
            acked.fetch_add(1, Ordering::AcqRel);
        }
        if recorded
            .compare_exchange(false, true, Ordering::AcqRel, Ordering::Acquire)
            .is_ok()
        {
            first_pass_sum.store(sum, Ordering::Release);
        }
        if stop.load(Ordering::Acquire) {
            break;
        }
    }
}

fn main() {
    let args = parse_args();
    let sparse = args.variant == "longLoopSparsePolls";
    let native_sleep_per_chunk = args.dataset == "nativeSleepDowncall";

    let pause = Arc::new(AtomicBool::new(false));
    let acked = Arc::new(AtomicUsize::new(0));
    let stop = Arc::new(AtomicBool::new(false));
    let mut required_acks = 0usize;
    let mut handles = Vec::new();
    let mut sums = Vec::new();

    let mut spawn_worker = |start: u64, count: u64| {
        let (p, a, s) = (pause.clone(), acked.clone(), stop.clone());
        let sum_cell = Arc::new(AtomicU64::new(0));
        let recorded = Arc::new(AtomicBool::new(false));
        sums.push(sum_cell.clone());
        handles.push(std::thread::spawn(move || {
            worker(start, count, sparse, native_sleep_per_chunk, p, a, s, sum_cell, recorded);
        }));
    };

    match args.dataset.as_str() {
        "numericLoop" | "nativeSleepDowncall" => {
            required_acks += 1;
            spawn_worker(0, TOTAL_ITERATIONS);
        }
        "threadFleet" => {
            required_acks += FLEET_SIZE as usize;
            for w in 0..FLEET_SIZE {
                spawn_worker(w * PER_FLEET_WORKER_ITERATIONS, PER_FLEET_WORKER_ITERATIONS);
            }
        }
        other => panic!("unknown dataset: {other}"),
    }

    // Extra threads: registered with the coordinator only if the variant
    // actually asks them to participate — see this file's doc comment.
    let mut extra_handles = Vec::new();
    let stop_extras = Arc::new(AtomicBool::new(false));
    if args.variant == "threadInNativeCall" {
        let s = stop_extras.clone();
        extra_handles.push(std::thread::spawn(move || {
            while !s.load(Ordering::Acquire) {
                native_sleep(20_000);
            }
        }));
    }
    if args.variant == "manyIdleThreads" {
        for _ in 0..IDLE_THREAD_COUNT {
            let s = stop_extras.clone();
            extra_handles.push(std::thread::spawn(move || {
                while !s.load(Ordering::Acquire) {
                    std::thread::sleep(Duration::from_millis(20));
                }
            }));
        }
    }
    // allocationPressureTrigger: no GC exists to pressure in Rust — this
    // variant is a documented pass-through, identical to cooperativeLoop
    // (rust.md). Included only for matrix parity with the Java side.

    std::thread::sleep(Duration::from_millis(50)); // let extras reach steady state first

    // Request a pause and measure how long it takes every REQUIRED
    // worker to acknowledge — this run's "time to pause". Workers loop
    // continuously, so this can never race a worker's own completion.
    let request_time = Instant::now();
    pause.store(true, Ordering::Release);
    while acked.load(Ordering::Acquire) < required_acks {
        std::hint::spin_loop();
    }
    let time_to_pause = request_time.elapsed();
    pause.store(false, Ordering::Release);

    // Let every worker complete (and record) at least one pass, then stop them.
    std::thread::sleep(Duration::from_millis(20));
    stop.store(true, Ordering::Release);

    let mut checksum: u64 = 0;
    for h in handles {
        h.join().expect("worker panicked");
    }
    for s in &sums {
        checksum = checksum.wrapping_add(s.load(Ordering::Acquire));
    }
    stop_extras.store(true, Ordering::Release);
    for h in extra_handles {
        let _ = h.join();
    }

    let expected = safepoints_lab::EXPECTED_TOTAL;
    assert_eq!(checksum, expected, "correctness oracle failed for {}/{}", args.variant, args.dataset);

    println!(
        "{{\n  \"harness\": \"safepoint_coordinator\",\n  \"variant\": \"{}\",\n  \"dataset\": \"{}\",\n  \"checksum\": {},\n  \"requiredAcks\": {},\n  \"timeToPauseNanos\": {}\n}}",
        args.variant, args.dataset, checksum, required_acks, time_to_pause.as_nanos()
    );
}
