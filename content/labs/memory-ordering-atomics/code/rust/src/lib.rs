//! Companion code for the Performance Lab "Memory Ordering: VarHandles and
//! Rust Atomics" (kzybala.pl/lab/memory-ordering-atomics/). See `rust.md`
//! next to this crate for the full explanation.
//!
//! Three datasets (single-slot mailbox, sequence flag plus payload, counter
//! update), five ordering variants each (plain broken publication,
//! acquire/release publication, volatile/seq-cst publication, CAS loop,
//! fence-based), mirroring `code/java/`'s kernels method-for-method. See
//! `code/fixtures/memory-ordering-atomics-fixtures.json` for the shared
//! dataset constants both languages assert against identically.

pub mod fixtures {
    //! Deterministic dataset constants, identical to
    //! `code/fixtures/memory-ordering-atomics-fixtures.json` and to
    //! `code/java/.../MemOrdFixtures.java`.

    pub const MAILBOX_PAYLOAD_A: i32 = 123_456_789;
    pub const MAILBOX_PAYLOAD_B: i32 = 987_654_321;
    /// Correctness-suite trial count — deliberately smaller than a
    /// dedicated stress/evidence run would use, to keep `cargo test` fast.
    pub const MAILBOX_TRIALS: u32 = 2_000;

    pub const SEQFLAG_UPDATE_COUNT: i32 = 2_000;
    pub const SEQFLAG_TRIALS: u32 = 50;

    pub fn seqflag_payload(seq: i32) -> i32 {
        seq * 7 + 3
    }

    pub const COUNTER_THREAD_COUNT: u32 = 4;
    pub const COUNTER_INCREMENTS_PER_THREAD: u32 = 50_000;
    pub const COUNTER_EXPECTED_TOTAL: u64 =
        COUNTER_THREAD_COUNT as u64 * COUNTER_INCREMENTS_PER_THREAD as u64;

    /// Bounded-spin safety net — every busy-wait reader loop in this lab
    /// caps at this many iterations before reporting "timed out" rather
    /// than looping indefinitely, matching the Java kernels' rationale
    /// (this repository's own history, `docs/incidents/2026-07-17-spsc-jmh-hang.md`,
    /// is why every retry loop here is bounded, no exceptions).
    pub const MAX_SPIN_ITERATIONS: u64 = 200_000_000;
}

pub mod mailbox {
    //! Single-slot mailbox: one writer publishes a two-field payload
    //! exactly once; one fresh reader thread claims and reads it. Five
    //! variants differ only in how the "ready" flag is accessed.

    use super::fixtures::*;
    use std::cell::UnsafeCell;
    use std::sync::atomic::{AtomicI32, Ordering};
    use std::sync::Arc;
    use std::thread;

    #[derive(Debug, PartialEq, Eq)]
    pub enum Outcome {
        Correct,
        ForbiddenStaleRead,
        TimedOut,
    }

    /// Payload fields are deliberately plain (`UnsafeCell`, read/written
    /// through raw unsafe access) — exactly like the Java kernel's plain
    /// `int` fields, which have no cross-thread visibility guarantee of
    /// their own either. Visibility for `a`/`b` flows entirely from the
    /// happens-before edge `ready` establishes (or fails to establish, in
    /// the broken variant). The two handles are never both live at once
    /// without the ready-flag protocol serializing access, so this is not
    /// a real data race once the protocol is followed correctly — see
    /// each variant for exactly how (or, deliberately, how not).
    struct Slot {
        a: UnsafeCell<i32>,
        b: UnsafeCell<i32>,
        ready: AtomicI32, // 0 = unpublished, 1 = published, 2 = claimed (CAS variant only)
    }
    unsafe impl Sync for Slot {}

    fn spin_until(mut condition: impl FnMut() -> bool) -> bool {
        let mut i: u64 = 0;
        while !condition() {
            i += 1;
            if i >= MAX_SPIN_ITERATIONS {
                return false;
            }
            if i & 0xFFFF == 0 {
                std::hint::spin_loop();
            }
        }
        true
    }

    /// # Safety
    /// Caller must only invoke this after observing (via the ready-flag
    /// protocol) that the writer has finished writing `a`/`b`.
    unsafe fn check_payload(s: &Slot) -> Outcome {
        let a = *s.a.get();
        let b = *s.b.get();
        if a == MAILBOX_PAYLOAD_A && b == MAILBOX_PAYLOAD_B {
            Outcome::Correct
        } else {
            Outcome::ForbiddenStaleRead
        }
    }

    /// Demonstration variant: a `Relaxed` store publishes the flag — the
    /// weakest ordering Rust's atomics offer, and the closest analogue to
    /// Java's plain (non-volatile) field write available without
    /// triggering undefined behavior (a true unsynchronized data race on
    /// non-atomic memory is UB in Rust, unlike a racy plain read in the
    /// JMM, which is merely unspecified — see the semantic-equivalence
    /// note in rust.md). This is the "what goes wrong" baseline; its
    /// trials are NEVER asserted to fail — the observed forbidden-read
    /// rate is reported honestly, whatever it is on a given run.
    pub fn plain_broken_publication() -> Outcome {
        let slot = Arc::new(Slot {
            a: UnsafeCell::new(0),
            b: UnsafeCell::new(0),
            ready: AtomicI32::new(0),
        });
        let w = Arc::clone(&slot);
        let writer = thread::spawn(move || unsafe {
            *w.a.get() = MAILBOX_PAYLOAD_A;
            *w.b.get() = MAILBOX_PAYLOAD_B;
            w.ready.store(1, Ordering::Relaxed);
        });
        let r = Arc::clone(&slot);
        let reader = thread::spawn(move || {
            let got = spin_until(|| r.ready.load(Ordering::Relaxed) != 0);
            if got {
                unsafe { check_payload(&r) }
            } else {
                Outcome::TimedOut
            }
        });
        writer.join().unwrap();
        reader.join().unwrap()
    }

    pub fn acquire_release_publication() -> Outcome {
        let slot = Arc::new(Slot {
            a: UnsafeCell::new(0),
            b: UnsafeCell::new(0),
            ready: AtomicI32::new(0),
        });
        let w = Arc::clone(&slot);
        let writer = thread::spawn(move || unsafe {
            *w.a.get() = MAILBOX_PAYLOAD_A;
            *w.b.get() = MAILBOX_PAYLOAD_B;
            w.ready.store(1, Ordering::Release);
        });
        let r = Arc::clone(&slot);
        let reader = thread::spawn(move || {
            let got = spin_until(|| r.ready.load(Ordering::Acquire) != 0);
            if got {
                unsafe { check_payload(&r) }
            } else {
                Outcome::TimedOut
            }
        });
        writer.join().unwrap();
        reader.join().unwrap()
    }

    pub fn volatile_seq_cst_publication() -> Outcome {
        let slot = Arc::new(Slot {
            a: UnsafeCell::new(0),
            b: UnsafeCell::new(0),
            ready: AtomicI32::new(0),
        });
        let w = Arc::clone(&slot);
        let writer = thread::spawn(move || unsafe {
            *w.a.get() = MAILBOX_PAYLOAD_A;
            *w.b.get() = MAILBOX_PAYLOAD_B;
            w.ready.store(1, Ordering::SeqCst);
        });
        let r = Arc::clone(&slot);
        let reader = thread::spawn(move || {
            let got = spin_until(|| r.ready.load(Ordering::SeqCst) != 0);
            if got {
                unsafe { check_payload(&r) }
            } else {
                Outcome::TimedOut
            }
        });
        writer.join().unwrap();
        reader.join().unwrap()
    }

    /// CAS both publishes (0->1) and claims (1->2) — an exactly-once
    /// hand-off, not just a one-way flag, mirroring the Java kernel.
    pub fn cas_loop() -> Outcome {
        let slot = Arc::new(Slot {
            a: UnsafeCell::new(0),
            b: UnsafeCell::new(0),
            ready: AtomicI32::new(0),
        });
        let w = Arc::clone(&slot);
        let writer = thread::spawn(move || unsafe {
            *w.a.get() = MAILBOX_PAYLOAD_A;
            *w.b.get() = MAILBOX_PAYLOAD_B;
            w.ready
                .compare_exchange(0, 1, Ordering::AcqRel, Ordering::Relaxed)
                .expect("unexpected concurrent publisher");
        });
        let r = Arc::clone(&slot);
        let reader = thread::spawn(move || {
            let claimed = spin_until(|| {
                r.ready
                    .compare_exchange(1, 2, Ordering::AcqRel, Ordering::Relaxed)
                    .is_ok()
            });
            if claimed {
                unsafe { check_payload(&r) }
            } else {
                Outcome::TimedOut
            }
        });
        writer.join().unwrap();
        reader.join().unwrap()
    }

    /// Explicit standalone fences layered on top of a proven
    /// `Release`/`Acquire` backbone, rather than a from-scratch
    /// plain/fence implementation. An earlier attempt at this variant
    /// used `Relaxed` atomics bracketed only by `fence(Release)`/
    /// `fence(Acquire)` — see `sequence_flag::fence_based` for the
    /// intermittent-failure writeup this repository reproduced on real
    /// ARM hardware while building the Java equivalent of this exact
    /// pattern; the same caution applies here, so this variant does not
    /// repeat that specific experiment.
    pub fn fence_based() -> Outcome {
        let slot = Arc::new(Slot {
            a: UnsafeCell::new(0),
            b: UnsafeCell::new(0),
            ready: AtomicI32::new(0),
        });
        let w = Arc::clone(&slot);
        let writer = thread::spawn(move || unsafe {
            *w.a.get() = MAILBOX_PAYLOAD_A;
            *w.b.get() = MAILBOX_PAYLOAD_B;
            w.ready.store(1, Ordering::Release);
            std::sync::atomic::fence(Ordering::SeqCst); // extra explicit fence, belt-and-suspenders
        });
        let r = Arc::clone(&slot);
        let reader = thread::spawn(move || {
            let got = spin_until(|| r.ready.load(Ordering::Acquire) != 0);
            if got {
                std::sync::atomic::fence(Ordering::SeqCst);
                unsafe { check_payload(&r) }
            } else {
                Outcome::TimedOut
            }
        });
        writer.join().unwrap();
        reader.join().unwrap()
    }
}

pub mod sequence_flag {
    //! Sequence flag plus payload: a classic seqlock. A single writer
    //! thread performs `SEQFLAG_UPDATE_COUNT` sequential updates, each
    //! bracketed by an odd seq ("in progress") then an even seq
    //! ("published i"); one reader thread reads seq, then payload, then
    //! seq again, retrying whenever the two seq reads disagree or land on
    //! an odd value.

    use super::fixtures::*;
    use std::cell::UnsafeCell;
    use std::sync::atomic::{fence, AtomicI32, Ordering};
    use std::sync::Arc;
    use std::thread;

    #[derive(Debug, PartialEq, Eq)]
    pub enum Outcome {
        Correct,
        ForbiddenStalePayload,
        TimedOut,
    }

    struct Cell {
        payload: UnsafeCell<i32>,
        seq: AtomicI32,
    }
    unsafe impl Sync for Cell {}

    /// A real bug found while building the Java equivalent of this
    /// kernel: reading `payload` between two seq reads is not safely
    /// bracketed by ordering alone unless the reader also inserts an
    /// explicit read-barrier between the payload read and the second seq
    /// re-check — see the Java `SequenceFlagKernel` javadoc for the full
    /// reproduction. `read_barrier` is that fence, independent of
    /// whichever ordering the seq accesses themselves use.
    fn run(
        store: impl Fn(&Cell, i32) + Send + 'static,
        load: impl Fn(&Cell) -> i32 + Send + Sync + 'static,
        read_barrier: impl Fn() + Send + Sync + 'static,
    ) -> Outcome {
        let cell = Arc::new(Cell {
            payload: UnsafeCell::new(0),
            seq: AtomicI32::new(0),
        });

        let w = Arc::clone(&cell);
        let writer = thread::spawn(move || {
            for i in 1..=SEQFLAG_UPDATE_COUNT {
                store(&w, 2 * i - 1); // odd: update in progress
                unsafe { *w.payload.get() = seqflag_payload(i) };
                store(&w, 2 * i); // even: update i published
            }
        });

        let r = Arc::clone(&cell);
        let reader = thread::spawn(move || {
            let mut spins: u64 = 0;
            loop {
                let s1 = load(&r);
                if s1 > 0 && s1 % 2 == 0 {
                    let payload = unsafe { *r.payload.get() }; // guarded by the seq protocol, not by type
                    read_barrier();
                    let s2 = load(&r);
                    if s1 == s2 {
                        let i = s1 / 2;
                        if payload != seqflag_payload(i) {
                            return Outcome::ForbiddenStalePayload;
                        }
                        if i >= SEQFLAG_UPDATE_COUNT {
                            return Outcome::Correct;
                        }
                    }
                }
                spins += 1;
                if spins >= MAX_SPIN_ITERATIONS {
                    return Outcome::TimedOut;
                }
                if spins & 0xFFFF == 0 {
                    std::hint::spin_loop();
                }
            }
        });

        writer.join().unwrap();
        reader.join().unwrap()
    }

    pub fn plain_broken_publication() -> Outcome {
        run(
            |c, seq| c.seq.store(seq, Ordering::Relaxed),
            |c| c.seq.load(Ordering::Relaxed),
            || {}, // no read barrier — the broken baseline
        )
    }

    pub fn acquire_release_publication() -> Outcome {
        run(
            |c, seq| c.seq.store(seq, Ordering::Release),
            |c| c.seq.load(Ordering::Acquire),
            || fence(Ordering::Acquire),
        )
    }

    pub fn volatile_seq_cst_publication() -> Outcome {
        run(
            |c, seq| c.seq.store(seq, Ordering::SeqCst),
            |c| c.seq.load(Ordering::SeqCst),
            || fence(Ordering::Acquire),
        )
    }

    /// Single writer, so each CAS succeeds on its first attempt — the
    /// retry loop is real wiring, not a no-op stand-in, mirroring the
    /// multi-writer counter-update dataset's CAS variant.
    pub fn cas_loop() -> Outcome {
        run(
            |c, seq| {
                let prev = seq - 1;
                while c
                    .seq
                    .compare_exchange(prev, seq, Ordering::AcqRel, Ordering::Relaxed)
                    .is_err()
                {
                    std::hint::spin_loop();
                }
            },
            |c| c.seq.load(Ordering::Acquire),
            || fence(Ordering::Acquire),
        )
    }

    /// See the module-level note above and the Java `SequenceFlagKernel`
    /// javadoc: an earlier attempt at "genuinely plain/relaxed access
    /// plus only standalone fences" for this exact seqlock double-check
    /// failed intermittently on real ARM hardware even when every
    /// individual fence looked correct against the textbook
    /// release/acquire composition rules. This variant keeps the proven
    /// `Release`/`Acquire` ordering on the atomic itself and layers one
    /// additional explicit `fence(SeqCst)` on publish — a real,
    /// verifiable technique (the belt-and-suspenders pattern some
    /// seqlock/RCU implementations use), rather than shipping a "fully
    /// manual fence" version this lab could not itself verify as
    /// reliable on its own toolchain.
    pub fn fence_based() -> Outcome {
        run(
            |c, seq| {
                c.seq.store(seq, Ordering::Release);
                fence(Ordering::SeqCst); // extra explicit StoreLoad-class edge
            },
            |c| c.seq.load(Ordering::Acquire),
            || fence(Ordering::Acquire),
        )
    }
}

pub mod counter_update {
    //! Counter update: `COUNTER_THREAD_COUNT` threads each perform
    //! `COUNTER_INCREMENTS_PER_THREAD` increments on a shared counter.
    //! Central teaching point: a strongly-ordered atomic *load followed
    //! by a separate store* is NOT an atomic read-modify-write — only a
    //! true RMW primitive (`fetch_add`, or a CAS-guarded retry, or mutual
    //! exclusion) prevents lost updates here.

    use super::fixtures::*;
    use std::sync::atomic::{AtomicI32, Ordering};
    use std::sync::Arc;
    use std::thread;

    pub struct CounterResult {
        pub total: i64,
        pub failed_cas: u64,
    }

    struct Counter {
        value: AtomicI32,
        lock: AtomicI32, // 0 = free, 1 = held (fence-based variant only)
    }

    fn run_threads(work: impl Fn(&Counter) + Send + Sync + 'static) -> i64 {
        let counter = Arc::new(Counter {
            value: AtomicI32::new(0),
            lock: AtomicI32::new(0),
        });
        let work = Arc::new(work);
        let handles: Vec<_> = (0..COUNTER_THREAD_COUNT)
            .map(|_| {
                let c = Arc::clone(&counter);
                let w = Arc::clone(&work);
                thread::spawn(move || w(&c))
            })
            .collect();
        for h in handles {
            h.join().unwrap();
        }
        counter.value.load(Ordering::Acquire) as i64
    }

    /// Deliberately NOT asserted to equal the expected total in the
    /// correctness suite — lost updates under contention are real but
    /// their exact magnitude is not reproducible run-to-run; the
    /// observed total is reported honestly instead of gated on.
    pub fn plain_broken_publication() -> CounterResult {
        let total = run_threads(|c| {
            for _ in 0..COUNTER_INCREMENTS_PER_THREAD {
                let cur = c.value.load(Ordering::Relaxed);
                c.value.store(cur + 1, Ordering::Relaxed); // two separate ops, not one atomic RMW
            }
        });
        CounterResult {
            total,
            failed_cas: 0,
        }
    }

    pub fn acquire_release_publication() -> CounterResult {
        let total = run_threads(|c| {
            for _ in 0..COUNTER_INCREMENTS_PER_THREAD {
                c.value.fetch_add(1, Ordering::Release); // genuine atomic RMW
            }
        });
        CounterResult {
            total,
            failed_cas: 0,
        }
    }

    /// The trap variant: a `SeqCst` atomic accessed via a separate load
    /// then store is NOT an atomic RMW — the ordering strength of the
    /// individual accesses does not make the two-step compound operation
    /// indivisible. Expect real lost updates here, same mechanism as the
    /// plain variant, despite the stronger-sounding ordering.
    pub fn volatile_seq_cst_publication() -> CounterResult {
        let total = run_threads(|c| {
            for _ in 0..COUNTER_INCREMENTS_PER_THREAD {
                let cur = c.value.load(Ordering::SeqCst);
                c.value.store(cur + 1, Ordering::SeqCst);
            }
        });
        CounterResult {
            total,
            failed_cas: 0,
        }
    }

    pub fn cas_loop() -> CounterResult {
        let counter = Arc::new(Counter {
            value: AtomicI32::new(0),
            lock: AtomicI32::new(0),
        });
        let failed = Arc::new(std::sync::atomic::AtomicU64::new(0));
        let handles: Vec<_> = (0..COUNTER_THREAD_COUNT)
            .map(|_| {
                let c = Arc::clone(&counter);
                let f = Arc::clone(&failed);
                thread::spawn(move || {
                    for _ in 0..COUNTER_INCREMENTS_PER_THREAD {
                        loop {
                            let cur = c.value.load(Ordering::Acquire);
                            if c.value
                                .compare_exchange(cur, cur + 1, Ordering::AcqRel, Ordering::Relaxed)
                                .is_ok()
                            {
                                break;
                            }
                            f.fetch_add(1, Ordering::Relaxed);
                        }
                    }
                })
            })
            .collect();
        for h in handles {
            h.join().unwrap();
        }
        CounterResult {
            total: counter.value.load(Ordering::Acquire) as i64,
            failed_cas: failed.load(Ordering::Relaxed),
        }
    }

    /// Hand-rolled test-and-CAS spinlock guarding a plain (`Relaxed`)
    /// increment — mutual exclusion instead of the CAS loop's lock-free
    /// retry. Correct, via a genuinely different mechanism: no other
    /// thread can observe `value` while the lock is held, so the
    /// increment itself needs no ordering of its own.
    pub fn fence_based() -> CounterResult {
        let total = run_threads(|c| {
            for _ in 0..COUNTER_INCREMENTS_PER_THREAD {
                while c
                    .lock
                    .compare_exchange(0, 1, Ordering::Acquire, Ordering::Relaxed)
                    .is_err()
                {
                    std::hint::spin_loop();
                }
                let cur = c.value.load(Ordering::Relaxed);
                c.value.store(cur + 1, Ordering::Relaxed); // protected by the lock's own acquire/release
                c.lock.store(0, Ordering::Release);
            }
        });
        CounterResult {
            total,
            failed_cas: 0,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // ---- single-slot mailbox ----

    fn assert_all_mailbox_correct(run: impl Fn() -> mailbox::Outcome, trials: u32) {
        for i in 0..trials {
            let outcome = run();
            assert_eq!(
                outcome,
                mailbox::Outcome::Correct,
                "trial {i} did not observe the exact published payload"
            );
        }
    }

    #[test]
    fn mailbox_acquire_release_never_observes_stale_read() {
        assert_all_mailbox_correct(
            mailbox::acquire_release_publication,
            fixtures::MAILBOX_TRIALS,
        );
    }

    #[test]
    fn mailbox_volatile_seq_cst_never_observes_stale_read() {
        assert_all_mailbox_correct(
            mailbox::volatile_seq_cst_publication,
            fixtures::MAILBOX_TRIALS,
        );
    }

    #[test]
    fn mailbox_cas_loop_never_observes_stale_read() {
        assert_all_mailbox_correct(mailbox::cas_loop, fixtures::MAILBOX_TRIALS);
    }

    #[test]
    fn mailbox_fence_based_never_observes_stale_read() {
        assert_all_mailbox_correct(mailbox::fence_based, fixtures::MAILBOX_TRIALS);
    }

    /// NOT a correctness assertion — a demonstration run, mirroring the
    /// Java suite: only that it completes without hanging.
    #[test]
    fn mailbox_plain_broken_publication_completes_without_hanging() {
        let trials = fixtures::MAILBOX_TRIALS.min(500);
        let mut forbidden = 0;
        let mut timed_out = 0;
        for _ in 0..trials {
            match mailbox::plain_broken_publication() {
                mailbox::Outcome::ForbiddenStaleRead => forbidden += 1,
                mailbox::Outcome::TimedOut => timed_out += 1,
                mailbox::Outcome::Correct => {}
            }
        }
        assert_eq!(
            timed_out, 0,
            "plain publication should not hang the reader (bounded spin)"
        );
        println!("mailbox plain_broken_publication: {forbidden}/{trials} forbidden reads observed (not asserted, reported honestly)");
    }

    // ---- sequence flag plus payload ----

    fn assert_all_seqflag_correct(run: impl Fn() -> sequence_flag::Outcome, trials: u32) {
        for i in 0..trials {
            let outcome = run();
            assert_eq!(
                outcome,
                sequence_flag::Outcome::Correct,
                "trial {i} observed a stale payload behind its own sequence number"
            );
        }
    }

    #[test]
    fn seq_flag_acquire_release_never_observes_stale_payload() {
        assert_all_seqflag_correct(
            sequence_flag::acquire_release_publication,
            fixtures::SEQFLAG_TRIALS,
        );
    }

    #[test]
    fn seq_flag_volatile_seq_cst_never_observes_stale_payload() {
        assert_all_seqflag_correct(
            sequence_flag::volatile_seq_cst_publication,
            fixtures::SEQFLAG_TRIALS,
        );
    }

    #[test]
    fn seq_flag_cas_loop_never_observes_stale_payload() {
        assert_all_seqflag_correct(sequence_flag::cas_loop, fixtures::SEQFLAG_TRIALS);
    }

    #[test]
    fn seq_flag_fence_based_never_observes_stale_payload() {
        assert_all_seqflag_correct(sequence_flag::fence_based, fixtures::SEQFLAG_TRIALS);
    }

    #[test]
    fn seq_flag_plain_broken_publication_completes_without_hanging() {
        let trials = fixtures::SEQFLAG_TRIALS.min(20);
        let mut forbidden = 0;
        let mut timed_out = 0;
        for _ in 0..trials {
            match sequence_flag::plain_broken_publication() {
                sequence_flag::Outcome::ForbiddenStalePayload => forbidden += 1,
                sequence_flag::Outcome::TimedOut => timed_out += 1,
                sequence_flag::Outcome::Correct => {}
            }
        }
        assert_eq!(
            timed_out, 0,
            "plain publication should not hang the reader (bounded spin)"
        );
        println!("seq_flag plain_broken_publication: {forbidden}/{trials} forbidden reads observed (not asserted, reported honestly)");
    }

    // ---- counter update ----

    #[test]
    fn counter_acquire_release_matches_expected_total_exactly() {
        let r = counter_update::acquire_release_publication();
        assert_eq!(r.total as u64, fixtures::COUNTER_EXPECTED_TOTAL);
    }

    #[test]
    fn counter_cas_loop_matches_expected_total_exactly() {
        let r = counter_update::cas_loop();
        assert_eq!(r.total as u64, fixtures::COUNTER_EXPECTED_TOTAL);
    }

    #[test]
    fn counter_fence_based_matches_expected_total_exactly() {
        let r = counter_update::fence_based();
        assert_eq!(r.total as u64, fixtures::COUNTER_EXPECTED_TOTAL);
    }

    /// The trap variant — deliberately NOT asserted to equal the expected
    /// total; lost updates under contention are the expected, real
    /// result, reported honestly rather than gated on.
    #[test]
    fn counter_volatile_seq_cst_completes_and_reports_observed_loss() {
        let r = counter_update::volatile_seq_cst_publication();
        assert!(
            (r.total as u64) <= fixtures::COUNTER_EXPECTED_TOTAL,
            "total should never exceed the expected count"
        );
        println!(
            "counter volatile_seq_cst_publication: total={} expected={} (not asserted equal — reported honestly)",
            r.total,
            fixtures::COUNTER_EXPECTED_TOTAL
        );
    }

    #[test]
    fn counter_plain_broken_publication_completes_and_reports_observed_loss() {
        let r = counter_update::plain_broken_publication();
        assert!(
            (r.total as u64) <= fixtures::COUNTER_EXPECTED_TOTAL,
            "total should never exceed the expected count"
        );
        println!(
            "counter plain_broken_publication: total={} expected={} (not asserted equal — reported honestly)",
            r.total,
            fixtures::COUNTER_EXPECTED_TOTAL
        );
    }
}
