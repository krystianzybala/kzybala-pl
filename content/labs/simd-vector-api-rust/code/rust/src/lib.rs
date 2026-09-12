//! Deterministic source-data generation and the five variants — the
//! cross-language equivalence contract
//! (../fixtures/simd-vector-api-rust-fixtures.json). Every variant
//! reads the identical values and reproduces the identical result;
//! vectorization strategy changes ns/element and vector instructions,
//! never the result.
//!
//! Rust's stable toolchain has no portable SIMD API (`std::simd` is
//! nightly-only) and this lab adds no external SIMD crate dependency
//! (design.md: "no unrelated frameworks or dependencies when existing
//! repository mechanisms are sufficient") — so `explicit_simd` below
//! uses real, architecture-specific `std::arch` intrinsics behind a
//! safe function boundary: NEON (`std::arch::aarch64`) on aarch64, SSE2
//! (`std::arch::x86_64`) on x86_64 (SSE2 is part of every x86_64
//! target's guaranteed baseline — no runtime feature detection needed,
//! unlike AVX2/AVX-512), and a scalar fallback for any other
//! architecture. Every `unsafe` block is isolated inside one small,
//! documented function and covered directly by this crate's own
//! correctness tests (rust.md).

pub fn xorshift64(x: u64) -> u64 {
    let mut x = x;
    x ^= x << 13;
    x ^= x >> 7;
    x ^= x << 17;
    x
}

pub const SUM_MIN_MAX_N: usize = 1_000_000;
pub const THRESHOLD_N: usize = 1_000_000;
pub const THRESHOLD: i32 = 500_000;
pub const DOT_PRODUCT_N: usize = 1_000_000;
pub const BYTE_CLASSIFICATION_N: usize = 2_000_000;
pub const SMALL_TAIL_N: usize = 17;

pub fn generate_sum_min_max(n: usize) -> Vec<i32> {
    let mut value = Vec::with_capacity(n);
    let mut x: u64 = 120;
    for _ in 0..n {
        x = xorshift64(x);
        value.push((x % 1_000_000) as i32);
    }
    value
}

pub fn generate_threshold(n: usize) -> Vec<i32> {
    let mut value = Vec::with_capacity(n);
    let mut x: u64 = 121;
    for _ in 0..n {
        x = xorshift64(x);
        value.push((x % 1_000_000) as i32);
    }
    value
}

pub struct DotProductSource {
    pub a: Vec<f64>,
    pub b: Vec<f64>,
}

pub fn generate_dot_product(n: usize) -> DotProductSource {
    let mut a = Vec::with_capacity(n);
    let mut x: u64 = 122;
    for _ in 0..n {
        x = xorshift64(x);
        a.push((x % 1_000) as f64);
    }
    let mut b = Vec::with_capacity(n);
    let mut y: u64 = 123;
    for _ in 0..n {
        y = xorshift64(y);
        b.push((y % 1_000) as f64);
    }
    DotProductSource { a, b }
}

pub fn generate_byte_classification(n: usize) -> Vec<u8> {
    let mut value = Vec::with_capacity(n);
    let mut x: u64 = 124;
    for _ in 0..n {
        x = xorshift64(x);
        value.push((x % 256) as u8);
    }
    value
}

pub mod sum_min_max {

    #[derive(Debug, PartialEq, Eq, Clone, Copy)]
    pub struct SumMinMaxResult {
        pub sum: i64,
        pub min: i32,
        pub max: i32,
    }

    pub fn scalar_baseline(value: &[i32]) -> SumMinMaxResult {
        let mut sum: i64 = 0;
        let mut min = i32::MAX;
        let mut max = i32::MIN;
        for &v in value {
            sum += v as i64;
            if v < min {
                min = v;
            }
            if v > max {
                max = v;
            }
        }
        SumMinMaxResult { sum, min, max }
    }

    pub fn auto_vectorized_candidate(value: &[i32]) -> SumMinMaxResult {
        let mut sum: i64 = 0;
        let mut min = i32::MAX;
        let mut max = i32::MIN;
        for &v in value {
            sum += v as i64;
            min = min.min(v);
            max = max.max(v);
        }
        SumMinMaxResult { sum, min, max }
    }

    /// A real, humbling finding while building this lab: reducing a
    /// vector to a scalar every single iteration (`vaddvq_s32` inside
    /// the loop, called once per 4 elements) is slow enough that a
    /// first draft of this function measured SLOWER than LLVM's own
    /// auto-vectorization of the plain scalar loop — a horizontal
    /// reduce is a real, non-trivial operation, not "free" just because
    /// it is a single intrinsic call. The fix accumulates SUM in a
    /// vector register across iterations (min/max already did this) and
    /// reduces only every [`FLUSH_INTERVAL`] iterations — enough to stay
    /// within i32 range for this dataset's values, matching the
    /// identical overflow-avoidance fix the Java track needed
    /// (java.md/rust.md).
    #[cfg(target_arch = "aarch64")]
    unsafe fn explicit_simd_arch(value: &[i32]) -> SumMinMaxResult {
        use std::arch::aarch64::*;
        const FLUSH_INTERVAL: usize = 1000;
        let lanes = 4;
        let n = value.len();
        let bound = n - (n % lanes);
        let mut sum: i64 = 0;
        let mut sum_acc = vdupq_n_s32(0);
        let mut min_v = vdupq_n_s32(i32::MAX);
        let mut max_v = vdupq_n_s32(i32::MIN);
        let mut i = 0;
        let mut since_flush = 0;
        while i < bound {
            let v = vld1q_s32(value.as_ptr().add(i));
            sum_acc = vaddq_s32(sum_acc, v);
            min_v = vminq_s32(min_v, v);
            max_v = vmaxq_s32(max_v, v);
            i += lanes;
            since_flush += 1;
            if since_flush == FLUSH_INTERVAL {
                sum += vaddvq_s32(sum_acc) as i64;
                sum_acc = vdupq_n_s32(0);
                since_flush = 0;
            }
        }
        sum += vaddvq_s32(sum_acc) as i64;
        let mut min = vminvq_s32(min_v);
        let mut max = vmaxvq_s32(max_v);
        while i < n {
            let v = value[i];
            sum += v as i64;
            min = min.min(v);
            max = max.max(v);
            i += 1;
        }
        SumMinMaxResult { sum, min, max }
    }

    /// Standard SSE2 (guaranteed on every x86_64 target — no feature
    /// detection needed). Written to well-documented Intel intrinsics
    /// semantics; execution-verified by this crate's own test suite
    /// only on the native-Linux x86_64 publication host, not on this
    /// repository's (aarch64) development machine — disclosed
    /// explicitly (rust.md), matching this project's evidence-maturity
    /// discipline for anything not directly run here.
    #[cfg(target_arch = "x86_64")]
    unsafe fn explicit_simd_arch(value: &[i32]) -> SumMinMaxResult {
        use std::arch::x86_64::*;
        unsafe fn hsum(v: __m128i) -> i32 {
            let shuf = _mm_shuffle_epi32(v, 0b_11_10_11_10);
            let sums = _mm_add_epi32(v, shuf);
            let shuf2 = _mm_shuffle_epi32(sums, 0b_01_01_01_01);
            let sums2 = _mm_add_epi32(sums, shuf2);
            _mm_cvtsi128_si32(sums2)
        }
        unsafe fn select_min(a: __m128i, b: __m128i) -> __m128i {
            let mask = _mm_cmplt_epi32(a, b);
            _mm_or_si128(_mm_and_si128(mask, a), _mm_andnot_si128(mask, b))
        }
        unsafe fn select_max(a: __m128i, b: __m128i) -> __m128i {
            let mask = _mm_cmpgt_epi32(a, b);
            _mm_or_si128(_mm_and_si128(mask, a), _mm_andnot_si128(mask, b))
        }
        const FLUSH_INTERVAL: usize = 1000;
        let lanes = 4;
        let n = value.len();
        let bound = n - (n % lanes);
        let mut sum: i64 = 0;
        let mut sum_acc = _mm_setzero_si128();
        let mut min_v = _mm_set1_epi32(i32::MAX);
        let mut max_v = _mm_set1_epi32(i32::MIN);
        let mut i = 0;
        let mut since_flush = 0;
        while i < bound {
            let v = _mm_loadu_si128(value.as_ptr().add(i) as *const __m128i);
            sum_acc = _mm_add_epi32(sum_acc, v);
            min_v = select_min(min_v, v);
            max_v = select_max(max_v, v);
            i += lanes;
            since_flush += 1;
            if since_flush == FLUSH_INTERVAL {
                sum += hsum(sum_acc) as i64;
                sum_acc = _mm_setzero_si128();
                since_flush = 0;
            }
        }
        sum += hsum(sum_acc) as i64;
        let mut minbuf = [0i32; 4];
        let mut maxbuf = [0i32; 4];
        _mm_storeu_si128(minbuf.as_mut_ptr() as *mut __m128i, min_v);
        _mm_storeu_si128(maxbuf.as_mut_ptr() as *mut __m128i, max_v);
        let mut min = minbuf.into_iter().min().unwrap();
        let mut max = maxbuf.into_iter().max().unwrap();
        while i < n {
            let v = value[i];
            sum += v as i64;
            min = min.min(v);
            max = max.max(v);
            i += 1;
        }
        SumMinMaxResult { sum, min, max }
    }

    #[cfg(not(any(target_arch = "aarch64", target_arch = "x86_64")))]
    fn explicit_simd_arch(value: &[i32]) -> SumMinMaxResult {
        scalar_baseline(value)
    }

    pub fn explicit_simd(value: &[i32]) -> SumMinMaxResult {
        #[cfg(any(target_arch = "aarch64", target_arch = "x86_64"))]
        unsafe {
            explicit_simd_arch(value)
        }
        #[cfg(not(any(target_arch = "aarch64", target_arch = "x86_64")))]
        {
            explicit_simd_arch(value)
        }
    }
}

pub mod threshold_filter {

    pub fn scalar_baseline(value: &[i32], threshold: i32) -> u64 {
        let mut count = 0u64;
        for &v in value {
            if v > threshold {
                count += 1;
            }
        }
        count
    }

    pub fn auto_vectorized_candidate(value: &[i32], threshold: i32) -> u64 {
        let mut count = 0u64;
        for &v in value {
            count += (v > threshold) as u64;
        }
        count
    }

    #[cfg(target_arch = "aarch64")]
    unsafe fn explicit_simd_arch(value: &[i32], threshold: i32) -> u64 {
        use std::arch::aarch64::*;
        let lanes = 4;
        let n = value.len();
        let bound = n - (n % lanes);
        let mut count = 0u64;
        let tv = vdupq_n_s32(threshold);
        let mut i = 0;
        while i < bound {
            let v = vld1q_s32(value.as_ptr().add(i));
            let gt = vcgtq_s32(v, tv); // u32x4 mask, 0xFFFFFFFF or 0
            let ones = vandq_u32(gt, vdupq_n_u32(1));
            count += vaddvq_u32(ones) as u64;
            i += lanes;
        }
        while i < n {
            count += (value[i] > threshold) as u64;
            i += 1;
        }
        count
    }

    #[cfg(target_arch = "x86_64")]
    unsafe fn explicit_simd_arch(value: &[i32], threshold: i32) -> u64 {
        use std::arch::x86_64::*;
        let lanes = 4;
        let n = value.len();
        let bound = n - (n % lanes);
        let mut count = 0u64;
        let tv = _mm_set1_epi32(threshold);
        let mut i = 0;
        while i < bound {
            let v = _mm_loadu_si128(value.as_ptr().add(i) as *const __m128i);
            let gt = _mm_cmpgt_epi32(v, tv);
            let mask = _mm_movemask_ps(_mm_castsi128_ps(gt));
            count += (mask as u32).count_ones() as u64;
            i += lanes;
        }
        while i < n {
            count += (value[i] > threshold) as u64;
            i += 1;
        }
        count
    }

    #[cfg(not(any(target_arch = "aarch64", target_arch = "x86_64")))]
    fn explicit_simd_arch(value: &[i32], threshold: i32) -> u64 {
        scalar_baseline(value, threshold)
    }

    pub fn explicit_simd(value: &[i32], threshold: i32) -> u64 {
        #[cfg(any(target_arch = "aarch64", target_arch = "x86_64"))]
        unsafe {
            explicit_simd_arch(value, threshold)
        }
        #[cfg(not(any(target_arch = "aarch64", target_arch = "x86_64")))]
        {
            explicit_simd_arch(value, threshold)
        }
    }
}

pub mod dot_product {

    pub fn scalar_baseline(a: &[f64], b: &[f64]) -> f64 {
        let mut sum = 0.0;
        for i in 0..a.len() {
            sum += a[i] * b[i];
        }
        sum
    }

    pub fn auto_vectorized_candidate(a: &[f64], b: &[f64]) -> f64 {
        let mut sum = 0.0;
        for i in 0..a.len() {
            sum = a[i].mul_add(b[i], sum);
        }
        sum
    }

    #[cfg(target_arch = "aarch64")]
    unsafe fn explicit_simd_arch(a: &[f64], b: &[f64]) -> f64 {
        use std::arch::aarch64::*;
        let lanes = 2;
        let n = a.len();
        let bound = n - (n % lanes);
        let mut sum = 0.0f64;
        let mut i = 0;
        while i < bound {
            let va = vld1q_f64(a.as_ptr().add(i));
            let vb = vld1q_f64(b.as_ptr().add(i));
            sum += vaddvq_f64(vmulq_f64(va, vb));
            i += lanes;
        }
        while i < n {
            sum += a[i] * b[i];
            i += 1;
        }
        sum
    }

    #[cfg(target_arch = "x86_64")]
    unsafe fn explicit_simd_arch(a: &[f64], b: &[f64]) -> f64 {
        use std::arch::x86_64::*;
        let lanes = 2;
        let n = a.len();
        let bound = n - (n % lanes);
        let mut sum = 0.0f64;
        let mut i = 0;
        while i < bound {
            let va = _mm_loadu_pd(a.as_ptr().add(i));
            let vb = _mm_loadu_pd(b.as_ptr().add(i));
            let prod = _mm_mul_pd(va, vb);
            let mut buf = [0f64; 2];
            _mm_storeu_pd(buf.as_mut_ptr(), prod);
            sum += buf[0] + buf[1];
            i += lanes;
        }
        while i < n {
            sum += a[i] * b[i];
            i += 1;
        }
        sum
    }

    #[cfg(not(any(target_arch = "aarch64", target_arch = "x86_64")))]
    fn explicit_simd_arch(a: &[f64], b: &[f64]) -> f64 {
        scalar_baseline(a, b)
    }

    pub fn explicit_simd(a: &[f64], b: &[f64]) -> f64 {
        #[cfg(any(target_arch = "aarch64", target_arch = "x86_64"))]
        unsafe {
            explicit_simd_arch(a, b)
        }
        #[cfg(not(any(target_arch = "aarch64", target_arch = "x86_64")))]
        {
            explicit_simd_arch(a, b)
        }
    }
}

pub mod byte_classification {

    const LOW: u8 = 0x20;
    const HIGH: u8 = 0x7E;

    pub fn scalar_baseline(value: &[u8]) -> u64 {
        let mut count = 0u64;
        for &v in value {
            if (LOW..=HIGH).contains(&v) {
                count += 1;
            }
        }
        count
    }

    pub fn auto_vectorized_candidate(value: &[u8]) -> u64 {
        let mut count = 0u64;
        for &v in value {
            count += (LOW..=HIGH).contains(&v) as u64;
        }
        count
    }

    #[cfg(target_arch = "aarch64")]
    unsafe fn explicit_simd_arch(value: &[u8]) -> u64 {
        use std::arch::aarch64::*;
        let lanes = 16;
        let n = value.len();
        let bound = n - (n % lanes);
        let mut count = 0u64;
        let low = vdupq_n_u8(LOW);
        let high = vdupq_n_u8(HIGH);
        let mut i = 0;
        while i < bound {
            let v = vld1q_u8(value.as_ptr().add(i));
            let ge = vcgeq_u8(v, low);
            let le = vcleq_u8(v, high);
            let both = vandq_u8(vandq_u8(ge, le), vdupq_n_u8(1));
            count += vaddvq_u8(both) as u64;
            i += lanes;
        }
        while i < n {
            count += (value[i] >= LOW && value[i] <= HIGH) as u64;
            i += 1;
        }
        count
    }

    #[cfg(target_arch = "x86_64")]
    unsafe fn explicit_simd_arch(value: &[u8]) -> u64 {
        use std::arch::x86_64::*;
        let lanes = 16;
        let n = value.len();
        let bound = n - (n % lanes);
        let mut count = 0u64;
        let low = _mm_set1_epi8(LOW as i8);
        let high = _mm_set1_epi8(HIGH as i8);
        let mut i = 0;
        while i < bound {
            let v = _mm_loadu_si128(value.as_ptr().add(i) as *const __m128i);
            // Unsigned range check via unsigned min/max (SSE2 epu8, guaranteed baseline).
            let ge = _mm_cmpeq_epi8(_mm_max_epu8(v, low), v); // v >= low
            let le = _mm_cmpeq_epi8(_mm_min_epu8(v, high), v); // v <= high
            let both = _mm_and_si128(ge, le);
            let mask = _mm_movemask_epi8(both);
            count += (mask as u32).count_ones() as u64;
            i += lanes;
        }
        while i < n {
            count += (value[i] >= LOW && value[i] <= HIGH) as u64;
            i += 1;
        }
        count
    }

    #[cfg(not(any(target_arch = "aarch64", target_arch = "x86_64")))]
    fn explicit_simd_arch(value: &[u8]) -> u64 {
        scalar_baseline(value)
    }

    pub fn explicit_simd(value: &[u8]) -> u64 {
        #[cfg(any(target_arch = "aarch64", target_arch = "x86_64"))]
        unsafe {
            explicit_simd_arch(value)
        }
        #[cfg(not(any(target_arch = "aarch64", target_arch = "x86_64")))]
        {
            explicit_simd_arch(value)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sum_min_max_all_variants_agree() {
        let value = generate_sum_min_max(SUM_MIN_MAX_N);
        assert_eq!(value[0], 371320);

        let expected = sum_min_max::scalar_baseline(&value);
        assert_eq!(expected.sum, 500_149_865_068);
        assert_eq!(expected.min, 0);
        assert_eq!(expected.max, 999999);

        assert_eq!(sum_min_max::auto_vectorized_candidate(&value), expected);
        assert_eq!(sum_min_max::explicit_simd(&value), expected);

        let expected_offset = sum_min_max::scalar_baseline(&value[1..]);
        assert_eq!(sum_min_max::explicit_simd(&value[1..]), expected_offset);

        let expected_small = sum_min_max::scalar_baseline(&value[..SMALL_TAIL_N]);
        assert_eq!(
            sum_min_max::explicit_simd(&value[..SMALL_TAIL_N]),
            expected_small
        );
    }

    #[test]
    fn threshold_filter_all_variants_agree() {
        let value = generate_threshold(THRESHOLD_N);
        assert_eq!(value[0], 601593);

        let expected = threshold_filter::scalar_baseline(&value, THRESHOLD);
        assert_eq!(expected, 501050);

        assert_eq!(
            threshold_filter::auto_vectorized_candidate(&value, THRESHOLD),
            expected
        );
        assert_eq!(threshold_filter::explicit_simd(&value, THRESHOLD), expected);

        let expected_offset = threshold_filter::scalar_baseline(&value[1..], THRESHOLD);
        assert_eq!(
            threshold_filter::explicit_simd(&value[1..], THRESHOLD),
            expected_offset
        );

        let expected_small = threshold_filter::scalar_baseline(&value[..SMALL_TAIL_N], THRESHOLD);
        assert_eq!(
            threshold_filter::explicit_simd(&value[..SMALL_TAIL_N], THRESHOLD),
            expected_small
        );
    }

    #[test]
    fn dot_product_all_variants_agree() {
        let s = generate_dot_product(DOT_PRODUCT_N);
        assert_eq!(s.a[0], 554.0);
        assert_eq!(s.b[0], 827.0);

        let expected = dot_product::scalar_baseline(&s.a, &s.b);
        assert_eq!(expected, 249_655_062_671.0);

        assert_eq!(dot_product::auto_vectorized_candidate(&s.a, &s.b), expected);
        assert_eq!(dot_product::explicit_simd(&s.a, &s.b), expected);

        let expected_offset = dot_product::scalar_baseline(&s.a[1..], &s.b[1..]);
        assert_eq!(
            dot_product::explicit_simd(&s.a[1..], &s.b[1..]),
            expected_offset
        );

        let expected_small =
            dot_product::scalar_baseline(&s.a[..SMALL_TAIL_N], &s.b[..SMALL_TAIL_N]);
        assert_eq!(
            dot_product::explicit_simd(&s.a[..SMALL_TAIL_N], &s.b[..SMALL_TAIL_N]),
            expected_small
        );
    }

    #[test]
    fn byte_classification_all_variants_agree() {
        let value = generate_byte_classification(BYTE_CLASSIFICATION_N);
        assert_eq!(value[0], 124);

        let expected = byte_classification::scalar_baseline(&value);
        assert_eq!(expected, 741505);

        assert_eq!(
            byte_classification::auto_vectorized_candidate(&value),
            expected
        );
        assert_eq!(byte_classification::explicit_simd(&value), expected);

        let expected_offset = byte_classification::scalar_baseline(&value[1..]);
        assert_eq!(
            byte_classification::explicit_simd(&value[1..]),
            expected_offset
        );

        let expected_small = byte_classification::scalar_baseline(&value[..SMALL_TAIL_N]);
        assert_eq!(
            byte_classification::explicit_simd(&value[..SMALL_TAIL_N]),
            expected_small
        );
    }
}
