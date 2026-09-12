//! Deterministic dataset generation and the two accumulation techniques
//! (branchy, branchless) for the "Branch prediction and data distribution"
//! lab — the cross-language equivalence contract
//! (../fixtures/branch-prediction-fixtures.json). One xorshift64 stream
//! (pure integer arithmetic, bit-identical to the Java side) drives three
//! datasets and four variants that vary only the arrangement of that data
//! or the technique used to consume it, never the underlying multiset.
//! This crate contains no `unsafe` code (the evidence binary's affinity
//! syscalls live in `src/bin/branch_evidence.rs`, isolated there).

pub const N: usize = 1_000_000;
pub const SEED: u64 = 42;

pub const BYTE_THRESHOLD: i64 = 128;
pub const BYTE_DOMAIN: i64 = 256;
pub const INT_THRESHOLD: i64 = 500_000;
pub const INT_DOMAIN: i64 = 1_000_000;
pub const PAYLOAD_DOMAIN: u64 = 1024;

#[inline]
pub fn xorshift64(mut x: u64) -> u64 {
    x ^= x << 13;
    x ^= x >> 7;
    x ^= x << 17;
    x
}

/// Wrapping checksum (checksum*31 + value) — fixture oracle.
pub fn checksum(values: &[i64]) -> i64 {
    let mut c: i64 = 0;
    for &v in values {
        c = c.wrapping_mul(31).wrapping_add(v);
    }
    c
}

/// A single value array generated so the predicate (value >= threshold) is
/// true with the given probability — drives byteFlags and intThresholds.
pub fn biased_values(
    bias_percent: u64,
    threshold: i64,
    domain: i64,
    seed: u64,
    n: usize,
) -> Vec<i64> {
    let span = (domain - threshold) as u64;
    let threshold_u = threshold as u64;
    let mut out = Vec::with_capacity(n);
    let mut x = seed;
    for _ in 0..n {
        x = xorshift64(x);
        let above = (x % 100) < bias_percent;
        let v = if above {
            threshold + (x % span) as i64
        } else {
            (x % threshold_u) as i64
        };
        out.push(v);
    }
    out
}

pub fn sorted_ascending(values: &[i64]) -> Vec<i64> {
    let mut copy = values.to_vec();
    copy.sort_unstable();
    copy
}

#[derive(Clone)]
pub struct Records {
    pub kinds: Vec<i64>,
    pub payloads: Vec<i64>,
}

/// kind (0/1) and payload derived from the same stream value at each index.
pub fn biased_records(bias_percent: u64, seed: u64, n: usize) -> Records {
    let mut kinds = Vec::with_capacity(n);
    let mut payloads = Vec::with_capacity(n);
    let mut x = seed;
    for _ in 0..n {
        x = xorshift64(x);
        kinds.push(if (x % 100) < bias_percent { 1 } else { 0 });
        payloads.push((x % PAYLOAD_DOMAIN) as i64);
    }
    Records { kinds, payloads }
}

/// Stable sort of (kind, payload) pairs by kind ascending — cold block then
/// hot block, payload order preserved within each block.
pub fn sorted_by_kind(r: &Records) -> Records {
    let mut idx: Vec<usize> = (0..r.kinds.len()).collect();
    idx.sort_by_key(|&i| r.kinds[i]); // stable
    Records {
        kinds: idx.iter().map(|&i| r.kinds[i]).collect(),
        payloads: idx.iter().map(|&i| r.payloads[i]).collect(),
    }
}

/// Branchy: an explicit conditional guards whether value contributes.
pub fn filtered_sum_branchy(values: &[i64], threshold: i64) -> i64 {
    let mut sum: i64 = 0;
    for &v in values {
        if v >= threshold {
            sum = sum.wrapping_add(v);
        }
    }
    sum
}

/// Branchless: pure arithmetic mask, no conditional or `if`/`match`
/// anywhere in the hot path — mirrors the Java `diff >>> 31 ^ 1` trick
/// exactly (computed on the 32-bit-equivalent range both languages share).
pub fn filtered_sum_branchless(values: &[i64], threshold: i64) -> i64 {
    let mut sum: i64 = 0;
    for &v in values {
        let diff = (v - threshold) as i32;
        let keep = ((diff as u32) >> 31) ^ 1;
        sum = sum.wrapping_add(v.wrapping_mul(keep as i64));
    }
    sum
}

pub fn filtered_sum_records_branchy(r: &Records) -> i64 {
    let mut sum: i64 = 0;
    for i in 0..r.kinds.len() {
        if r.kinds[i] == 1 {
            sum = sum.wrapping_add(r.payloads[i]);
        }
    }
    sum
}

/// Branchless record variant: kind is already a 0/1 multiplier.
pub fn filtered_sum_records_branchless(r: &Records) -> i64 {
    let mut sum: i64 = 0;
    for i in 0..r.kinds.len() {
        sum = sum.wrapping_add(r.payloads[i].wrapping_mul(r.kinds[i]));
    }
    sum
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn biased9010_byte_flags_matches_the_fixture() {
        let vals = biased_values(90, BYTE_THRESHOLD, BYTE_DOMAIN, SEED, N);
        assert_eq!(checksum(&vals), -2158142668497906325);
        assert_eq!(&vals[..5], &[170, 191, 250, 232, 130]);
        assert_eq!(filtered_sum_branchy(&vals, BYTE_THRESHOLD), 172388675);
    }

    #[test]
    fn biased9010_int_thresholds_matches_the_fixture() {
        let vals = biased_values(90, INT_THRESHOLD, INT_DOMAIN, SEED, N);
        assert_eq!(checksum(&vals), -4739254585402386741);
        assert_eq!(&vals[..5], &[805674, 905471, 820954, 629736, 584162]);
        assert_eq!(filtered_sum_branchy(&vals, INT_THRESHOLD), 675165658915);
    }

    #[test]
    fn biased9010_mixed_hot_cold_matches_the_fixture() {
        let r = biased_records(90, SEED, N);
        assert_eq!(checksum(&r.kinds), 2027598892701658837);
        assert_eq!(checksum(&r.payloads), -20623316227680661);
        assert_eq!(filtered_sum_records_branchy(&r), 460810947);
    }

    #[test]
    fn random5050_byte_flags_matches_the_fixture() {
        let vals = biased_values(50, BYTE_THRESHOLD, BYTE_DOMAIN, SEED, N);
        assert_eq!(checksum(&vals), -6276435397034852629);
        assert_eq!(&vals[..5], &[42, 63, 122, 232, 2]);
        assert_eq!(filtered_sum_branchy(&vals, BYTE_THRESHOLD), 95744342);
    }

    #[test]
    fn random5050_int_thresholds_matches_the_fixture() {
        let vals = biased_values(50, INT_THRESHOLD, INT_DOMAIN, SEED, N);
        assert_eq!(checksum(&vals), 2963978878734898219);
        assert_eq!(filtered_sum_branchy(&vals, INT_THRESHOLD), 374751514134);
    }

    #[test]
    fn random5050_mixed_hot_cold_matches_the_fixture() {
        let r = biased_records(50, SEED, N);
        assert_eq!(checksum(&r.kinds), 4589498116125369640u64 as i64);
        assert_eq!(filtered_sum_records_branchy(&r), 255808086);
    }

    #[test]
    fn sorted_byte_flags_preserves_filtered_sum_but_changes_arrangement() {
        let random = biased_values(50, BYTE_THRESHOLD, BYTE_DOMAIN, SEED, N);
        let sorted = sorted_ascending(&random);
        assert_eq!(&sorted[..5], &[0, 0, 0, 0, 0]);
        assert_eq!(checksum(&sorted), -5030257529232730897);
        let sorted_sum = filtered_sum_branchy(&sorted, BYTE_THRESHOLD);
        let random_sum = filtered_sum_branchy(&random, BYTE_THRESHOLD);
        assert_eq!(sorted_sum, 95744342);
        assert_eq!(
            sorted_sum, random_sum,
            "sorting must not change the filtered sum"
        );
    }

    #[test]
    fn sorted_int_thresholds_preserves_filtered_sum() {
        let random = biased_values(50, INT_THRESHOLD, INT_DOMAIN, SEED, N);
        let sorted = sorted_ascending(&random);
        assert_eq!(&sorted[..5], &[50, 51, 52, 52, 52]);
        assert_eq!(
            filtered_sum_branchy(&random, INT_THRESHOLD),
            filtered_sum_branchy(&sorted, INT_THRESHOLD)
        );
    }

    #[test]
    fn sorted_mixed_hot_cold_preserves_filtered_sum() {
        let random = biased_records(50, SEED, N);
        let sorted = sorted_by_kind(&random);
        assert_eq!(&sorted.kinds[..5], &[0, 0, 0, 0, 0]);
        assert_eq!(checksum(&sorted.kinds), -2080036976520064992);
        assert_eq!(checksum(&sorted.payloads), -8076951700959590119);
        assert_eq!(
            filtered_sum_records_branchy(&random),
            filtered_sum_records_branchy(&sorted)
        );
    }

    #[test]
    fn branchless_matches_branchy_on_the_same_random5050_data() {
        let bytes = biased_values(50, BYTE_THRESHOLD, BYTE_DOMAIN, SEED, N);
        assert_eq!(
            filtered_sum_branchy(&bytes, BYTE_THRESHOLD),
            filtered_sum_branchless(&bytes, BYTE_THRESHOLD)
        );

        let ints = biased_values(50, INT_THRESHOLD, INT_DOMAIN, SEED, N);
        assert_eq!(
            filtered_sum_branchy(&ints, INT_THRESHOLD),
            filtered_sum_branchless(&ints, INT_THRESHOLD)
        );

        let records = biased_records(50, SEED, N);
        assert_eq!(
            filtered_sum_records_branchy(&records),
            filtered_sum_records_branchless(&records)
        );
    }

    #[test]
    fn branchless_mask_never_produces_a_third_value() {
        for v in [0i64, 1, 127, 128, 129, 255] {
            let diff = (v - 128) as i32;
            let keep = ((diff as u32) >> 31) ^ 1;
            assert!(keep == 0 || keep == 1);
            assert_eq!(keep, if v >= 128 { 1 } else { 0 });
        }
    }
}
