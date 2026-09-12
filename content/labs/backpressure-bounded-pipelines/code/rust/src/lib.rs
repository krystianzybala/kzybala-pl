//! A deterministic, single-threaded tick simulation of a bounded producer/consumer pipeline
//! under each overload policy — the Rust mirror of the Java `PipelineSimulator`. Determinism
//! (fixed production schedule, fixed consume rate, no wall-clock timing) is what makes this a
//! correctness fixture rather than a flaky concurrency test; real concurrent wall-clock timing
//! is answered separately by the Criterion benchmark harness (see benchmark.md).

use std::collections::{HashMap, VecDeque};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Policy {
    Unbounded,
    BoundedReject,
    BoundedBlock,
    DropOldest,
    CoalesceByKey,
    LoadShedding,
}

#[derive(Clone)]
pub struct PipelineParams {
    pub capacity: usize,
    pub produced_per_tick: Vec<usize>,
    pub consume_rate: usize,
    pub key_count: usize,
    pub shed_threshold_percent: u64,
}

#[derive(Debug, PartialEq, Eq)]
pub struct SimResult {
    pub produced: u64,
    pub delivered: u64,
    pub rejected: u64,
    pub dropped: u64,
    pub superseded: u64,
    pub max_queue_depth: usize,
}

impl SimResult {
    pub fn invariant_holds(&self) -> bool {
        self.produced == self.delivered + self.rejected + self.dropped + self.superseded
    }
}

pub fn simulate(policy: Policy, params: &PipelineParams) -> SimResult {
    let mut queue: VecDeque<u64> = VecDeque::new();
    let mut block_backlog: VecDeque<u64> = VecDeque::new();
    let mut coalesce_order: VecDeque<u64> = VecDeque::new();
    let mut coalesce_values: HashMap<u64, u64> = HashMap::new();

    let mut produced = 0u64;
    let mut delivered = 0u64;
    let mut rejected = 0u64;
    let mut dropped = 0u64;
    let mut superseded = 0u64;
    let mut max_depth: usize = 0;
    let mut next_id: u64 = 0;

    for &produced_this_tick in &params.produced_per_tick {
        if policy == Policy::BoundedBlock {
            while !block_backlog.is_empty() && queue.len() < params.capacity {
                queue.push_back(block_backlog.pop_front().unwrap());
            }
        }

        for _ in 0..produced_this_tick {
            let id = next_id;
            next_id += 1;
            produced += 1;
            let key = if params.key_count > 0 {
                id % params.key_count as u64
            } else {
                id
            };

            match policy {
                Policy::Unbounded => queue.push_back(id),
                Policy::BoundedReject => {
                    if queue.len() < params.capacity {
                        queue.push_back(id);
                    } else {
                        rejected += 1;
                    }
                }
                Policy::BoundedBlock => {
                    if queue.len() < params.capacity {
                        queue.push_back(id);
                    } else {
                        block_backlog.push_back(id);
                    }
                }
                Policy::DropOldest => {
                    if queue.len() >= params.capacity && !queue.is_empty() {
                        queue.pop_front();
                        dropped += 1;
                    }
                    queue.push_back(id);
                }
                Policy::CoalesceByKey => {
                    if coalesce_values.insert(key, id).is_some() {
                        superseded += 1;
                    } else {
                        coalesce_order.push_back(key);
                    }
                }
                Policy::LoadShedding => {
                    let occupancy_percent = if params.capacity == 0 {
                        0
                    } else {
                        (100u64 * queue.len() as u64) / params.capacity as u64
                    };
                    let hard_full = queue.len() >= params.capacity;
                    let proactively_shed =
                        occupancy_percent >= params.shed_threshold_percent && id % 2 == 0;
                    if hard_full || proactively_shed {
                        dropped += 1;
                    } else {
                        queue.push_back(id);
                    }
                }
            }
        }

        let current_depth = if policy == Policy::CoalesceByKey {
            coalesce_values.len()
        } else {
            queue.len()
        };
        max_depth = max_depth.max(current_depth);

        for _ in 0..params.consume_rate {
            if policy == Policy::CoalesceByKey {
                match pop_coalesced(&mut coalesce_order, &mut coalesce_values) {
                    Some(_) => delivered += 1,
                    None => break,
                }
            } else {
                match queue.pop_front() {
                    Some(_) => delivered += 1,
                    None => break,
                }
            }
        }
    }

    // Trailing synchronous drain so the invariant holds with zero items left queued.
    loop {
        if policy == Policy::CoalesceByKey {
            match pop_coalesced(&mut coalesce_order, &mut coalesce_values) {
                Some(_) => delivered += 1,
                None => break,
            }
        } else {
            if !block_backlog.is_empty() && queue.len() < params.capacity {
                queue.push_back(block_backlog.pop_front().unwrap());
                continue;
            }
            match queue.pop_front() {
                Some(_) => delivered += 1,
                None => break,
            }
        }
    }

    SimResult {
        produced,
        delivered,
        rejected,
        dropped,
        superseded,
        max_queue_depth: max_depth,
    }
}

fn pop_coalesced(order: &mut VecDeque<u64>, values: &mut HashMap<u64, u64>) -> Option<u64> {
    loop {
        let key = order.pop_front()?;
        if let Some(v) = values.remove(&key) {
            return Some(v);
        }
        // key was already removed by a previous pop for the same key slot; skip (should not
        // happen given coalescing never removes without a matching order entry, but keeps the
        // loop total).
    }
}

pub mod fixtures {
    use super::PipelineParams;

    fn constant(per_tick: usize, ticks: usize) -> Vec<usize> {
        vec![per_tick; ticks]
    }

    fn burst_then_drain(
        burst_per_tick: usize,
        burst_ticks: usize,
        drain_ticks: usize,
    ) -> Vec<usize> {
        let mut schedule = vec![0usize; burst_ticks + drain_ticks];
        for slot in schedule.iter_mut().take(burst_ticks) {
            *slot = burst_per_tick;
        }
        schedule
    }

    pub fn steady_below_capacity() -> PipelineParams {
        PipelineParams {
            capacity: 8,
            produced_per_tick: constant(4, 200),
            consume_rate: 4,
            key_count: 4,
            shed_threshold_percent: 50,
        }
    }

    pub fn short_burst() -> PipelineParams {
        PipelineParams {
            capacity: 8,
            produced_per_tick: burst_then_drain(20, 5, 200),
            consume_rate: 4,
            key_count: 4,
            shed_threshold_percent: 50,
        }
    }

    pub fn sustained_overload() -> PipelineParams {
        PipelineParams {
            capacity: 8,
            produced_per_tick: burst_then_drain(10, 50, 400),
            consume_rate: 4,
            key_count: 4,
            shed_threshold_percent: 50,
        }
    }

    pub fn hot_key_skew() -> PipelineParams {
        PipelineParams {
            capacity: 8,
            produced_per_tick: burst_then_drain(10, 50, 400),
            consume_rate: 4,
            key_count: 3,
            shed_threshold_percent: 50,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::fixtures::*;
    use super::*;

    const ALL_POLICIES: [Policy; 6] = [
        Policy::Unbounded,
        Policy::BoundedReject,
        Policy::BoundedBlock,
        Policy::DropOldest,
        Policy::CoalesceByKey,
        Policy::LoadShedding,
    ];

    fn assert_invariant(policy: Policy, params: &PipelineParams) {
        let result = simulate(policy, params);
        assert!(result.invariant_holds(), "{:?}: {:?}", policy, result);
        if policy != Policy::Unbounded {
            let bound = params.capacity.max(params.key_count);
            assert!(
                result.max_queue_depth <= bound,
                "{:?}: max_queue_depth {} exceeded bound {}",
                policy,
                result.max_queue_depth,
                bound
            );
        }
    }

    #[test]
    fn invariant_holds_on_every_policy_and_dataset() {
        let datasets = [
            steady_below_capacity(),
            short_burst(),
            sustained_overload(),
            hot_key_skew(),
        ];
        for dataset in &datasets {
            for &policy in &ALL_POLICIES {
                assert_invariant(policy, dataset);
            }
        }
    }

    #[test]
    fn unbounded_never_drops_or_rejects() {
        let result = simulate(Policy::Unbounded, &sustained_overload());
        assert_eq!(result.rejected, 0);
        assert_eq!(result.dropped, 0);
        assert_eq!(result.superseded, 0);
        assert_eq!(result.produced, result.delivered);
    }

    #[test]
    fn bounded_block_never_drops_or_rejects_but_still_bounded() {
        let result = simulate(Policy::BoundedBlock, &sustained_overload());
        assert_eq!(result.rejected, 0);
        assert_eq!(result.dropped, 0);
        assert_eq!(result.produced, result.delivered);
    }

    #[test]
    fn bounded_reject_actually_rejects_under_overload() {
        let result = simulate(Policy::BoundedReject, &sustained_overload());
        assert!(result.rejected > 0);
    }

    #[test]
    fn coalesce_by_key_supersedes_under_hot_key_skew() {
        let result = simulate(Policy::CoalesceByKey, &hot_key_skew());
        assert!(result.superseded > 0);
    }
}
