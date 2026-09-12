//! Companion code for the Performance Lab "Locks, Spin Waiting and
//! Parking" (kzybala.pl/lab/locks-spin-parking/). See `rust.md` next to
//! this crate for the full explanation.
//!
//! Five wait-strategy variants over a shared-counter workload, mirroring
//! `code/java/`'s kernels method-for-method. See
//! `code/fixtures/locks-spin-parking-fixtures.json` for the shared
//! dataset constants both languages assert against identically.

pub mod fixtures {
    pub const WORKER_COUNT: usize = 4;
    pub const OPS_PER_WORKER: usize = 2_000;
    pub const LONG_CS_BUSY_ITERATIONS: u32 = 200;
    pub const SPIN_LIMIT: u32 = 100;

    /// Deterministic, side-effect-free busy work used inside the "long
    /// critical section" variant — its cost depends only on `iterations`.
    pub fn busy_work(iterations: u32) -> u64 {
        let mut acc: u64 = 0;
        for i in 0..iterations as u64 {
            acc = acc.wrapping_add((i.wrapping_mul(2654435761)) ^ i);
        }
        acc
    }
}

pub struct LockResult {
    pub final_counter: u64,
    pub cas_failures: u64,
    pub park_count: u64,
}

impl LockResult {
    pub fn is_correct(&self, worker_count: usize, ops_per_worker: usize) -> bool {
        self.final_counter == (worker_count * ops_per_worker) as u64
    }
}

pub mod mutex_kernel {
    //! Three variants share one mechanism — a `Mutex`-protected shared
    //! counter — differing only in worker count and critical-section
    //! length. Mirrors the Java `MutexKernel` exactly.

    use super::{fixtures::*, LockResult};
    use std::sync::{Arc, Mutex};
    use std::thread;

    fn run(worker_count: usize, ops_per_worker: usize, busy_iterations: u32) -> LockResult {
        let counter = Arc::new(Mutex::new(0u64));
        let sink = Arc::new(Mutex::new(0u64)); // absorbs busy_work's result so it cannot be optimized away

        let handles: Vec<_> = (0..worker_count)
            .map(|_| {
                let counter = Arc::clone(&counter);
                let sink = Arc::clone(&sink);
                thread::spawn(move || {
                    let mut local_sink: u64 = 0;
                    for _ in 0..ops_per_worker {
                        let mut c = counter.lock().unwrap();
                        if busy_iterations > 0 {
                            local_sink = local_sink.wrapping_add(busy_work(busy_iterations));
                        }
                        *c += 1;
                    }
                    *sink.lock().unwrap() += local_sink;
                })
            })
            .collect();
        for h in handles {
            h.join().unwrap();
        }

        let final_counter = *counter.lock().unwrap();
        LockResult {
            final_counter,
            cas_failures: 0,
            park_count: 0,
        }
    }

    pub fn uncontended() -> LockResult {
        run(1, OPS_PER_WORKER, 0)
    }

    pub fn short_contended(worker_count: usize, ops_per_worker: usize) -> LockResult {
        run(worker_count, ops_per_worker, 0)
    }

    pub fn long_critical_section(
        worker_count: usize,
        ops_per_worker: usize,
        busy_iterations: u32,
    ) -> LockResult {
        run(worker_count, ops_per_worker, busy_iterations)
    }
}

pub mod cas_loop {
    //! No lock at all: every worker retries a CAS on a shared `AtomicU64`
    //! until it succeeds.

    use super::LockResult;
    use std::sync::atomic::{AtomicU64, Ordering};
    use std::sync::Arc;
    use std::thread;

    pub fn run(worker_count: usize, ops_per_worker: usize) -> LockResult {
        let counter = Arc::new(AtomicU64::new(0));

        let handles: Vec<_> = (0..worker_count)
            .map(|_| {
                let counter = Arc::clone(&counter);
                thread::spawn(move || {
                    let mut local_failures: u64 = 0;
                    for _ in 0..ops_per_worker {
                        loop {
                            let cur = counter.load(Ordering::Acquire);
                            if counter
                                .compare_exchange(cur, cur + 1, Ordering::AcqRel, Ordering::Relaxed)
                                .is_ok()
                            {
                                break;
                            }
                            local_failures += 1;
                        }
                    }
                    local_failures
                })
            })
            .collect();

        let mut total_failures = 0u64;
        for h in handles {
            total_failures += h.join().unwrap();
        }

        LockResult {
            final_counter: counter.load(Ordering::Acquire),
            cas_failures: total_failures,
            park_count: 0,
        }
    }
}

pub mod spin_then_park {
    //! A hand-rolled adaptive lock: spin briefly, then fall back to
    //! `std::thread::park`/`Thread::unpark` — mirrors the Java
    //! `SpinThenParkKernel`. The waiter queue is a plain
    //! `Mutex<VecDeque<Thread>>` (Rust's std has no lock-free queue);
    //! `unlock()` may occasionally unpark a thread that already took the
    //! lock via the double-check path, which is harmless — a spurious
    //! `unpark` permit is simply consumed by that thread's next `park()`
    //! call without blocking, the same tolerance every park/unpark design
    //! must have since spurious wakeups are part of the API's contract.

    use super::LockResult;
    use std::collections::VecDeque;
    use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
    use std::sync::{Arc, Mutex};
    use std::thread::{self, Thread};

    struct SpinParkLock {
        locked: AtomicBool,
        waiters: Mutex<VecDeque<Thread>>,
        spin_limit: u32,
        park_count: AtomicU64,
    }

    impl SpinParkLock {
        fn new(spin_limit: u32) -> Self {
            SpinParkLock {
                locked: AtomicBool::new(false),
                waiters: Mutex::new(VecDeque::new()),
                spin_limit,
                park_count: AtomicU64::new(0),
            }
        }

        fn lock(&self) {
            let mut spins = 0u32;
            loop {
                if self
                    .locked
                    .compare_exchange(false, true, Ordering::AcqRel, Ordering::Relaxed)
                    .is_ok()
                {
                    return;
                }
                if spins < self.spin_limit {
                    spins += 1;
                    std::hint::spin_loop();
                    continue;
                }
                self.waiters.lock().unwrap().push_back(thread::current());
                // Double-check after registering (the standard missed-
                // wakeup guard): if the lock freed up between the last
                // failed CAS and joining the waiter queue, take it now.
                if self
                    .locked
                    .compare_exchange(false, true, Ordering::AcqRel, Ordering::Relaxed)
                    .is_ok()
                {
                    return;
                }
                thread::park();
                self.park_count.fetch_add(1, Ordering::Relaxed);
                spins = 0; // re-enter the spin phase after waking
            }
        }

        fn unlock(&self) {
            self.locked.store(false, Ordering::Release);
            if let Some(t) = self.waiters.lock().unwrap().pop_front() {
                t.unpark();
            }
        }
    }

    pub fn run(worker_count: usize, ops_per_worker: usize, spin_limit: u32) -> LockResult {
        let lock = Arc::new(SpinParkLock::new(spin_limit));
        let counter = Arc::new(Mutex::new(0u64));

        let handles: Vec<_> = (0..worker_count)
            .map(|_| {
                let lock = Arc::clone(&lock);
                let counter = Arc::clone(&counter);
                thread::spawn(move || {
                    for _ in 0..ops_per_worker {
                        lock.lock();
                        *counter.lock().unwrap() += 1;
                        lock.unlock();
                    }
                })
            })
            .collect();
        for h in handles {
            h.join().unwrap();
        }

        let final_counter = *counter.lock().unwrap();
        LockResult {
            final_counter,
            cas_failures: 0,
            park_count: lock.park_count.load(Ordering::Relaxed),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn uncontended_mutex_reaches_exact_count() {
        let r = mutex_kernel::uncontended();
        assert!(r.is_correct(1, fixtures::OPS_PER_WORKER));
    }

    #[test]
    fn short_contended_mutex_reaches_exact_count() {
        let r = mutex_kernel::short_contended(fixtures::WORKER_COUNT, fixtures::OPS_PER_WORKER);
        assert!(r.is_correct(fixtures::WORKER_COUNT, fixtures::OPS_PER_WORKER));
    }

    #[test]
    fn long_critical_section_reaches_exact_count() {
        let r = mutex_kernel::long_critical_section(
            fixtures::WORKER_COUNT,
            fixtures::OPS_PER_WORKER,
            fixtures::LONG_CS_BUSY_ITERATIONS,
        );
        assert!(r.is_correct(fixtures::WORKER_COUNT, fixtures::OPS_PER_WORKER));
    }

    #[test]
    fn cas_loop_reaches_exact_count() {
        let r = cas_loop::run(fixtures::WORKER_COUNT, fixtures::OPS_PER_WORKER);
        assert!(r.is_correct(fixtures::WORKER_COUNT, fixtures::OPS_PER_WORKER));
    }

    #[test]
    fn spin_then_park_reaches_exact_count() {
        let r = spin_then_park::run(
            fixtures::WORKER_COUNT,
            fixtures::OPS_PER_WORKER,
            fixtures::SPIN_LIMIT,
        );
        assert!(r.is_correct(fixtures::WORKER_COUNT, fixtures::OPS_PER_WORKER));
    }

    /// With spin_limit=0 every acquisition that finds the lock held
    /// tries to park immediately — correctness must still hold exactly.
    /// Whether a park actually happens (versus the double-check re-CAS
    /// winning first) is a genuine timing race, not something this test
    /// can force deterministically, so the park count is reported, not
    /// asserted — the same "observed, not gated" treatment this lab's
    /// other genuinely racy outcomes get elsewhere in this repository.
    #[test]
    fn spin_then_park_with_zero_spin_limit_still_reaches_exact_count() {
        let r = spin_then_park::run(fixtures::WORKER_COUNT, fixtures::OPS_PER_WORKER, 0);
        assert!(r.is_correct(fixtures::WORKER_COUNT, fixtures::OPS_PER_WORKER));
        println!(
            "spin_then_park with spin_limit=0: park_count={} (not asserted — timing-dependent)",
            r.park_count
        );
    }
}
