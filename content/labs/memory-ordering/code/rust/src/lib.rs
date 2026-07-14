//! Companion code for the Performance Lab "Memory ordering in Java and
//! Rust" lab (kzybala.pl/lab/memory-ordering/). See `rust.md` next to this
//! crate for the full explanation. Not a benchmark crate — see
//! `theory.md` "Methodology and limitations" for why timing-dependent
//! reordering effects are not reported as portable numbers.

use std::sync::atomic::{AtomicI32, Ordering};
use std::sync::Arc;
use std::thread;

/// Plain — er, `Relaxed` — access gives no ordering guarantee at all:
/// [`try_consume`](Self::try_consume) may legally observe `flag == 1` while
/// still observing `data == 0`. See rust.md and the interactive model's
/// "Broken publication" scenario.
pub struct RelaxedPublication {
    data: AtomicI32,
    flag: AtomicI32,
}

impl RelaxedPublication {
    pub fn new() -> Self {
        Self { data: AtomicI32::new(0), flag: AtomicI32::new(0) }
    }

    /// Publisher thread.
    pub fn publish(&self) {
        self.data.store(42, Ordering::Relaxed);
        self.flag.store(1, Ordering::Relaxed);
    }

    /// Observer thread. Returns `true` only if it saw the published value;
    /// `false` either because the flag wasn't set yet, or — the bug this
    /// type exists to demonstrate — because the flag was visible but the
    /// data wasn't.
    pub fn try_consume(&self) -> bool {
        if self.flag.load(Ordering::Relaxed) == 1 {
            return self.data.load(Ordering::Relaxed) == 42;
        }
        false
    }
}

impl Default for RelaxedPublication {
    fn default() -> Self {
        Self::new()
    }
}

/// The fix for [`RelaxedPublication`]: a `Release` store on the flag
/// flushes everything published before it in program order, and an
/// `Acquire` load that observes it is guaranteed to see that data too. See
/// rust.md and the interactive model's "Release/acquire message passing"
/// scenario.
pub struct ReleaseAcquirePublication {
    data: AtomicI32,
    flag: AtomicI32,
}

impl ReleaseAcquirePublication {
    pub fn new() -> Self {
        Self { data: AtomicI32::new(0), flag: AtomicI32::new(0) }
    }

    /// Publisher thread.
    pub fn publish(&self) {
        self.data.store(42, Ordering::Relaxed); // ordering comes from the release below
        self.flag.store(1, Ordering::Release);
    }

    /// Observer thread. Once this observes `flag == 1` via `Acquire`, the
    /// relaxed read of `data` is guaranteed to see the publisher's write —
    /// unlike [`RelaxedPublication::try_consume`].
    pub fn try_consume(&self) -> bool {
        if self.flag.load(Ordering::Acquire) == 1 {
            return self.data.load(Ordering::Relaxed) == 42;
        }
        false
    }
}

impl Default for ReleaseAcquirePublication {
    fn default() -> Self {
        Self::new()
    }
}

/// The classic store-buffering litmus test: thread 0 writes `x` then reads
/// `y`; thread 1 writes `y` then reads `x` — no dependency between the
/// variables at all. Under `Relaxed`, both threads can legally observe 0
/// for the other's write; under `SeqCst`, that specific outcome cannot
/// occur. See rust.md and the interactive model's "Store buffering" /
/// "Sequential consistency comparison" scenarios.
///
/// This is a genuine hardware-timing-dependent phenomenon (theory.md
/// "Methodology and limitations") — [`run_once`](Self::run_once) is
/// exposed so callers can run it many times and observe the distribution
/// of outcomes, rather than asserting a single racy result is guaranteed
/// either way.
pub struct StoreBufferingTest {
    x: AtomicI32,
    y: AtomicI32,
}

impl StoreBufferingTest {
    pub fn new() -> Self {
        Self { x: AtomicI32::new(0), y: AtomicI32::new(0) }
    }

    /// Runs one instance of the litmus test on fresh state under `ordering`
    /// and returns `(seen_y, seen_x)` — what thread 0 observed for `y` and
    /// what thread 1 observed for `x`.
    pub fn run_once(ordering: Ordering) -> (i32, i32) {
        let test = Arc::new(Self::new());
        let a = Arc::clone(&test);
        let b = Arc::clone(&test);
        let t0 = thread::spawn(move || {
            a.x.store(1, ordering);
            a.y.load(ordering)
        });
        let t1 = thread::spawn(move || {
            b.y.store(1, ordering);
            b.x.load(ordering)
        });
        (t0.join().unwrap(), t1.join().unwrap())
    }
}

impl Default for StoreBufferingTest {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // ReleaseAcquirePublication.try_consume() can only ever return true
    // once it has actually seen data == 42 (it checks this explicitly), so
    // the property worth testing isn't "never returns true incorrectly" —
    // it's "does Release/Acquire reliably deliver visibility promptly,"
    // i.e. does a bounded spin-wait actually succeed, every trial.
    #[test]
    fn release_acquire_publication_consumer_reliably_observes_publication_within_bounded_spins() {
        for trial in 0..2_000 {
            let publication = Arc::new(ReleaseAcquirePublication::new());
            let publisher_handle = Arc::clone(&publication);
            let consumer_handle = Arc::clone(&publication);

            let publisher = thread::spawn(move || publisher_handle.publish());
            let consumer = thread::spawn(move || {
                for _ in 0..1_000_000 {
                    if consumer_handle.try_consume() {
                        return true;
                    }
                }
                false
            });

            publisher.join().unwrap();
            let consumed = consumer.join().unwrap();
            assert!(consumed, "consumer failed to observe the publication within its spin bound on trial {trial}");
        }
    }

    // A same-thread sanity check only — RelaxedPublication's whole point is
    // that cross-thread ordering is NOT guaranteed, so this test does not
    // attempt to assert anything about concurrent visibility.
    #[test]
    fn relaxed_publication_publisher_and_data_are_consistent_when_observed_synchronously() {
        let publication = RelaxedPublication::new();
        publication.publish();
        assert!(publication.try_consume());
    }

    #[test]
    fn store_buffering_test_runs_and_reports_without_asserting_either_outcome() {
        // An investigation tool, not a pass/fail gate on a racy hardware
        // phenomenon (see theory.md "Methodology and limitations", and the
        // lab's investigation task). Only asserts both observed values are
        // one of the two legal results (0 or 1).
        let mut saw_both_zero_under_relaxed = 0;
        for _ in 0..500 {
            let (seen_y, seen_x) = StoreBufferingTest::run_once(Ordering::Relaxed);
            assert!(seen_y == 0 || seen_y == 1);
            assert!(seen_x == 0 || seen_x == 1);
            if seen_y == 0 && seen_x == 0 {
                saw_both_zero_under_relaxed += 1;
            }
        }
        println!("StoreBufferingTest (Relaxed): both-saw-0 in {saw_both_zero_under_relaxed}/500 runs on this machine/target.");
    }
}
