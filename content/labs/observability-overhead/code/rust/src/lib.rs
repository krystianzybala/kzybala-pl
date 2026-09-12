//! Logging/metrics/tracing overhead lab: the identical hot-path checksum computed under seven
//! instrumentation variants. Instrumentation must never change the checksum — only add (or avoid
//! adding) cost and side effects, which every field on `Outcome` besides `checksum` accounts for.

use std::backtrace::Backtrace;
use std::collections::HashMap;
use std::hint::black_box;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Variant {
    NoInstrumentation,
    DisabledEagerLogging,
    DisabledLazyLogging,
    SynchronousLogging,
    AsyncBoundedLogging,
    MetricsLabels,
    SampledTracing,
}

#[derive(Clone)]
pub struct ObsParams {
    pub ids: Vec<u32>,
    pub distinct_key_count: u32,
    pub sample_rate: u32,
    pub async_queue_capacity: usize,
    pub stack_depth: u32,
}

#[derive(Debug, PartialEq, Eq)]
pub struct Outcome {
    pub checksum: u64,
    pub calls: usize,
    pub format_count: u64,
    pub logged_count: u64,
    pub dropped_count: u64,
    pub distinct_labels_seen: usize,
    pub label_hits_total: u64,
    pub sampled_count: u64,
}

pub fn hot_path_work(id: u32) -> u64 {
    let mut x: u64 = (id as u64).wrapping_mul(0x9E3779B97F4A7C15).wrapping_add(1);
    x ^= x << 13;
    x ^= x >> 7;
    x ^= x << 17;
    x
}

pub fn work_through_stack(id: u32, depth: u32) -> u64 {
    if depth == 0 {
        hot_path_work(id)
    } else {
        work_through_stack(id, depth - 1)
    }
}

const LOG_LEVEL_DISABLED: bool = true;

pub fn run(variant: Variant, params: &ObsParams) -> Outcome {
    let mut checksum: u64 = 0;
    let mut format_count: u64 = 0;
    let mut logged_count: u64 = 0;
    let mut dropped_count: u64 = 0;
    let mut sampled_count: u64 = 0;
    let mut label_counts: HashMap<u32, u64> = HashMap::new();
    let mut sync_log_buffer = String::new();
    let mut async_occupancy: usize = 0;

    for &id in &params.ids {
        checksum ^= if variant == Variant::SampledTracing {
            work_through_stack(id, params.stack_depth)
        } else {
            hot_path_work(id)
        };

        match variant {
            Variant::NoInstrumentation => {}
            Variant::DisabledEagerLogging => {
                let message = format!("event id={id} checksum={checksum}");
                format_count += 1;
                if !LOG_LEVEL_DISABLED {
                    logged_count += 1;
                }
                black_box(message);
            }
            Variant::DisabledLazyLogging => {
                if !LOG_LEVEL_DISABLED {
                    let message = format!("event id={id} checksum={checksum}");
                    format_count += 1;
                    logged_count += 1;
                    black_box(message);
                }
            }
            Variant::SynchronousLogging => {
                let message = format!("event id={id} checksum={checksum}");
                format_count += 1;
                sync_log_buffer.push_str(&message);
                sync_log_buffer.push('\n');
                logged_count += 1;
            }
            Variant::AsyncBoundedLogging => {
                let message = format!("event id={id} checksum={checksum}");
                format_count += 1;
                if async_occupancy < params.async_queue_capacity {
                    async_occupancy += 1;
                    logged_count += 1;
                    black_box(message);
                } else {
                    dropped_count += 1;
                }
            }
            Variant::MetricsLabels => {
                let key = if params.distinct_key_count > 0 {
                    id % params.distinct_key_count
                } else {
                    id
                };
                *label_counts.entry(key).or_insert(0) += 1;
            }
            Variant::SampledTracing => {
                if params.sample_rate > 0 && id % params.sample_rate == 0 {
                    let trace = Backtrace::force_capture();
                    sampled_count += 1;
                    let _ = black_box(trace);
                }
            }
        }
    }

    let label_hits_total: u64 = label_counts.values().sum();
    Outcome {
        checksum,
        calls: params.ids.len(),
        format_count,
        logged_count,
        dropped_count,
        distinct_labels_seen: label_counts.len(),
        label_hits_total,
        sampled_count,
    }
}

pub mod fixtures {
    use super::ObsParams;

    fn sequential(n: u32) -> Vec<u32> {
        (0..n).collect()
    }

    pub fn single_event() -> ObsParams {
        ObsParams {
            ids: sequential(1),
            distinct_key_count: 4,
            sample_rate: 8,
            async_queue_capacity: 16,
            stack_depth: 3,
        }
    }

    pub fn error_burst() -> ObsParams {
        ObsParams {
            ids: sequential(2000),
            distinct_key_count: 4,
            sample_rate: 8,
            async_queue_capacity: 16,
            stack_depth: 3,
        }
    }

    pub fn high_cardinality_key() -> ObsParams {
        ObsParams {
            ids: sequential(2000),
            distinct_key_count: 500,
            sample_rate: 8,
            async_queue_capacity: 16,
            stack_depth: 3,
        }
    }

    pub fn stack_trace_path() -> ObsParams {
        ObsParams {
            ids: sequential(2000),
            distinct_key_count: 4,
            sample_rate: 8,
            async_queue_capacity: 16,
            stack_depth: 12,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::fixtures::*;
    use super::*;

    const ALL_VARIANTS: [Variant; 7] = [
        Variant::NoInstrumentation,
        Variant::DisabledEagerLogging,
        Variant::DisabledLazyLogging,
        Variant::SynchronousLogging,
        Variant::AsyncBoundedLogging,
        Variant::MetricsLabels,
        Variant::SampledTracing,
    ];

    fn expected_checksum(params: &ObsParams) -> u64 {
        params
            .ids
            .iter()
            .fold(0u64, |acc, &id| acc ^ hot_path_work(id))
    }

    #[test]
    fn checksum_independent_of_variant_on_error_burst() {
        let params = error_burst();
        for &variant in &ALL_VARIANTS {
            assert_eq!(
                run(variant, &params).checksum,
                expected_checksum(&params),
                "{:?}",
                variant
            );
        }
    }

    #[test]
    fn checksum_independent_of_variant_on_stack_trace_path() {
        let params = stack_trace_path();
        for &variant in &ALL_VARIANTS {
            assert_eq!(
                run(variant, &params).checksum,
                expected_checksum(&params),
                "{:?}",
                variant
            );
        }
    }

    #[test]
    fn disabled_eager_logging_still_pays_format_cost() {
        let params = error_burst();
        let outcome = run(Variant::DisabledEagerLogging, &params);
        assert_eq!(outcome.format_count, params.ids.len() as u64);
        assert_eq!(outcome.logged_count, 0);
    }

    #[test]
    fn disabled_lazy_logging_avoids_format_cost() {
        let params = error_burst();
        let outcome = run(Variant::DisabledLazyLogging, &params);
        assert_eq!(outcome.format_count, 0);
    }

    #[test]
    fn synchronous_logging_never_drops() {
        let params = error_burst();
        let outcome = run(Variant::SynchronousLogging, &params);
        assert_eq!(outcome.logged_count, params.ids.len() as u64);
        assert_eq!(outcome.dropped_count, 0);
    }

    #[test]
    fn async_bounded_logging_drops_exactly_the_overflow() {
        let params = error_burst();
        let outcome = run(Variant::AsyncBoundedLogging, &params);
        assert_eq!(outcome.logged_count, params.async_queue_capacity as u64);
        assert_eq!(
            outcome.dropped_count,
            params.ids.len() as u64 - params.async_queue_capacity as u64
        );
    }

    #[test]
    fn metrics_labels_tracks_exact_distinct_key_count() {
        let params = high_cardinality_key();
        let outcome = run(Variant::MetricsLabels, &params);
        assert_eq!(
            outcome.distinct_labels_seen,
            params.distinct_key_count.min(params.ids.len() as u32) as usize
        );
        assert_eq!(outcome.label_hits_total, params.ids.len() as u64);
    }

    #[test]
    fn sampled_tracing_captures_exactly_every_nth() {
        let params = stack_trace_path();
        let outcome = run(Variant::SampledTracing, &params);
        let expected = params
            .ids
            .iter()
            .filter(|&&id| id % params.sample_rate == 0)
            .count() as u64;
        assert_eq!(outcome.sampled_count, expected);
    }

    #[test]
    fn no_instrumentation_has_no_side_effects() {
        let outcome = run(Variant::NoInstrumentation, &error_burst());
        assert_eq!(outcome.format_count, 0);
        assert_eq!(outcome.logged_count, 0);
        assert_eq!(outcome.dropped_count, 0);
        assert_eq!(outcome.sampled_count, 0);
    }
}
