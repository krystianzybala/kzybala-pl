//! Companion code for the Performance Lab "Thread-per-Core and
//! Shared-Nothing Sharding" (kzybala.pl/lab/thread-per-core-sharding/).
//! See `rust.md` next to this crate for the full explanation.
//!
//! Mirrors `code/java/`'s kernels method-for-method. See
//! `code/fixtures/thread-per-core-sharding-fixtures.json` for the shared
//! dataset constants both languages assert against identically.

pub mod fixtures {
    pub const KEY_COUNT: usize = 16;
    pub const SHARD_COUNT: usize = 4;
    pub const REQUESTS_PER_KEY_UNIFORM: usize = 500;
    pub const REQUESTER_THREADS: usize = 4;
    pub const SKEWED_HOT_KEY_REQUESTS: usize = 4_000;
    pub const SKEWED_COLD_KEY_REQUESTS: usize = 200;

    pub fn expected_uniform(_key: usize) -> usize {
        REQUESTS_PER_KEY_UNIFORM
    }

    pub fn expected_skewed(key: usize) -> usize {
        if key == 0 {
            SKEWED_HOT_KEY_REQUESTS
        } else {
            SKEWED_COLD_KEY_REQUESTS
        }
    }

    pub fn shard_for(key: usize) -> usize {
        key % SHARD_COUNT
    }
}

pub struct WorkloadPlan {
    pub keys: Vec<usize>,
}

impl WorkloadPlan {
    fn build(expected: impl Fn(usize) -> usize) -> Self {
        let mut keys = Vec::new();
        for k in 0..fixtures::KEY_COUNT {
            for _ in 0..expected(k) {
                keys.push(k);
            }
        }
        WorkloadPlan { keys }
    }

    pub fn uniform() -> Self {
        Self::build(fixtures::expected_uniform)
    }

    pub fn skewed() -> Self {
        Self::build(fixtures::expected_skewed)
    }

    pub fn uniform_fraction(per_key_count: usize) -> Self {
        Self::build(|_| per_key_count)
    }

    /// The `[start, end)` slice assigned to requester `requester_id` out
    /// of `requester_count`.
    pub fn chunk_for(&self, requester_id: usize, requester_count: usize) -> Vec<usize> {
        let total = self.keys.len();
        let base = total / requester_count;
        let extra = total % requester_count;
        let start = requester_id * base + requester_id.min(extra);
        let end = start + base + if requester_id < extra { 1 } else { 0 };
        self.keys[start..end].to_vec()
    }
}

pub struct TpcResult {
    pub final_counters: Vec<u64>, // indexed by key
    pub rebalance_cost_nanos: u64,
}

impl TpcResult {
    pub fn is_correct_uniform(&self) -> bool {
        self.final_counters
            .iter()
            .enumerate()
            .all(|(k, &c)| c == fixtures::expected_uniform(k) as u64)
    }

    pub fn is_correct_skewed(&self) -> bool {
        self.final_counters
            .iter()
            .enumerate()
            .all(|(k, &c)| c == fixtures::expected_skewed(k) as u64)
    }
}

pub mod shared_map {
    //! The naive baseline: no sharding at all, every requester updates
    //! any key directly against one shared array of `AtomicU64` counters.

    use super::{fixtures::*, TpcResult, WorkloadPlan};
    use std::sync::atomic::{AtomicU64, Ordering};
    use std::sync::Arc;
    use std::thread;

    pub fn run(plan: &WorkloadPlan) -> TpcResult {
        let counters: Arc<Vec<AtomicU64>> =
            Arc::new((0..KEY_COUNT).map(|_| AtomicU64::new(0)).collect());

        let handles: Vec<_> = (0..REQUESTER_THREADS)
            .map(|r| {
                let chunk = plan.chunk_for(r, REQUESTER_THREADS);
                let counters = Arc::clone(&counters);
                thread::spawn(move || {
                    for key in chunk {
                        counters[key].fetch_add(1, Ordering::AcqRel);
                    }
                })
            })
            .collect();
        for h in handles {
            h.join().unwrap();
        }

        let final_counters = counters.iter().map(|c| c.load(Ordering::Acquire)).collect();
        TpcResult {
            final_counters,
            rebalance_cost_nanos: 0,
        }
    }
}

pub mod mutex_shards {
    //! Keys are partitioned into `SHARD_COUNT` fixed shards
    //! (`shard_for`), each guarded by its own `Mutex`.

    use super::{fixtures::*, TpcResult, WorkloadPlan};
    use std::sync::{Arc, Mutex};
    use std::thread;

    pub fn run(plan: &WorkloadPlan) -> TpcResult {
        let counters: Arc<Vec<Mutex<u64>>> =
            Arc::new((0..KEY_COUNT).map(|_| Mutex::new(0u64)).collect());

        let handles: Vec<_> = (0..REQUESTER_THREADS)
            .map(|r| {
                let chunk = plan.chunk_for(r, REQUESTER_THREADS);
                let counters = Arc::clone(&counters);
                thread::spawn(move || {
                    for key in chunk {
                        *counters[key].lock().unwrap() += 1;
                    }
                })
            })
            .collect();
        for h in handles {
            h.join().unwrap();
        }

        let final_counters = counters.iter().map(|c| *c.lock().unwrap()).collect();
        TpcResult {
            final_counters,
            rebalance_cost_nanos: 0,
        }
    }
}

pub mod single_writer {
    //! Shared-nothing, ownership-partitioned design: each shard is owned
    //! by exactly one worker thread, and only that thread writes that
    //! shard's counters. Routing uses `std::sync::mpsc` — Rust's own
    //! standard-library MPSC channel — one per shard, matching this
    //! lab's prerequisite MPSC lab's "library queue" reference point.

    use super::{fixtures::*, TpcResult, WorkloadPlan};
    use std::sync::mpsc;
    use std::thread;

    pub fn run(plan: &WorkloadPlan) -> TpcResult {
        let mut senders = Vec::with_capacity(SHARD_COUNT);
        let mut owner_handles = Vec::with_capacity(SHARD_COUNT);
        let mut expected_per_shard = [0usize; SHARD_COUNT];
        for &key in &plan.keys {
            expected_per_shard[shard_for(key)] += 1;
        }

        let mut receivers = Vec::with_capacity(SHARD_COUNT);
        for _ in 0..SHARD_COUNT {
            let (tx, rx) = mpsc::channel::<usize>();
            senders.push(tx);
            receivers.push(rx);
        }

        for (shard_id, rx) in receivers.into_iter().enumerate() {
            let expected = expected_per_shard[shard_id];
            owner_handles.push(thread::spawn(move || {
                let mut counters = vec![0u64; KEY_COUNT];
                for _ in 0..expected {
                    let key = rx
                        .recv()
                        .expect("requester dropped sender before shard total was reached");
                    counters[key] += 1; // safe: only this thread ever writes keys routed to this shard
                }
                counters
            }));
        }

        let requester_handles: Vec<_> = (0..REQUESTER_THREADS)
            .map(|r| {
                let chunk = plan.chunk_for(r, REQUESTER_THREADS);
                let senders = senders.clone();
                thread::spawn(move || {
                    for key in chunk {
                        senders[shard_for(key)]
                            .send(key)
                            .expect("owner dropped receiver");
                    }
                })
            })
            .collect();
        drop(senders); // owners' recv() loops rely on senders eventually being droppable
        for h in requester_handles {
            h.join().unwrap();
        }

        let mut final_counters = vec![0u64; KEY_COUNT];
        for h in owner_handles {
            let shard_counters = h.join().unwrap();
            for (k, c) in shard_counters.into_iter().enumerate() {
                final_counters[k] += c;
            }
        }

        TpcResult {
            final_counters,
            rebalance_cost_nanos: 0,
        }
    }
}

pub mod rebalance_simulation {
    //! A deliberately simplified "stop-the-world" rebalance: two
    //! half-sized phases against the single-writer mechanism, with a
    //! different key-to-shard mapping in each phase, fully quiesced
    //! (every phase-1 owner joined) between them — see the Java
    //! `RebalanceSimulationKernel` javadoc for the full disclosure of
    //! what this simplification does and does not model.

    use super::{fixtures::*, TpcResult, WorkloadPlan};
    use std::sync::mpsc;
    use std::thread;
    use std::time::Instant;

    fn run_phase(
        plan: &WorkloadPlan,
        shard_of: impl Fn(usize) -> usize + Copy + Send + 'static,
        counters: &mut [u64],
    ) {
        let mut expected_per_shard = [0usize; SHARD_COUNT];
        for &key in &plan.keys {
            expected_per_shard[shard_of(key)] += 1;
        }

        let mut senders = Vec::with_capacity(SHARD_COUNT);
        let mut receivers = Vec::with_capacity(SHARD_COUNT);
        for _ in 0..SHARD_COUNT {
            let (tx, rx) = mpsc::channel::<usize>();
            senders.push(tx);
            receivers.push(rx);
        }

        let owner_handles: Vec<_> = receivers
            .into_iter()
            .enumerate()
            .map(|(shard_id, rx)| {
                let expected = expected_per_shard[shard_id];
                thread::spawn(move || {
                    let mut local = vec![0u64; KEY_COUNT];
                    for _ in 0..expected {
                        let key = rx
                            .recv()
                            .expect("requester dropped sender before shard total was reached");
                        local[key] += 1;
                    }
                    local
                })
            })
            .collect();

        let requester_handles: Vec<_> = (0..REQUESTER_THREADS)
            .map(|r| {
                let chunk = plan.chunk_for(r, REQUESTER_THREADS);
                let senders = senders.clone();
                thread::spawn(move || {
                    for key in chunk {
                        senders[shard_of(key)]
                            .send(key)
                            .expect("owner dropped receiver");
                    }
                })
            })
            .collect();
        drop(senders);
        for h in requester_handles {
            h.join().unwrap();
        }
        for h in owner_handles {
            let local = h.join().unwrap(); // full quiescence — the happens-before edge the next phase relies on
            for (k, c) in local.into_iter().enumerate() {
                counters[k] += c;
            }
        }
    }

    pub fn run() -> TpcResult {
        let mut counters = vec![0u64; KEY_COUNT];
        let per_phase = REQUESTS_PER_KEY_UNIFORM / 2;

        run_phase(
            &WorkloadPlan::uniform_fraction(per_phase),
            shard_for,
            &mut counters,
        );

        let rebalance_start = Instant::now();
        // The "rebalance": key 0 moves to the next shard over. With full
        // quiescence already established by run_phase's joins, there is
        // no per-key state left to migrate beyond the counters array
        // (already safely visible) — this measures the pause's own
        // bookkeeping cost, not a realistic migration cost. See theory.md.
        let rebalanced_mapping = move |key: usize| {
            if key == 0 {
                (shard_for(0) + 1) % SHARD_COUNT
            } else {
                shard_for(key)
            }
        };
        let rebalance_cost_nanos = rebalance_start.elapsed().as_nanos() as u64;

        run_phase(
            &WorkloadPlan::uniform_fraction(per_phase),
            rebalanced_mapping,
            &mut counters,
        );

        TpcResult {
            final_counters: counters,
            rebalance_cost_nanos,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn shared_map_reaches_exact_uniform_counts() {
        let r = shared_map::run(&WorkloadPlan::uniform());
        assert!(r.is_correct_uniform());
    }

    #[test]
    fn shared_map_reaches_exact_skewed_counts() {
        let r = shared_map::run(&WorkloadPlan::skewed());
        assert!(r.is_correct_skewed());
    }

    #[test]
    fn mutex_shards_reaches_exact_uniform_counts() {
        let r = mutex_shards::run(&WorkloadPlan::uniform());
        assert!(r.is_correct_uniform());
    }

    #[test]
    fn mutex_shards_reaches_exact_skewed_counts() {
        let r = mutex_shards::run(&WorkloadPlan::skewed());
        assert!(r.is_correct_skewed());
    }

    #[test]
    fn single_writer_reaches_exact_uniform_counts() {
        let r = single_writer::run(&WorkloadPlan::uniform());
        assert!(r.is_correct_uniform());
    }

    #[test]
    fn single_writer_reaches_exact_skewed_counts() {
        let r = single_writer::run(&WorkloadPlan::skewed());
        assert!(r.is_correct_skewed());
    }

    #[test]
    fn rebalance_simulation_reaches_exact_uniform_counts_across_both_phases() {
        let r = rebalance_simulation::run();
        assert!(r.is_correct_uniform());
    }
}
