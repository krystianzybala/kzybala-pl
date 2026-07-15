//! Persistent-worker publication harness for the native-Linux evidence
//! runner (scripts/performance-lab/run-linux-evidence.sh) — the Rust
//! methodology-parity counterpart of `SpscLinuxEvidenceBenchmark`:
//!
//! - producer and consumer threads are created ONCE, before the timed
//!   steady-state interval (never inside it — the Criterion spawn/join
//!   benchmark in `benches/` is a separate lifecycle-cost experiment and
//!   is excluded from cross-language comparison);
//! - same operation definition as Java: one producer op = one item
//!   accepted (spin while full); the consumer drains up to `--batch` items
//!   per acknowledgement and verifies every item's monotonic sequence;
//! - fixed power-of-two capacity, preallocated output storage, zero
//!   allocation in the measured path;
//! - workers pinned to explicit CPUs via sched_setaffinity, placement
//!   verified and reported (same evidence fields as the Java
//!   worker-placement artifact).
//!
//! Prints one JSON document on stdout; non-zero exit on any correctness or
//! placement violation.

use spsc_ring_buffer_lab::ring_buffer_with_mode;
use std::sync::atomic::{AtomicU8, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

const PHASE_WARMUP: u8 = 0;
const PHASE_MEASURE: u8 = 1;
const PHASE_STOP: u8 = 2;

struct Args {
    cursor_mode: String,
    batch: usize,
    capacity: usize,
    cpus: Vec<usize>,
    seconds: u64,
    warmup_seconds: u64,
}

fn parse_args() -> Args {
    let mut args = Args {
        cursor_mode: "cached".into(),
        batch: 1,
        capacity: 1024,
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
            "--cursor-mode" => args.cursor_mode = value,
            "--batch" => args.batch = value.parse().expect("--batch"),
            "--capacity" => args.capacity = value.parse().expect("--capacity"),
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
    /// Pin the calling thread to one CPU and verify (sched_setaffinity,
    /// pid 0 = calling thread). Aborts on failure — a publication worker
    /// that cannot pin must not measure.
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

    pub fn native_tid() -> i64 {
        unsafe { libc::syscall(libc::SYS_gettid) }
    }

    /// Kernel per-thread migration counter (`se.nr_migrations`); -1 when
    /// unavailable.
    pub fn thread_migrations() -> i64 {
        let tid = native_tid();
        let path = format!("/proc/self/task/{tid}/sched");
        if let Ok(text) = std::fs::read_to_string(path) {
            for line in text.lines() {
                if line.starts_with("se.nr_migrations") {
                    if let Some(v) = line.split(':').nth(1) {
                        return v.trim().parse().unwrap_or(-1);
                    }
                }
            }
        }
        -1
    }
}

#[cfg(not(target_os = "linux"))]
mod affinity {
    pub fn pin_current_thread(_cpu: usize) {
        panic!("worker pinning requires Linux — this harness only produces publication evidence on the dedicated native-Linux host");
    }
    pub fn current_cpu() -> i32 {
        -1
    }
    pub fn native_tid() -> i64 {
        -1
    }
    pub fn thread_migrations() -> i64 {
        -1
    }
}

#[derive(Default)]
struct Placement {
    role: &'static str,
    intended_cpu: i64,
    observed_after_pin: i64,
    tid: i64,
    migrations_at_pin: i64,
    observed_at_end: i64,
    migrations_at_end: i64,
    pinned: bool,
}

impl Placement {
    fn establish(role: &'static str, cpu: Option<usize>) -> Placement {
        match cpu {
            Some(cpu) => {
                affinity::pin_current_thread(cpu);
                Placement {
                    role,
                    intended_cpu: cpu as i64,
                    observed_after_pin: affinity::current_cpu() as i64,
                    tid: affinity::native_tid(),
                    migrations_at_pin: affinity::thread_migrations(),
                    observed_at_end: -1,
                    migrations_at_end: -1,
                    pinned: true,
                }
            }
            None => Placement {
                role,
                intended_cpu: -1,
                pinned: false,
                ..Default::default()
            },
        }
    }

    fn finish(&mut self) {
        self.observed_at_end = affinity::current_cpu() as i64;
        self.migrations_at_end = affinity::thread_migrations();
    }

    fn violation(&self) -> Option<String> {
        if !self.pinned {
            return None; // unpinned runs are development-only; the runner records the absence
        }
        if self.observed_at_end != self.intended_cpu {
            return Some(format!(
                "{}: intended CPU {} but finished on {}",
                self.role, self.intended_cpu, self.observed_at_end
            ));
        }
        let delta = self.migrations_at_end - self.migrations_at_pin;
        if self.migrations_at_pin >= 0 && delta != 0 {
            return Some(format!(
                "{}: {} migration(s) during the run",
                self.role, delta
            ));
        }
        None
    }

    fn json(&self) -> String {
        format!(
            "{{\"role\":\"{}\",\"pinned\":{},\"intendedCpu\":{},\"observedCpuAfterPin\":{},\"observedCpuAtEnd\":{},\"nativeThreadId\":{},\"migrationsAtPin\":{},\"migrationsAtEnd\":{}}}",
            self.role, self.pinned, self.intended_cpu, self.observed_after_pin,
            self.observed_at_end, self.tid, self.migrations_at_pin, self.migrations_at_end
        )
    }
}

fn main() {
    let args = parse_args();
    let cached = match args.cursor_mode.as_str() {
        "cached" => true,
        "uncached" => false,
        other => panic!("unknown --cursor-mode {other}"),
    };
    let (mut producer, mut consumer) = ring_buffer_with_mode(args.capacity, cached);
    let phase = Arc::new(AtomicU8::new(PHASE_WARMUP));
    let producer_cpu = args.cpus.first().copied();
    let consumer_cpu = args.cpus.get(1).copied();
    let batch = args.batch;

    // Workers are created HERE, once, before any timed interval.
    let producer_phase = Arc::clone(&phase);
    let producer_handle = std::thread::spawn(move || {
        let mut placement = Placement::establish("producer", producer_cpu);
        let mut seq: u64 = 0;
        let mut measured: u64 = 0;
        loop {
            let p = producer_phase.load(Ordering::Relaxed);
            if p == PHASE_STOP {
                break;
            }
            if producer.try_produce(seq) {
                seq += 1;
                if p == PHASE_MEASURE {
                    measured += 1;
                }
            } else {
                std::hint::spin_loop();
            }
        }
        placement.finish();
        (measured, placement)
    });

    let consumer_phase = Arc::clone(&phase);
    let consumer_handle = std::thread::spawn(move || {
        let mut placement = Placement::establish("consumer", consumer_cpu);
        let mut out = vec![0u64; batch]; // preallocated once, outside the measured interval
        let mut expected: u64 = 0;
        let mut measured: u64 = 0;
        let mut sequence_valid = true;
        loop {
            let p = consumer_phase.load(Ordering::Relaxed);
            let n = consumer.try_consume_batch(&mut out);
            if n == 0 {
                if p == PHASE_STOP {
                    break;
                }
                std::hint::spin_loop();
                continue;
            }
            for (i, item) in out.iter().enumerate().take(n) {
                if *item != expected + i as u64 {
                    sequence_valid = false;
                }
            }
            expected += n as u64;
            if p == PHASE_MEASURE {
                measured += n as u64;
            }
        }
        placement.finish();
        (measured, expected, sequence_valid, placement)
    });

    std::thread::sleep(Duration::from_secs(args.warmup_seconds));
    let start = Instant::now();
    phase.store(PHASE_MEASURE, Ordering::Relaxed);
    std::thread::sleep(Duration::from_secs(args.seconds));
    phase.store(PHASE_STOP, Ordering::Relaxed);
    let elapsed = start.elapsed();

    let (produced_measured, mut producer_placement) =
        producer_handle.join().expect("producer panicked");
    let (consumed_measured, consumed_total, sequence_valid, mut consumer_placement) =
        consumer_handle.join().expect("consumer panicked");
    producer_placement.finish();
    consumer_placement.finish();

    let mut violations: Vec<String> = Vec::new();
    if let Some(v) = producer_placement.violation() {
        violations.push(v);
    }
    if let Some(v) = consumer_placement.violation() {
        violations.push(v);
    }
    if !sequence_valid {
        violations.push("sequence violation (stale read or overwrite)".into());
    }
    if consumed_total == 0 {
        violations.push("no items transferred".into());
    }

    let items_per_second = consumed_measured as f64 / elapsed.as_secs_f64();
    println!(
        "{{\n  \"harness\": \"spsc_evidence (persistent workers)\",\n  \"cursorMode\": \"{}\",\n  \"batch\": {},\n  \"capacity\": {},\n  \"warmupSeconds\": {},\n  \"measureSeconds\": {},\n  \"elapsedNs\": {},\n  \"itemsConsumedInWindow\": {},\n  \"itemsProducedInWindow\": {},\n  \"itemsPerSecond\": {:.3},\n  \"sequenceValid\": {},\n  \"workers\": [{}, {}],\n  \"violations\": {:?},\n  \"note\": \"per-op phase check (Relaxed load) is harness overhead analogous to JMH invocation accounting; identical across scenarios\"\n}}",
        args.cursor_mode, args.batch, args.capacity, args.warmup_seconds, args.seconds,
        elapsed.as_nanos(), consumed_measured, produced_measured, items_per_second, sequence_valid,
        producer_placement.json(), consumer_placement.json(), violations
    );

    if !violations.is_empty() {
        std::process::exit(1);
    }
}
