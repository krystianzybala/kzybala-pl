//! Persistent-worker publication harness for the native-Linux evidence
//! runner — the Rust methodology-parity counterpart of
//! `CasLinuxEvidenceBenchmark`: N contender threads created once (outside
//! the timed interval), each pinned to its own physical core, retrying CAS
//! on one shared counter under the selected retry policy, against the
//! single-writer baseline. One operation = one successful increment; a
//! failed CAS applies the policy and retries inside the operation.
//!
//! Policies (applied only after a FAILED attempt):
//!   none      — immediate retry (spin hint)
//!   fixed     — park ~1 µs per failure
//!   expjitter — exponential (1 µs base, ×2/streak, 64 µs cap) with
//!               deterministic xorshift jitter seeded by worker index
//!   baseline  — single-writer plain increment, exactly 1 thread
//!
//! Prints one JSON document with per-worker success/failure counts (the
//! fairness/progress distribution) and placement evidence; non-zero exit
//! on any placement or correctness violation.

use std::sync::atomic::{AtomicU64, AtomicU8, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

const PHASE_WARMUP: u8 = 0;
const PHASE_MEASURE: u8 = 1;
const PHASE_STOP: u8 = 2;

struct Args {
    scenario: String,
    threads: usize,
    cpus: Vec<usize>,
    seconds: u64,
    warmup_seconds: u64,
}

fn parse_args() -> Args {
    let mut args = Args {
        scenario: "none".into(),
        threads: 1,
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
            "--scenario" => args.scenario = value,
            "--threads" => args.threads = value.parse().expect("--threads"),
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

fn park_nanos(nanos: u64) {
    std::thread::sleep(Duration::from_nanos(nanos));
}

fn main() {
    let args = parse_args();
    let baseline = args.scenario == "baseline";
    if baseline {
        assert_eq!(
            args.threads, 1,
            "single-writer baseline requires exactly 1 thread"
        );
    }
    let shared = Arc::new(AtomicU64::new(0));
    let phase = Arc::new(AtomicU8::new(PHASE_WARMUP));

    let mut handles = Vec::new();
    for worker in 0..args.threads {
        let cpu = args.cpus.get(worker).copied();
        let shared = Arc::clone(&shared);
        let phase = Arc::clone(&phase);
        let scenario = args.scenario.clone();
        handles.push(std::thread::spawn(move || {
            let pinned = if let Some(cpu) = cpu {
                affinity::pin_current_thread(cpu);
                true
            } else {
                false
            };
            let intended = cpu.map(|c| c as i64).unwrap_or(-1);
            // deterministic per-worker jitter seed — recorded in output
            let mut jitter_state: u64 = 0x9E37_79B9_7F4A_7C15 ^ (worker as u64 + 1);
            let mut streak: u32 = 0;
            let mut successes: u64 = 0;
            let mut failures: u64 = 0;
            let mut plain: u64 = 0;
            loop {
                let p = phase.load(Ordering::Relaxed);
                if p == PHASE_STOP {
                    break;
                }
                let measuring = p == PHASE_MEASURE;
                if baseline {
                    plain += 1;
                    if measuring {
                        successes += 1;
                    }
                    continue;
                }
                // one operation = one successful increment
                loop {
                    let current = shared.load(Ordering::Relaxed);
                    if shared
                        .compare_exchange(current, current + 1, Ordering::AcqRel, Ordering::Relaxed)
                        .is_ok()
                    {
                        streak = 0;
                        if measuring {
                            successes += 1;
                        }
                        break;
                    }
                    if measuring {
                        failures += 1;
                    }
                    match scenario.as_str() {
                        "none" => std::hint::spin_loop(),
                        "fixed" => park_nanos(1_000),
                        "expjitter" => {
                            streak = (streak + 1).min(6);
                            let cap: u64 = 1_000u64 << streak;
                            jitter_state ^= jitter_state >> 12;
                            jitter_state ^= jitter_state << 25;
                            jitter_state ^= jitter_state >> 27;
                            let jitter =
                                jitter_state.wrapping_mul(0x2545_F491_4F6C_DD1D) % (cap / 2);
                            park_nanos(cap / 2 + jitter);
                        }
                        other => panic!("unknown scenario: {other}"),
                    }
                }
            }
            let observed_end = affinity::current_cpu() as i64;
            (
                worker,
                pinned,
                intended,
                observed_end,
                successes,
                failures,
                plain,
            )
        }));
    }

    std::thread::sleep(Duration::from_secs(args.warmup_seconds));
    let start = Instant::now();
    phase.store(PHASE_MEASURE, Ordering::Relaxed);
    std::thread::sleep(Duration::from_secs(args.seconds));
    phase.store(PHASE_STOP, Ordering::Relaxed);
    let elapsed = start.elapsed();

    let mut violations: Vec<String> = Vec::new();
    let mut workers_json = Vec::new();
    let mut total_success: u64 = 0;
    let mut total_failures: u64 = 0;
    for handle in handles {
        let (worker, pinned, intended, observed_end, successes, failures, plain) =
            handle.join().expect("worker panicked");
        if pinned && observed_end != intended {
            violations.push(format!(
                "worker {worker}: intended CPU {intended}, finished on {observed_end}"
            ));
        }
        if successes == 0 && plain == 0 {
            violations.push(format!("worker {worker}: made no progress (starved)"));
        }
        total_success += successes;
        total_failures += failures;
        workers_json.push(format!(
            "{{\"worker\":{worker},\"pinned\":{pinned},\"intendedCpu\":{intended},\"observedCpuAtEnd\":{observed_end},\"successes\":{successes},\"failedAttempts\":{failures}}}"
        ));
    }
    if !baseline {
        let counted = shared.load(Ordering::Relaxed);
        if counted == 0 {
            violations.push("shared counter never advanced".into());
        }
        // measured successes are a window over the counter's whole life:
        // the total counter must be >= the windowed successes.
        if counted < total_success {
            violations.push(format!(
                "lost updates: counter={counted} < windowed successes={total_success}"
            ));
        }
    }

    let ops_per_second = total_success as f64 / elapsed.as_secs_f64();
    println!(
        "{{\n  \"harness\": \"cas_evidence (persistent workers)\",\n  \"scenario\": \"{}\",\n  \"threads\": {},\n  \"warmupSeconds\": {},\n  \"measureSeconds\": {},\n  \"elapsedNs\": {},\n  \"successfulOps\": {},\n  \"failedAttempts\": {},\n  \"retriesPerSuccess\": {:.4},\n  \"opsPerSecond\": {:.3},\n  \"jitterSeedScheme\": \"xorshift64*, seed = 0x9E3779B97F4A7C15 ^ (workerIndex+1)\",\n  \"workers\": [{}],\n  \"violations\": {:?}\n}}",
        args.scenario, args.threads, args.warmup_seconds, args.seconds, elapsed.as_nanos(),
        total_success, total_failures,
        if total_success > 0 { total_failures as f64 / total_success as f64 } else { 0.0 },
        ops_per_second, workers_json.join(", "), violations
    );

    if !violations.is_empty() {
        std::process::exit(1);
    }
}
