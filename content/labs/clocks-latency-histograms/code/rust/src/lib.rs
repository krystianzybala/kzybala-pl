//! Deterministic synthetic latency streams, histogram conventions and the
//! response-time model for the "Clocks, latency histograms and
//! percentiles" lab — the cross-language equivalence contract
//! (../fixtures/clocks-latency-histograms-fixtures.json). Sequences are
//! pure integer arithmetic (bit-identical to Java); histograms use the
//! `hdrhistogram` crate, the Rust port of HdrHistogram, with the same
//! dynamic range and precision as the Java side — which is what makes the
//! percentile fixtures shareable. This crate contains no `unsafe` code.

use hdrhistogram::Histogram;

pub const LOWEST_DISCERNIBLE: u64 = 1;
pub const HIGHEST_TRACKABLE: u64 = 60_000_000_000;
pub const SIGNIFICANT_DIGITS: u8 = 3;

#[inline]
pub fn xorshift64(mut x: u64) -> u64 {
    x ^= x << 13;
    x ^= x >> 7;
    x ^= x << 17;
    x
}

/// Bimodal service time in nanoseconds: 95% fast 800–1199 ns, 5% slow
/// 40000–59999 ns.
pub fn bimodal_nanos(stream_value: u64) -> u64 {
    if stream_value % 100 < 95 {
        800 + stream_value % 400
    } else {
        40_000 + stream_value % 20_000
    }
}

pub fn bimodal_sequence(seed: u64, n: usize) -> Vec<u64> {
    let mut out = Vec::with_capacity(n);
    let mut x = seed;
    for _ in 0..n {
        x = xorshift64(x);
        out.push(bimodal_nanos(x));
    }
    out
}

/// The pause-injection dataset: +5 ms on every 1000th operation.
pub fn pause_injected_sequence(seed: u64, n: usize) -> Vec<u64> {
    let mut out = bimodal_sequence(seed, n);
    let mut i = 999;
    while i < n {
        out[i] += 5_000_000;
        i += 1000;
    }
    out
}

/// Wrapping checksum (checksum*31 + value) — fixture oracle.
pub fn checksum(values: &[u64]) -> u64 {
    let mut c: u64 = 0;
    for v in values {
        c = c.wrapping_mul(31).wrapping_add(*v);
    }
    c
}

/// Single-server FIFO response times under bursty arrivals (bursts of
/// `burst_size` every `burst_interval` ns; service from the bimodal
/// stream): response = completion − arrival.
pub fn burst_response_times(
    seed: u64,
    n: usize,
    burst_size: usize,
    burst_interval: u64,
) -> Vec<u64> {
    let service = bimodal_sequence(seed, n);
    let mut out = Vec::with_capacity(n);
    let mut prev_completion: u64 = 0;
    for (i, s) in service.iter().enumerate() {
        let arrival = (i / burst_size) as u64 * burst_interval;
        let start = arrival.max(prev_completion);
        let completion = start + s;
        out.push(completion - arrival);
        prev_completion = completion;
    }
    out
}

pub fn new_histogram() -> Histogram<u64> {
    Histogram::new_with_bounds(LOWEST_DISCERNIBLE, HIGHEST_TRACKABLE, SIGNIFICANT_DIGITS)
        .expect("fixed-range histogram")
}

/// Naive recording: one value per completed operation, service time only.
pub fn record_all(values: &[u64]) -> Histogram<u64> {
    let mut h = new_histogram();
    for v in values {
        h.record(*v).expect("value within fixed range");
    }
    h
}

/// Coordinated-omission-corrected recording (HdrHistogram's standard
/// expected-interval backfill).
pub fn record_corrected(values: &[u64], expected_interval: u64) -> Histogram<u64> {
    let mut h = new_histogram();
    for v in values {
        h.record_correct(*v, expected_interval)
            .expect("value within fixed range");
    }
    h
}

/// Sampled recording: every `sample_every`-th value only.
pub fn record_sampled(values: &[u64], sample_every: usize) -> Histogram<u64> {
    let mut h = new_histogram();
    let mut i = 0;
    while i < values.len() {
        h.record(values[i]).expect("value within fixed range");
        i += sample_every;
    }
    h
}

/// Per-thread pattern: independent histograms merged by addition.
pub fn merge(a: &Histogram<u64>, b: &Histogram<u64>) -> Histogram<u64> {
    let mut out = new_histogram();
    out.add(a).expect("same configuration");
    out.add(b).expect("same configuration");
    out
}

/// The lab's canonical percentile snapshot.
#[derive(Debug, PartialEq, Eq)]
pub struct Percentiles {
    pub count: u64,
    pub p50: u64,
    pub p95: u64,
    pub p99: u64,
    pub p999: u64,
    pub max: u64,
}

impl Percentiles {
    pub fn of(h: &Histogram<u64>) -> Percentiles {
        Percentiles {
            count: h.len(),
            p50: h.value_at_percentile(50.0),
            p95: h.value_at_percentile(95.0),
            p99: h.value_at_percentile(99.0),
            p999: h.value_at_percentile(99.9),
            max: h.max(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn p(count: u64, p50: u64, p95: u64, p99: u64, p999: u64, max: u64) -> Percentiles {
        Percentiles {
            count,
            p50,
            p95,
            p99,
            p999,
            max,
        }
    }

    #[test]
    fn bimodal_sequence_matches_the_shared_fixture() {
        let seq = bimodal_sequence(42, 100_000);
        assert_eq!(checksum(&seq) as i64, 3641811140783620904);
        assert_eq!(&seq[..5], &[874, 1071, 954, 936, 962]);
    }

    #[test]
    fn pause_injected_sequence_matches_the_shared_fixture() {
        let seq = pause_injected_sequence(42, 100_000);
        assert_eq!(checksum(&seq) as i64, -3209529033418889176);
        assert!(seq[999] > 5_000_000 && seq[998] < 100_000);
    }

    #[test]
    fn full_recording_matches_the_percentile_fixture() {
        let h = record_all(&bimodal_sequence(42, 100_000));
        assert_eq!(
            Percentiles::of(&h),
            p(100_000, 1009, 1194, 55903, 59711, 59999)
        );
    }

    #[test]
    fn sampled_recording_matches_the_percentile_fixture() {
        let h = record_sampled(&bimodal_sequence(42, 100_000), 64);
        assert_eq!(
            Percentiles::of(&h),
            p(1563, 1008, 1193, 56799, 59807, 59999)
        );
    }

    #[test]
    fn coordinated_omission_correction_matches_the_fixture() {
        let paused = pause_injected_sequence(42, 100_000);
        let naive = Percentiles::of(&record_all(&paused));
        // p999 sits EXACTLY on the stall cliff (100 stalls in 100000
        // values): the Java and Rust HdrHistogram implementations resolve
        // that tie differently (Java: 59999 — below the cliff; Rust:
        // 5001215 — on it). A documented instrument difference
        // (fixtures.json), excluded from cross-language pinning; every
        // off-boundary percentile matches exactly.
        assert_eq!(naive, p(100_000, 1009, 1194, 56223, 5_001_215, 5_062_655));
        let corrected = Percentiles::of(&record_corrected(&paused, 1000));
        assert_eq!(
            corrected,
            p(837_236, 819_199, 4_587_519, 4_923_391, 4_997_119, 5_062_655)
        );
        assert!(corrected.p99 > naive.p99 * 50);
    }

    #[test]
    fn per_thread_histograms_merge_exactly() {
        let a = record_all(&bimodal_sequence(42, 50_000));
        let b = record_all(&bimodal_sequence(43, 50_000));
        let merged = Percentiles::of(&merge(&a, &b));
        assert_eq!(merged, p(100_000, 1009, 1194, 55903, 59711, 59999));
    }

    #[test]
    fn burst_response_times_separate_service_from_response() {
        let response = burst_response_times(42, 100_000, 10, 50_000);
        assert_eq!(checksum(&response) as i64, 8047843593492953431);
        let resp = Percentiles::of(&record_all(&response));
        assert_eq!(resp, p(100_000, 21023, 140_927, 223_615, 316_159, 377_599));
        let svc = Percentiles::of(&record_all(&bimodal_sequence(42, 100_000)));
        assert!(resp.p50 > svc.p50 * 10);
    }
}
