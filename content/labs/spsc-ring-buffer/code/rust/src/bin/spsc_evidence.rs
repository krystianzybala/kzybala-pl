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
//!   allocation in the measured path.
//!
//! Worker placement protocol (post 2026-07-18 affinity-diagnostics fix —
//! the parent previously overwrote worker end-state after join() with its
//! OWN thread's CPU and migration counter, producing identical migration
//! counts for both workers and a false consumer-placement violation):
//!
//!   1. spawn producer, spawn consumer;
//!   2. EACH WORKER, on its own thread: read its own Linux TID
//!      (syscall(SYS_gettid)), sample migrations BEFORE pinning, pin
//!      itself (sched_setaffinity(0) = calling task), verify the mask via
//!      sched_getaffinity (exactly the intended CPU), verify
//!      sched_getcpu() == intended, sample migrations AFTER pinning;
//!   3. each worker reports READY (with its TID and pin verdict) and holds;
//!   4. the parent waits for both READY, rejects duplicate/invalid TIDs
//!      and any failed pin, then releases the start barrier;
//!   5. workers sample their own migration counter at the
//!      warmup→measure and measure→stop transitions; every end-of-run
//!      observation (CPU, affinity mask, migrations, capturing TID) is
//!      taken by the worker itself before it exits and returned through
//!      the join handle. The parent NEVER queries worker placement.
//!
//! `migrationsDuringMeasurement = atMeasurementEnd − atMeasurementStart`
//! is the publication-relevant value; pre-pin/startup migrations are
//! reported separately and never counted against the run. A decreasing
//! counter is an infrastructure error. Process-tree counters from the
//! surrounding `perf stat` (processTreeCpuMigrations) include launcher and
//! service threads and are NEVER comparable with these per-worker values.
//!
//! Prints one JSON document on stdout; non-zero exit on any correctness,
//! placement, or diagnostics-integrity violation.

use spsc_ring_buffer_lab::ring_buffer_with_mode;
use std::sync::atomic::{AtomicU8, Ordering};
use std::sync::{mpsc, Arc};
use std::time::{Duration, Instant};

const PHASE_HOLD: u8 = 0;
const PHASE_WARMUP: u8 = 1;
const PHASE_MEASURE: u8 = 2;
const PHASE_STOP: u8 = 3;

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

/// Thread-identity/affinity operations for the CALLING thread. Injectable
/// so the pin/verify/report flow is testable without a specific host CPU
/// numbering; `LinuxAffinity` is the real implementation.
trait AffinityOps {
    /// Own Linux task id (NOT the process id — every worker has its own).
    fn native_tid(&self) -> i64;
    /// sched_setaffinity(0, {cpu}) for the calling task; true on success.
    fn set_affinity(&self, cpu: usize) -> bool;
    /// sched_getaffinity(0) for the calling task, as an explicit CPU list.
    fn allowed_cpus(&self) -> Vec<usize>;
    fn current_cpu(&self) -> i64;
    /// Own kernel migration counter (se.nr_migrations); -1 if unavailable.
    fn migrations(&self) -> i64;
    /// The exact procfs path this thread's counters are read from.
    fn procfs_path(&self) -> String;
}

#[cfg(target_os = "linux")]
struct LinuxAffinity;

#[cfg(target_os = "linux")]
impl AffinityOps for LinuxAffinity {
    fn native_tid(&self) -> i64 {
        unsafe { libc::syscall(libc::SYS_gettid) }
    }
    fn set_affinity(&self, cpu: usize) -> bool {
        unsafe {
            let mut set: libc::cpu_set_t = std::mem::zeroed();
            libc::CPU_SET(cpu, &mut set);
            libc::sched_setaffinity(0, std::mem::size_of::<libc::cpu_set_t>(), &set) == 0
        }
    }
    fn allowed_cpus(&self) -> Vec<usize> {
        unsafe {
            let mut set: libc::cpu_set_t = std::mem::zeroed();
            if libc::sched_getaffinity(0, std::mem::size_of::<libc::cpu_set_t>(), &mut set) != 0 {
                return vec![];
            }
            (0..1024).filter(|&c| libc::CPU_ISSET(c, &set)).collect()
        }
    }
    fn current_cpu(&self) -> i64 {
        unsafe { libc::sched_getcpu() as i64 }
    }
    fn migrations(&self) -> i64 {
        if let Ok(text) = std::fs::read_to_string(self.procfs_path()) {
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
    fn procfs_path(&self) -> String {
        format!("/proc/self/task/{}/sched", self.native_tid())
    }
}

#[cfg(not(target_os = "linux"))]
struct LinuxAffinity;

#[cfg(not(target_os = "linux"))]
impl AffinityOps for LinuxAffinity {
    fn native_tid(&self) -> i64 {
        -1
    }
    fn set_affinity(&self, _cpu: usize) -> bool {
        false // pinning requires Linux; requesting --cpus elsewhere fails loudly
    }
    fn allowed_cpus(&self) -> Vec<usize> {
        vec![]
    }
    fn current_cpu(&self) -> i64 {
        -1
    }
    fn migrations(&self) -> i64 {
        -1
    }
    fn procfs_path(&self) -> String {
        "unavailable".into()
    }
}

/// Everything a worker observes about its own identity and placement.
/// Captured EXCLUSIVELY on the worker's own thread; the parent only reads.
#[derive(Clone, Debug, Default)]
struct WorkerReport {
    role: &'static str,
    pin_requested: bool,
    intended_cpu: i64,
    native_tid: i64,
    /// TID observed when the END-of-run fields were captured — must equal
    /// native_tid, proving the report was not populated by another thread.
    captured_by_tid: i64,
    procfs_path: String,
    affinity_set_succeeded: bool,
    affinity_mask_verified: bool,
    cpu_after_pin_verified: bool,
    /// All four: syscall ok + mask exactly {intended} + observed CPU ==
    /// intended + valid own TID. Never just "sched_setaffinity returned 0".
    pinned: bool,
    allowed_cpus_after_pin: Vec<usize>,
    allowed_cpus_at_end: Vec<usize>,
    observed_cpu_after_pin: i64,
    observed_cpu_at_end: i64,
    migrations_before_pin: i64,
    migrations_after_pin: i64,
    migrations_at_measurement_start: i64,
    migrations_at_measurement_end: i64,
}

impl WorkerReport {
    fn migrations_during_measurement(&self) -> i64 {
        if self.migrations_at_measurement_start < 0 || self.migrations_at_measurement_end < 0 {
            return -1;
        }
        self.migrations_at_measurement_end - self.migrations_at_measurement_start
    }

    fn json(&self) -> String {
        format!(
            concat!(
                "{{\"role\":\"{}\",\"pinRequested\":{},\"pinned\":{},",
                "\"affinitySetSucceeded\":{},\"affinityMaskVerified\":{},\"cpuAfterPinVerified\":{},",
                "\"intendedCpu\":{},\"nativeThreadId\":{},\"capturedByTid\":{},\"procfsPath\":\"{}\",",
                "\"allowedCpusAfterPin\":{:?},\"allowedCpusAtEnd\":{:?},",
                "\"observedCpuAfterPin\":{},\"observedCpuAtEnd\":{},",
                "\"migrationsBeforePin\":{},\"migrationsAfterPin\":{},",
                "\"migrationsAtMeasurementStart\":{},\"migrationsAtMeasurementEnd\":{},",
                "\"migrationsDuringMeasurement\":{}}}"
            ),
            self.role, self.pin_requested, self.pinned,
            self.affinity_set_succeeded, self.affinity_mask_verified, self.cpu_after_pin_verified,
            self.intended_cpu, self.native_tid, self.captured_by_tid, self.procfs_path,
            self.allowed_cpus_after_pin, self.allowed_cpus_at_end,
            self.observed_cpu_after_pin, self.observed_cpu_at_end,
            self.migrations_before_pin, self.migrations_after_pin,
            self.migrations_at_measurement_start, self.migrations_at_measurement_end,
            self.migrations_during_measurement()
        )
    }
}

/// Pin-and-verify sequence, run ON the worker thread as its first act:
/// sample migrations before pinning, pin, verify mask, verify CPU, sample
/// again. Returns the report; never panics — a failed pin is reported and
/// rejected by the parent BEFORE any measurement starts.
fn establish_on_worker(ops: &dyn AffinityOps, role: &'static str, cpu: Option<usize>) -> WorkerReport {
    let mut report = WorkerReport {
        role,
        native_tid: ops.native_tid(),
        procfs_path: ops.procfs_path(),
        intended_cpu: -1,
        observed_cpu_at_end: -1,
        migrations_at_measurement_start: -1,
        migrations_at_measurement_end: -1,
        ..Default::default()
    };
    report.captured_by_tid = report.native_tid;
    let Some(cpu) = cpu else {
        // Development-only unpinned mode: identity still recorded.
        report.migrations_before_pin = ops.migrations();
        report.migrations_after_pin = report.migrations_before_pin;
        report.allowed_cpus_after_pin = ops.allowed_cpus();
        report.observed_cpu_after_pin = ops.current_cpu();
        return report;
    };
    report.pin_requested = true;
    report.intended_cpu = cpu as i64;
    report.migrations_before_pin = ops.migrations();
    report.affinity_set_succeeded = ops.set_affinity(cpu);
    report.allowed_cpus_after_pin = ops.allowed_cpus();
    report.affinity_mask_verified = report.allowed_cpus_after_pin == vec![cpu];
    std::thread::yield_now();
    report.observed_cpu_after_pin = ops.current_cpu();
    report.cpu_after_pin_verified = report.observed_cpu_after_pin == cpu as i64;
    report.migrations_after_pin = ops.migrations();
    report.pinned = report.affinity_set_succeeded
        && report.affinity_mask_verified
        && report.cpu_after_pin_verified
        && report.native_tid > 0;
    report
}

/// End-of-run capture, run ON the worker thread at the measure→stop
/// transition (before any drain/teardown work).
fn capture_end_on_worker(ops: &dyn AffinityOps, report: &mut WorkerReport) {
    report.observed_cpu_at_end = ops.current_cpu();
    report.migrations_at_measurement_end = ops.migrations();
    report.allowed_cpus_at_end = ops.allowed_cpus();
    report.captured_by_tid = ops.native_tid();
}

/// Placement/diagnostics validation — pure so the policy is unit-testable.
/// Publication and smoke share the same rules: broken placement is never
/// silently accepted at any duration.
fn validate_reports(producer: &WorkerReport, consumer: &WorkerReport) -> Vec<String> {
    let mut violations = Vec::new();
    for r in [producer, consumer] {
        if !r.pin_requested {
            continue; // development-only unpinned run; runner records the absence
        }
        if !r.pinned {
            violations.push(format!(
                "{}: not pinned (affinitySetSucceeded={}, affinityMaskVerified={} allowedCpusAfterPin={:?}, cpuAfterPinVerified={} observed={}, tid={})",
                r.role, r.affinity_set_succeeded, r.affinity_mask_verified,
                r.allowed_cpus_after_pin, r.cpu_after_pin_verified,
                r.observed_cpu_after_pin, r.native_tid
            ));
            continue;
        }
        if r.captured_by_tid != r.native_tid {
            violations.push(format!(
                "{}: end-state captured by TID {} but the worker is TID {} — report not captured by its own worker",
                r.role, r.captured_by_tid, r.native_tid
            ));
        }
        if r.observed_cpu_at_end != r.intended_cpu {
            violations.push(format!(
                "{}: intended CPU {} but finished on {}",
                r.role, r.intended_cpu, r.observed_cpu_at_end
            ));
        }
        if r.allowed_cpus_at_end != vec![r.intended_cpu as usize] {
            violations.push(format!(
                "{}: end affinity mask {:?} is not exactly [{}]",
                r.role, r.allowed_cpus_at_end, r.intended_cpu
            ));
        }
        // Counter sanity first: a decreasing kernel counter is an
        // infrastructure error, never a measurement result.
        let series = [
            r.migrations_before_pin,
            r.migrations_after_pin,
            r.migrations_at_measurement_start,
            r.migrations_at_measurement_end,
        ];
        if series.iter().any(|&m| m < 0) {
            violations.push(format!(
                "{}: infrastructure error — migration counter unavailable ({:?})",
                r.role, series
            ));
        } else if series.windows(2).any(|w| w[1] < w[0]) {
            violations.push(format!(
                "{}: infrastructure error — migration counter decreased ({:?})",
                r.role, series
            ));
        } else if r.migrations_during_measurement() != 0 {
            // Pre-pin/startup migrations (beforePin→atMeasurementStart) are
            // reported but never counted; only the measurement window gates.
            violations.push(format!(
                "{}: {} migration(s) during measurement",
                r.role,
                r.migrations_during_measurement()
            ));
        }
    }
    if producer.pin_requested && consumer.pin_requested {
        if producer.native_tid == consumer.native_tid {
            violations.push(format!(
                "duplicate worker TID {} — producer and consumer reports must come from distinct tasks",
                producer.native_tid
            ));
        }
        if producer.procfs_path == consumer.procfs_path && producer.procfs_path != "unavailable" {
            violations.push(format!(
                "producer and consumer diagnostics read the same procfs path {}",
                producer.procfs_path
            ));
        }
    }
    violations
}

fn main() {
    let args = parse_args();
    let cached = match args.cursor_mode.as_str() {
        "cached" => true,
        "uncached" => false,
        other => panic!("unknown --cursor-mode {other}"),
    };
    let (mut producer, mut consumer) = ring_buffer_with_mode(args.capacity, cached);
    let phase = Arc::new(AtomicU8::new(PHASE_HOLD));
    let producer_cpu = args.cpus.first().copied();
    let consumer_cpu = args.cpus.get(1).copied();
    let batch = args.batch;
    let (ready_tx, ready_rx) = mpsc::channel::<(&'static str, i64, bool)>();

    // Workers are created HERE, once, before any timed interval. Each pins
    // and verifies ITSELF, reports READY, and holds until released.
    let producer_phase = Arc::clone(&phase);
    let producer_ready = ready_tx.clone();
    let producer_handle = std::thread::spawn(move || {
        let ops = LinuxAffinity;
        let mut report = establish_on_worker(&ops, "producer", producer_cpu);
        producer_ready
            .send(("producer", report.native_tid, !report.pin_requested || report.pinned))
            .ok();
        let mut seq: u64 = 0;
        let mut measured: u64 = 0;
        let mut prev = PHASE_HOLD;
        loop {
            let p = producer_phase.load(Ordering::Relaxed);
            if p != prev {
                if p == PHASE_MEASURE {
                    report.migrations_at_measurement_start = ops.migrations();
                }
                if p == PHASE_STOP {
                    capture_end_on_worker(&ops, &mut report);
                    break;
                }
                prev = p;
            }
            if p == PHASE_HOLD {
                std::hint::spin_loop();
                continue;
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
        (measured, report)
    });

    let consumer_phase = Arc::clone(&phase);
    let consumer_ready = ready_tx;
    let consumer_handle = std::thread::spawn(move || {
        let ops = LinuxAffinity;
        let mut report = establish_on_worker(&ops, "consumer", consumer_cpu);
        consumer_ready
            .send(("consumer", report.native_tid, !report.pin_requested || report.pinned))
            .ok();
        let mut out = vec![0u64; batch]; // preallocated once, outside the measured interval
        let mut expected: u64 = 0;
        let mut measured: u64 = 0;
        let mut sequence_valid = true;
        let mut prev = PHASE_HOLD;
        let mut stopping = false;
        loop {
            let p = consumer_phase.load(Ordering::Relaxed);
            if p != prev {
                if p == PHASE_MEASURE {
                    report.migrations_at_measurement_start = ops.migrations();
                }
                if p == PHASE_STOP && !stopping {
                    // measurement window ends HERE; the post-stop drain
                    // below is teardown, outside every reported counter
                    capture_end_on_worker(&ops, &mut report);
                    stopping = true;
                }
                prev = p;
            }
            if p == PHASE_HOLD {
                std::hint::spin_loop();
                continue;
            }
            let n = consumer.try_consume_batch(&mut out);
            if n == 0 {
                if stopping {
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
        (measured, expected, sequence_valid, report)
    });

    // Barrier: measurement starts only after BOTH workers proved their
    // placement. A failed pin or a duplicate/invalid TID aborts before any
    // timing; the workers are released to observe PHASE_STOP and exit.
    let mut ready: Vec<(&'static str, i64, bool)> = Vec::new();
    let mut startup_violations: Vec<String> = Vec::new();
    for _ in 0..2 {
        match ready_rx.recv_timeout(Duration::from_secs(30)) {
            Ok(entry) => ready.push(entry),
            Err(_) => startup_violations
                .push("infrastructure error: a worker did not report READY within 30s".into()),
        }
    }
    let pinning_requested = producer_cpu.is_some() || consumer_cpu.is_some();
    if pinning_requested {
        for (role, tid, pin_ok) in &ready {
            if !*pin_ok {
                startup_violations.push(format!("{role}: failed to pin before the start barrier"));
            }
            if *tid <= 0 {
                startup_violations.push(format!("{role}: invalid native TID {tid}"));
            }
        }
        if ready.len() == 2 && ready[0].1 == ready[1].1 && ready[0].1 > 0 {
            startup_violations.push(format!("duplicate worker TID {} at READY", ready[0].1));
        }
    }

    let elapsed;
    if startup_violations.is_empty() {
        phase.store(PHASE_WARMUP, Ordering::Relaxed);
        std::thread::sleep(Duration::from_secs(args.warmup_seconds));
        let start = Instant::now();
        phase.store(PHASE_MEASURE, Ordering::Relaxed);
        std::thread::sleep(Duration::from_secs(args.seconds));
        phase.store(PHASE_STOP, Ordering::Relaxed);
        elapsed = start.elapsed();
    } else {
        // no measurement on a broken start: release workers straight to STOP
        phase.store(PHASE_STOP, Ordering::Relaxed);
        elapsed = Duration::from_secs(0);
    }

    let (produced_measured, producer_report) = producer_handle.join().expect("producer panicked");
    let (consumed_measured, consumed_total, sequence_valid, consumer_report) =
        consumer_handle.join().expect("consumer panicked");
    // The parent takes NO placement observations of its own: every field in
    // the reports was captured on the owning worker's thread.

    let mut violations = startup_violations;
    violations.extend(validate_reports(&producer_report, &consumer_report));
    if !sequence_valid {
        violations.push("sequence violation (stale read or overwrite)".into());
    }
    if violations.is_empty() && consumed_total == 0 {
        violations.push("no items transferred".into());
    }

    let items_per_second = if elapsed.as_secs_f64() > 0.0 {
        consumed_measured as f64 / elapsed.as_secs_f64()
    } else {
        0.0
    };
    println!(
        "{{\n  \"harness\": \"spsc_evidence (persistent workers)\",\n  \"cursorMode\": \"{}\",\n  \"batch\": {},\n  \"capacity\": {},\n  \"warmupSeconds\": {},\n  \"measureSeconds\": {},\n  \"elapsedNs\": {},\n  \"itemsConsumedInWindow\": {},\n  \"itemsProducedInWindow\": {},\n  \"itemsPerSecond\": {:.3},\n  \"sequenceValid\": {},\n  \"workers\": [{}, {}],\n  \"violations\": {:?},\n  \"counterScope\": \"worker fields are per-task (own TID procfs); any surrounding perf stat cpu-migrations is processTreeCpuMigrations (launcher/service threads included) and is never comparable with per-worker counters\",\n  \"note\": \"per-op phase check (Relaxed load) is harness overhead analogous to JMH invocation accounting; identical across scenarios\"\n}}",
        args.cursor_mode, args.batch, args.capacity, args.warmup_seconds, args.seconds,
        elapsed.as_nanos(), consumed_measured, produced_measured, items_per_second, sequence_valid,
        producer_report.json(), consumer_report.json(), violations
    );

    if !violations.is_empty() {
        std::process::exit(1);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;

    /// Injectable fake: scripted mask/CPU/migration behavior plus a call
    /// log proving the pin sequence runs in order on the calling thread.
    struct FakeAffinity {
        tid: i64,
        set_result: bool,
        mask_after_set: Vec<usize>,
        cpu: i64,
        migrations: RefCell<Vec<i64>>, // popped front-first per call
        calls: RefCell<Vec<String>>,
    }

    impl FakeAffinity {
        fn new(tid: i64, cpu: i64) -> FakeAffinity {
            FakeAffinity {
                tid,
                set_result: true,
                mask_after_set: vec![cpu as usize],
                cpu,
                migrations: RefCell::new(vec![0, 0, 0, 0]),
                calls: RefCell::new(vec![]),
            }
        }
    }

    impl AffinityOps for FakeAffinity {
        fn native_tid(&self) -> i64 {
            self.calls.borrow_mut().push("tid".into());
            self.tid
        }
        fn set_affinity(&self, cpu: usize) -> bool {
            self.calls.borrow_mut().push(format!("set({cpu})"));
            self.set_result
        }
        fn allowed_cpus(&self) -> Vec<usize> {
            self.calls.borrow_mut().push("mask".into());
            self.mask_after_set.clone()
        }
        fn current_cpu(&self) -> i64 {
            self.calls.borrow_mut().push("getcpu".into());
            self.cpu
        }
        fn migrations(&self) -> i64 {
            self.calls.borrow_mut().push("migrations".into());
            let mut m = self.migrations.borrow_mut();
            if m.len() > 1 {
                m.remove(0)
            } else {
                *m.first().unwrap_or(&-1)
            }
        }
        fn procfs_path(&self) -> String {
            format!("/proc/self/task/{}/sched", self.tid)
        }
    }

    fn pinned_report(role: &'static str, tid: i64, cpu: i64) -> WorkerReport {
        let fake = FakeAffinity::new(tid, cpu);
        let mut r = establish_on_worker(&fake, role, Some(cpu as usize));
        r.migrations_at_measurement_start = 0;
        r.migrations_at_measurement_end = 0;
        r.observed_cpu_at_end = cpu;
        r.allowed_cpus_at_end = vec![cpu as usize];
        r.captured_by_tid = tid;
        r
    }

    #[test]
    fn establish_pins_verifies_and_samples_in_order_on_the_calling_thread() {
        let fake = FakeAffinity::new(101, 2);
        let r = establish_on_worker(&fake, "producer", Some(2));
        assert!(r.pinned);
        assert!(r.affinity_set_succeeded && r.affinity_mask_verified && r.cpu_after_pin_verified);
        assert_eq!(r.allowed_cpus_after_pin, vec![2]);
        assert_eq!(r.native_tid, 101);
        assert_eq!(r.procfs_path, "/proc/self/task/101/sched");
        // migrations sampled BEFORE the pin, then set, then mask+cpu verify,
        // then migrations again — the whole flow on the calling thread
        let calls = fake.calls.borrow();
        let m1 = calls.iter().position(|c| c == "migrations").unwrap();
        let set = calls.iter().position(|c| c == "set(2)").unwrap();
        let mask = calls.iter().position(|c| c == "mask").unwrap();
        let cpu = calls.iter().position(|c| c == "getcpu").unwrap();
        let m2 = calls.iter().rposition(|c| c == "migrations").unwrap();
        assert!(m1 < set && set < mask && mask < cpu && cpu < m2, "{calls:?}");
    }

    #[test]
    fn distinct_tids_and_paths_pass_and_duplicates_are_rejected() {
        let p = pinned_report("producer", 101, 2);
        let c = pinned_report("consumer", 102, 3);
        assert_ne!(p.native_tid, c.native_tid);
        assert_ne!(p.procfs_path, c.procfs_path);
        assert_eq!(validate_reports(&p, &c), Vec::<String>::new());

        let dup = pinned_report("consumer", 101, 3);
        let violations = validate_reports(&p, &dup);
        assert!(violations.iter().any(|v| v.contains("duplicate worker TID 101")), "{violations:?}");
        assert!(violations.iter().any(|v| v.contains("same procfs path")), "{violations:?}");
    }

    #[test]
    fn producer_and_consumer_masks_must_be_exactly_their_intended_cpu() {
        let p = pinned_report("producer", 101, 2);
        assert_eq!(p.allowed_cpus_after_pin, vec![2]);
        let c = pinned_report("consumer", 102, 3);
        assert_eq!(c.allowed_cpus_after_pin, vec![3]);
        // a two-CPU mask (process-level taskset containment) is NOT pinned
        let fake = FakeAffinity {
            mask_after_set: vec![2, 3],
            ..FakeAffinity::new(103, 2)
        };
        let r = establish_on_worker(&fake, "producer", Some(2));
        assert!(!r.affinity_mask_verified);
        assert!(!r.pinned, "a containment mask must never count as worker pinning");
    }

    #[test]
    fn syscall_success_without_mask_verification_is_not_pinned() {
        let fake = FakeAffinity {
            mask_after_set: vec![5],
            ..FakeAffinity::new(104, 2)
        };
        let r = establish_on_worker(&fake, "producer", Some(2));
        assert!(r.affinity_set_succeeded, "the syscall itself reported success");
        assert!(!r.pinned);
        let ok = pinned_report("consumer", 105, 3);
        let violations = validate_reports(&r, &ok);
        assert!(violations.iter().any(|v| v.contains("producer: not pinned")), "{violations:?}");
    }

    #[test]
    fn end_state_not_captured_by_the_owning_worker_is_rejected() {
        let p = pinned_report("producer", 101, 2);
        let mut c = pinned_report("consumer", 102, 3);
        c.captured_by_tid = 999; // parent (or any other thread) wrote the end state
        let violations = validate_reports(&p, &c);
        assert!(
            violations.iter().any(|v| v.contains("not captured by its own worker")),
            "{violations:?}"
        );
    }

    #[test]
    fn prepin_migrations_are_reported_but_only_the_measurement_window_gates() {
        let mut p = pinned_report("producer", 101, 2);
        // noisy startup: 5 migrations before the pin, 2 more before measure
        p.migrations_before_pin = 0;
        p.migrations_after_pin = 5;
        p.migrations_at_measurement_start = 7;
        p.migrations_at_measurement_end = 7;
        let c = pinned_report("consumer", 102, 3);
        assert_eq!(p.migrations_during_measurement(), 0);
        assert_eq!(validate_reports(&p, &c), Vec::<String>::new());
    }

    #[test]
    fn one_real_measurement_migration_fails() {
        let mut p = pinned_report("producer", 101, 2);
        p.migrations_at_measurement_start = 7;
        p.migrations_at_measurement_end = 8;
        let c = pinned_report("consumer", 102, 3);
        let violations = validate_reports(&p, &c);
        assert!(
            violations.iter().any(|v| v.contains("producer: 1 migration(s) during measurement")),
            "{violations:?}"
        );
    }

    #[test]
    fn decreasing_migration_counter_is_an_infrastructure_error() {
        let mut p = pinned_report("producer", 101, 2);
        p.migrations_after_pin = 5;
        p.migrations_at_measurement_start = 3; // kernel counters never decrease
        p.migrations_at_measurement_end = 3;
        let c = pinned_report("consumer", 102, 3);
        let violations = validate_reports(&p, &c);
        assert!(
            violations.iter().any(|v| v.contains("migration counter decreased")),
            "{violations:?}"
        );
    }

    #[test]
    fn consumer_finishing_on_the_wrong_cpu_fails() {
        let p = pinned_report("producer", 101, 2);
        let mut c = pinned_report("consumer", 102, 3);
        c.observed_cpu_at_end = 2;
        let violations = validate_reports(&p, &c);
        assert!(
            violations.iter().any(|v| v.contains("consumer: intended CPU 3 but finished on 2")),
            "{violations:?}"
        );
    }

    #[test]
    fn end_mask_widened_back_to_containment_fails() {
        let p = pinned_report("producer", 101, 2);
        let mut c = pinned_report("consumer", 102, 3);
        c.allowed_cpus_at_end = vec![2, 3];
        let violations = validate_reports(&p, &c);
        assert!(
            violations.iter().any(|v| v.contains("end affinity mask [2, 3] is not exactly [3]")),
            "{violations:?}"
        );
    }

    // Linux-only integration: two real threads pin themselves to two CPUs
    // taken from THIS process's allowed mask (never hardcoded numbering)
    // and each report carries its own distinct TID and procfs path.
    #[cfg(target_os = "linux")]
    #[test]
    fn real_threads_pin_themselves_with_distinct_tids() {
        let ops = LinuxAffinity;
        let allowed = ops.allowed_cpus();
        if allowed.len() < 2 {
            eprintln!("skipping: fewer than 2 allowed CPUs");
            return;
        }
        let (cpu_a, cpu_b) = (allowed[0], allowed[1]);
        let a = std::thread::spawn(move || {
            let ops = LinuxAffinity;
            establish_on_worker(&ops, "producer", Some(cpu_a))
        });
        let b = std::thread::spawn(move || {
            let ops = LinuxAffinity;
            establish_on_worker(&ops, "consumer", Some(cpu_b))
        });
        let ra = a.join().unwrap();
        let rb = b.join().unwrap();
        assert!(ra.pinned, "{ra:?}");
        assert!(rb.pinned, "{rb:?}");
        assert_eq!(ra.allowed_cpus_after_pin, vec![cpu_a]);
        assert_eq!(rb.allowed_cpus_after_pin, vec![cpu_b]);
        assert_ne!(ra.native_tid, rb.native_tid, "workers must have distinct Linux TIDs");
        assert_ne!(ra.procfs_path, rb.procfs_path);
        let main_tid = ops.native_tid();
        assert_ne!(ra.native_tid, main_tid, "worker TID must not be the parent's");
        assert_ne!(rb.native_tid, main_tid, "worker TID must not be the parent's");
    }
}
