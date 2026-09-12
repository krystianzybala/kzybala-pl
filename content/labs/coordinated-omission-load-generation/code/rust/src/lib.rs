//! Coordinated omission lab: a deterministic, single-threaded discrete-event simulation of a
//! load generator driving one FIFO, single-worker server on a virtual (logical) clock — no real
//! sleeping or wall-clock timing, which is what makes this a correctness fixture rather than a
//! timing-sensitive race. Mirrors the Java `LoadGenSimulator` recurrence exactly.

use std::collections::VecDeque;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Variant {
    ClosedLoop,
    OpenLoopFixedRate,
    PoissonLikeArrivals,
    OmissionCorrectedRecording,
    BurstSchedule,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ServiceTimeModel {
    PeriodicStall,
    GcPauseInjection,
    BoundedOverload,
}

impl ServiceTimeModel {
    pub fn service_time_nanos(&self, index: u64) -> u64 {
        match self {
            ServiceTimeModel::PeriodicStall => {
                if index > 0 && index % 50 == 0 {
                    10_000_000
                } else {
                    100
                }
            }
            ServiceTimeModel::GcPauseInjection => {
                if index == 500 {
                    50_000_000
                } else {
                    100
                }
            }
            ServiceTimeModel::BoundedOverload => 150,
        }
    }
}

#[derive(Clone, Copy)]
pub struct LoadParams {
    pub count: u64,
    pub service_model: ServiceTimeModel,
    pub interval_nanos: u64,
    pub queue_capacity: usize,
    pub burst_size: u64,
}

#[derive(Debug, PartialEq, Eq)]
pub struct Outcome {
    pub produced: u64,
    pub admitted: u64,
    pub missed: u64,
    pub recorded_samples: u64,
    pub total_service_time_nanos: u64,
    pub total_response_time_nanos: u64,
    pub max_response_time_nanos: u64,
}

impl Outcome {
    pub fn admission_invariant_holds(&self) -> bool {
        self.produced == self.admitted + self.missed
    }
}

pub fn simulate(variant: Variant, params: &LoadParams) -> Outcome {
    match variant {
        Variant::ClosedLoop => closed_loop(params),
        Variant::OpenLoopFixedRate => open_loop(params, |i, p| i * p.interval_nanos),
        Variant::PoissonLikeArrivals => open_loop(params, poisson_like_schedule),
        Variant::OmissionCorrectedRecording => omission_corrected(params),
        Variant::BurstSchedule => open_loop(params, burst_schedule),
    }
}

fn closed_loop(params: &LoadParams) -> Outcome {
    let mut clock: u64 = 0;
    let mut total_service = 0u64;
    let mut total_response = 0u64;
    let mut max_response = 0u64;
    for i in 0..params.count {
        let service_time = params.service_model.service_time_nanos(i);
        let completion = clock + service_time;
        let response_time = completion - clock;
        total_service += service_time;
        total_response += response_time;
        max_response = max_response.max(response_time);
        clock = completion;
    }
    Outcome {
        produced: params.count,
        admitted: params.count,
        missed: 0,
        recorded_samples: params.count,
        total_service_time_nanos: total_service,
        total_response_time_nanos: total_response,
        max_response_time_nanos: max_response,
    }
}

fn open_loop(params: &LoadParams, intended_send_time: impl Fn(u64, &LoadParams) -> u64) -> Outcome {
    let mut in_flight: VecDeque<u64> = VecDeque::new();
    let mut next_free_time: u64 = 0;
    let mut admitted = 0u64;
    let mut missed = 0u64;
    let mut total_service = 0u64;
    let mut total_response = 0u64;
    let mut max_response = 0u64;

    for i in 0..params.count {
        let send_time = intended_send_time(i, params);
        while let Some(&front) = in_flight.front() {
            if front <= send_time {
                in_flight.pop_front();
            } else {
                break;
            }
        }
        if in_flight.len() >= params.queue_capacity {
            missed += 1;
            continue;
        }
        let service_time = params.service_model.service_time_nanos(i);
        let start = next_free_time.max(send_time);
        let completion = start + service_time;
        next_free_time = completion;
        in_flight.push_back(completion);

        let response_time = completion - send_time;
        admitted += 1;
        total_service += service_time;
        total_response += response_time;
        max_response = max_response.max(response_time);
    }

    Outcome {
        produced: params.count,
        admitted,
        missed,
        recorded_samples: admitted,
        total_service_time_nanos: total_service,
        total_response_time_nanos: total_response,
        max_response_time_nanos: max_response,
    }
}

fn omission_corrected(params: &LoadParams) -> Outcome {
    let mut clock: u64 = 0;
    let mut total_service = 0u64;
    let mut total_response = 0u64;
    let mut max_response = 0u64;
    let mut recorded_samples = 0u64;
    let interval = params.interval_nanos;

    for i in 0..params.count {
        let service_time = params.service_model.service_time_nanos(i);
        let completion = clock + service_time;
        let response_time = completion - clock;
        total_service += service_time;
        total_response += response_time;
        max_response = max_response.max(response_time);
        recorded_samples += 1;

        if interval > 0 && response_time > interval {
            let missed_intervals = response_time / interval - 1;
            for k in 1..=missed_intervals {
                let corrected_sample = response_time - k * interval;
                total_response += corrected_sample;
                max_response = max_response.max(corrected_sample);
                recorded_samples += 1;
            }
        }
        clock = completion;
    }

    Outcome {
        produced: params.count,
        admitted: params.count,
        missed: 0,
        recorded_samples,
        total_service_time_nanos: total_service,
        total_response_time_nanos: total_response,
        max_response_time_nanos: max_response,
    }
}

fn poisson_like_schedule(i: u64, params: &LoadParams) -> u64 {
    let interval = params.interval_nanos;
    let gap_pattern = [
        interval / 2,
        interval + interval / 2,
        (interval * 8) / 10,
        (interval * 12) / 10,
        interval,
    ];
    let mut t = 0u64;
    for k in 0..i {
        t += gap_pattern[(k % gap_pattern.len() as u64) as usize];
    }
    t
}

fn burst_schedule(i: u64, params: &LoadParams) -> u64 {
    let burst_size = params.burst_size.max(1);
    let burst_interval = params.interval_nanos * burst_size;
    (i / burst_size) * burst_interval
}

pub mod fixtures {
    use super::{LoadParams, ServiceTimeModel};

    pub fn periodic_ten_ms_stall() -> LoadParams {
        LoadParams {
            count: 600,
            service_model: ServiceTimeModel::PeriodicStall,
            interval_nanos: 1_000_000,
            queue_capacity: 1000,
            burst_size: 5,
        }
    }

    pub fn gc_pause_injection() -> LoadParams {
        LoadParams {
            count: 600,
            service_model: ServiceTimeModel::GcPauseInjection,
            interval_nanos: 1_000_000,
            queue_capacity: 1000,
            burst_size: 5,
        }
    }

    pub fn bounded_server_overload() -> LoadParams {
        LoadParams {
            count: 600,
            service_model: ServiceTimeModel::BoundedOverload,
            interval_nanos: 100,
            queue_capacity: 20,
            burst_size: 5,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::fixtures::*;
    use super::*;

    const ALL_VARIANTS: [Variant; 5] = [
        Variant::ClosedLoop,
        Variant::OpenLoopFixedRate,
        Variant::PoissonLikeArrivals,
        Variant::OmissionCorrectedRecording,
        Variant::BurstSchedule,
    ];

    #[test]
    fn admission_invariant_holds_on_periodic_stall() {
        for &variant in &ALL_VARIANTS {
            let outcome = simulate(variant, &periodic_ten_ms_stall());
            assert!(
                outcome.admission_invariant_holds(),
                "{:?}: {:?}",
                variant,
                outcome
            );
        }
    }

    #[test]
    fn admission_invariant_holds_on_bounded_overload() {
        for &variant in &ALL_VARIANTS {
            let outcome = simulate(variant, &bounded_server_overload());
            assert!(
                outcome.admission_invariant_holds(),
                "{:?}: {:?}",
                variant,
                outcome
            );
        }
    }

    #[test]
    fn closed_loop_response_time_always_equals_service_time() {
        let outcome = simulate(Variant::ClosedLoop, &periodic_ten_ms_stall());
        assert_eq!(
            outcome.total_service_time_nanos,
            outcome.total_response_time_nanos
        );
        assert_eq!(outcome.missed, 0);
    }

    #[test]
    fn closed_loop_max_response_time_equals_stall_magnitude() {
        let outcome = simulate(Variant::ClosedLoop, &periodic_ten_ms_stall());
        assert_eq!(outcome.max_response_time_nanos, 10_000_000);
    }

    #[test]
    fn omission_corrected_records_more_samples_than_produced_under_stall() {
        let outcome = simulate(
            Variant::OmissionCorrectedRecording,
            &periodic_ten_ms_stall(),
        );
        assert_eq!(outcome.missed, 0);
        assert!(outcome.recorded_samples > outcome.produced);
    }

    #[test]
    fn open_loop_fixed_rate_misses_under_sustained_overload() {
        let outcome = simulate(Variant::OpenLoopFixedRate, &bounded_server_overload());
        assert!(outcome.missed > 0);
    }

    #[test]
    fn open_loop_never_misses_under_light_load() {
        let outcome = simulate(Variant::OpenLoopFixedRate, &periodic_ten_ms_stall());
        assert_eq!(outcome.missed, 0);
    }

    #[test]
    fn burst_schedule_holds_invariant() {
        let outcome = simulate(Variant::BurstSchedule, &periodic_ten_ms_stall());
        assert!(outcome.admission_invariant_holds());
    }
}
