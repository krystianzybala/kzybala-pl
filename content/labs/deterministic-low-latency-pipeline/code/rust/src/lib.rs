//! Capstone pipeline lab: a deterministic, single-threaded discrete-event simulation of a
//! fixed-event, key-sharded, bounded-admission pipeline — the Rust mirror of the Java
//! `PipelineSimulator`. No real threads or byte buffers here; the real concurrent, real-buffer
//! version lives in the benchmark harness (rust.md), never in this correctness fixture.

use std::collections::VecDeque;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PipelineVariant {
    NaiveObjectQueue,
    Optimized,
    OverloadProfile,
    FaultRestartProfile,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum KeyPattern {
    Uniform,
    HotKeySkew,
}

impl KeyPattern {
    pub fn key_for(&self, id: u32) -> u32 {
        match self {
            KeyPattern::Uniform => id,
            KeyPattern::HotKeySkew => {
                if id % 5 == 0 {
                    id
                } else {
                    0
                }
            }
        }
    }
}

#[derive(Clone)]
pub struct PipelineParams {
    pub event_size_bytes: u32,
    pub key_pattern: KeyPattern,
    pub produced_per_tick: Vec<u32>,
    pub num_shards: usize,
    pub capacity_per_shard: usize,
    pub consume_rate_per_shard: u32,
    pub restart_at_tick: usize,
    pub restart_shard: usize,
}

#[derive(Debug, PartialEq, Eq)]
pub struct Outcome {
    pub produced: u32,
    pub delivered: u32,
    pub dropped: u32,
    pub restart_loss: u32,
    pub checksum: u64,
    pub max_shard_depth: usize,
}

impl Outcome {
    pub fn invariant_holds(&self) -> bool {
        self.produced == self.delivered + self.dropped + self.restart_loss
    }
}

pub fn decision_checksum(key: u32, event_id: u32, event_size_bytes: u32) -> u64 {
    let mut x: u64 = ((key as u64) << 32)
        ^ (event_id as u64).wrapping_mul(0x9E3779B97F4A7C15)
        ^ (event_size_bytes as u64);
    x ^= x << 13;
    x ^= x >> 7;
    x ^= x << 17;
    x
}

pub fn simulate(variant: PipelineVariant, params: &PipelineParams) -> Outcome {
    let num_shards = if variant == PipelineVariant::NaiveObjectQueue {
        1
    } else {
        params.num_shards
    };
    let capacity = match variant {
        PipelineVariant::OverloadProfile => (params.capacity_per_shard / 8).max(1),
        PipelineVariant::NaiveObjectQueue => usize::MAX,
        _ => params.capacity_per_shard,
    };

    let mut queues: Vec<VecDeque<u32>> = (0..num_shards).map(|_| VecDeque::new()).collect();
    let mut produced: u32 = 0;
    let mut delivered: u32 = 0;
    let mut dropped: u32 = 0;
    let mut restart_loss: u32 = 0;
    let mut max_depth: usize = 0;
    let mut checksum: u64 = 0;
    let mut next_id: u32 = 0;

    for (tick, &produced_this_tick) in params.produced_per_tick.iter().enumerate() {
        for _ in 0..produced_this_tick {
            let id = next_id;
            next_id += 1;
            produced += 1;
            let key = params.key_pattern.key_for(id);
            let shard = if num_shards > 1 {
                (key as usize) % num_shards
            } else {
                0
            };
            if queues[shard].len() < capacity {
                queues[shard].push_back(id);
            } else {
                dropped += 1;
            }
        }

        if variant == PipelineVariant::FaultRestartProfile && tick == params.restart_at_tick {
            let victim = params.restart_shard % num_shards;
            restart_loss += queues[victim].len() as u32;
            queues[victim].clear();
        }

        for shard_queue in queues.iter_mut() {
            max_depth = max_depth.max(shard_queue.len());
            for _ in 0..params.consume_rate_per_shard {
                match shard_queue.pop_front() {
                    Some(id) => {
                        let key = params.key_pattern.key_for(id);
                        checksum ^= decision_checksum(key, id, params.event_size_bytes);
                        delivered += 1;
                    }
                    None => break,
                }
            }
        }
    }

    // Trailing synchronous drain so the invariant holds with zero events left queued.
    loop {
        let mut progressed = false;
        for shard_queue in queues.iter_mut() {
            if let Some(id) = shard_queue.pop_front() {
                let key = params.key_pattern.key_for(id);
                checksum ^= decision_checksum(key, id, params.event_size_bytes);
                delivered += 1;
                progressed = true;
            }
        }
        if !progressed {
            break;
        }
    }

    Outcome {
        produced,
        delivered,
        dropped,
        restart_loss,
        checksum,
        max_shard_depth: max_depth,
    }
}

pub mod fixtures {
    use super::{KeyPattern, PipelineParams};

    fn constant(per_tick: u32, ticks: usize) -> Vec<u32> {
        vec![per_tick; ticks]
    }

    fn burst_then_drain(burst_per_tick: u32, burst_ticks: usize, drain_ticks: usize) -> Vec<u32> {
        let mut schedule = vec![0u32; burst_ticks + drain_ticks];
        for slot in schedule.iter_mut().take(burst_ticks) {
            *slot = burst_per_tick;
        }
        schedule
    }

    pub fn small_events_uniform_steady() -> PipelineParams {
        PipelineParams {
            event_size_bytes: 32,
            key_pattern: KeyPattern::Uniform,
            produced_per_tick: constant(5, 300),
            num_shards: 4,
            capacity_per_shard: 50,
            consume_rate_per_shard: 5,
            restart_at_tick: 5,
            restart_shard: 0,
        }
    }

    pub fn large_events_uniform_steady() -> PipelineParams {
        PipelineParams {
            event_size_bytes: 1024,
            key_pattern: KeyPattern::Uniform,
            produced_per_tick: constant(5, 300),
            num_shards: 4,
            capacity_per_shard: 50,
            consume_rate_per_shard: 5,
            restart_at_tick: 5,
            restart_shard: 0,
        }
    }

    pub fn medium_events_hot_key_burst() -> PipelineParams {
        PipelineParams {
            event_size_bytes: 128,
            key_pattern: KeyPattern::HotKeySkew,
            produced_per_tick: burst_then_drain(40, 10, 100),
            num_shards: 4,
            capacity_per_shard: 50,
            consume_rate_per_shard: 5,
            restart_at_tick: 5,
            restart_shard: 0,
        }
    }

    pub fn medium_events_uniform_burst() -> PipelineParams {
        PipelineParams {
            event_size_bytes: 128,
            key_pattern: KeyPattern::Uniform,
            produced_per_tick: burst_then_drain(40, 10, 100),
            num_shards: 4,
            capacity_per_shard: 50,
            consume_rate_per_shard: 5,
            restart_at_tick: 5,
            restart_shard: 0,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::fixtures::*;
    use super::*;

    const ALL_VARIANTS: [PipelineVariant; 4] = [
        PipelineVariant::NaiveObjectQueue,
        PipelineVariant::Optimized,
        PipelineVariant::OverloadProfile,
        PipelineVariant::FaultRestartProfile,
    ];

    #[test]
    fn invariant_holds_on_small_events_uniform_steady() {
        for &variant in &ALL_VARIANTS {
            let outcome = simulate(variant, &small_events_uniform_steady());
            assert!(outcome.invariant_holds(), "{:?}: {:?}", variant, outcome);
        }
    }

    #[test]
    fn invariant_holds_on_medium_events_hot_key_burst() {
        for &variant in &ALL_VARIANTS {
            let outcome = simulate(variant, &medium_events_hot_key_burst());
            assert!(outcome.invariant_holds(), "{:?}: {:?}", variant, outcome);
        }
    }

    #[test]
    fn invariant_holds_on_medium_events_uniform_burst() {
        for &variant in &ALL_VARIANTS {
            let outcome = simulate(variant, &medium_events_uniform_burst());
            assert!(outcome.invariant_holds(), "{:?}: {:?}", variant, outcome);
        }
    }

    #[test]
    fn naive_never_drops_or_loses_events() {
        let outcome = simulate(
            PipelineVariant::NaiveObjectQueue,
            &medium_events_uniform_burst(),
        );
        assert_eq!(outcome.dropped, 0);
        assert_eq!(outcome.restart_loss, 0);
        assert_eq!(outcome.produced, outcome.delivered);
    }

    #[test]
    fn checksum_independent_of_variant_when_nothing_is_dropped_or_lost() {
        let params = small_events_uniform_steady();
        let naive = simulate(PipelineVariant::NaiveObjectQueue, &params);
        let optimized = simulate(PipelineVariant::Optimized, &params);
        assert_eq!(optimized.dropped, 0);
        assert_eq!(naive.checksum, optimized.checksum);
    }

    #[test]
    fn overload_profile_forces_drops_even_under_a_handleable_dataset() {
        let optimized = simulate(PipelineVariant::Optimized, &medium_events_hot_key_burst());
        let overload = simulate(
            PipelineVariant::OverloadProfile,
            &medium_events_hot_key_burst(),
        );
        assert!(overload.dropped > optimized.dropped);
    }

    #[test]
    fn hot_key_skew_can_overflow_one_shard_even_with_generous_aggregate_capacity() {
        let outcome = simulate(PipelineVariant::Optimized, &medium_events_hot_key_burst());
        assert!(outcome.max_shard_depth > 0);
    }

    #[test]
    fn fault_restart_profile_holds_invariant() {
        let outcome = simulate(
            PipelineVariant::FaultRestartProfile,
            &medium_events_hot_key_burst(),
        );
        assert!(outcome.invariant_holds());
    }
}
