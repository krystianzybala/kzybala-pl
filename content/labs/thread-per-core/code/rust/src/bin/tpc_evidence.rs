//! Persistent-worker publication harness for the native-Linux evidence
//! runner — the Rust methodology-parity counterpart of
//! `TpcLinuxEvidenceBenchmark` for the two disciplines with a valid
//! cross-language comparison: the SAME business operation ("record one
//! event in a partition") as either an owner-only plain increment on a
//! line-padded slot (`partitioned`) or a mutex-protected increment on a
//! shared pool (`shared`). Handoff/backpressure scenarios are Java-only
//! (documented in the lab's methodology). Workers are created once,
//! pinned worker-i→cpu-i, and report per-worker progress (fairness).

use std::sync::atomic::{AtomicU8, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

const PHASE_WARMUP: u8 = 0;
const PHASE_MEASURE: u8 = 1;
const PHASE_STOP: u8 = 2;
const STRIDE: usize = 16; // 16 × 8 bytes = 128 bytes between owned slots

struct Args {
    scenario: String,
    workers: usize,
    cpus: Vec<usize>,
    seconds: u64,
    warmup_seconds: u64,
}

fn parse_args() -> Args {
    let mut args = Args {
        scenario: "partitioned".into(),
        workers: 4,
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
            "--workers" => args.workers = value.parse().expect("--workers"),
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

/// Owner-only padded slots: exactly one writer per slot, 128 bytes apart.
struct OwnedSlots {
    slots: Vec<u64>,
}

// SAFETY: each worker only ever writes slots[(worker+1)*STRIDE]; disjoint
// indices, single writer each — the same discipline the Java benchmark's
// plain long[] uses. The raw pointer share below never aliases writes.
struct SlotsPtr(*mut u64);
unsafe impl Send for SlotsPtr {}
unsafe impl Sync for SlotsPtr {}

fn main() {
    let args = parse_args();
    let shared_scenario = match args.scenario.as_str() {
        "partitioned" => false,
        "shared" => true,
        other => panic!("unknown scenario: {other} (partitioned|shared)"),
    };
    let mut owned = OwnedSlots {
        slots: vec![0u64; (args.workers + 2) * STRIDE],
    };
    let slots_ptr = SlotsPtr(owned.slots.as_mut_ptr());
    let slots_arc = Arc::new(slots_ptr);
    let pool: Arc<Vec<Mutex<u64>>> =
        Arc::new((0..args.workers).map(|_| Mutex::new(0u64)).collect());
    let phase = Arc::new(AtomicU8::new(PHASE_WARMUP));

    let mut handles = Vec::new();
    for worker in 0..args.workers {
        let cpu = args.cpus.get(worker).copied();
        let phase = Arc::clone(&phase);
        let pool = Arc::clone(&pool);
        let slots = Arc::clone(&slots_arc);
        handles.push(std::thread::spawn(move || {
            let pinned = if let Some(cpu) = cpu {
                affinity::pin_current_thread(cpu);
                true
            } else {
                false
            };
            let intended = cpu.map(|c| c as i64).unwrap_or(-1);
            let mut events: u64 = 0;
            let slot_index = (worker + 1) * STRIDE;
            loop {
                let p = phase.load(Ordering::Relaxed);
                if p == PHASE_STOP {
                    break;
                }
                if shared_scenario {
                    let mut guard = pool[worker].lock().unwrap();
                    *guard += 1;
                } else {
                    // SAFETY: see SlotsPtr — this worker is the only writer
                    // of slot_index, 128 bytes from any other written slot.
                    unsafe {
                        *slots.0.add(slot_index) += 1;
                    }
                }
                if p == PHASE_MEASURE {
                    events += 1;
                }
            }
            (
                worker,
                pinned,
                intended,
                affinity::current_cpu() as i64,
                events,
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
    let mut per_worker = Vec::new();
    let mut total: u64 = 0;
    for handle in handles {
        let (worker, pinned, intended, observed_end, events) =
            handle.join().expect("worker panicked");
        if pinned && observed_end != intended {
            violations.push(format!(
                "worker {worker}: intended CPU {intended}, finished on {observed_end}"
            ));
        }
        if events == 0 {
            violations.push(format!("worker {worker}: made no progress (starved)"));
        }
        total += events;
        per_worker.push(format!(
            "{{\"worker\":{worker},\"pinned\":{pinned},\"intendedCpu\":{intended},\"observedCpuAtEnd\":{observed_end},\"events\":{events}}}"
        ));
    }

    println!(
        "{{\n  \"harness\": \"tpc_evidence (persistent workers)\",\n  \"scenario\": \"{}\",\n  \"workers\": {},\n  \"warmupSeconds\": {},\n  \"measureSeconds\": {},\n  \"elapsedNs\": {},\n  \"totalEvents\": {},\n  \"eventsPerSecond\": {:.3},\n  \"perWorker\": [{}],\n  \"comparabilityNote\": \"partitioned/shared share the Java operation definition; handoff/backpressure scenarios are Java-only\",\n  \"violations\": {:?}\n}}",
        args.scenario, args.workers, args.warmup_seconds, args.seconds,
        elapsed.as_nanos(), total,
        total as f64 / elapsed.as_secs_f64(),
        per_worker.join(", "), violations
    );
    if !violations.is_empty() {
        std::process::exit(1);
    }
}
