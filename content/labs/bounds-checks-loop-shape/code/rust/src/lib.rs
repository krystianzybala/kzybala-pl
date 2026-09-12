//! The five variants (canonical indexed loop, opaque limit provider,
//! irregular index array, safe iterator, isolated unchecked access) over
//! three datasets (primitive arrays, slices/subranges, strided access)
//! for the "Bounds checks and loop shape" lab — the cross-language
//! equivalence contract
//! (../fixtures/bounds-checks-loop-shape-fixtures.json). Every backing
//! array holds `backing[i] = i`; the permutation is the IDENTICAL
//! Fisher-Yates construction already established in
//! content/labs/cache-locality-working-set (same seed, same algorithm).
//!
//! The only `unsafe` in this crate is [`unchecked::sum_unchecked`],
//! isolated in its own module and covered by the same correctness
//! oracle as every safe variant — see that module's doc comment for the
//! specific invariant that makes it sound.

pub const N: usize = 1_000_000;
pub const SEED: u64 = 42;
pub const STRIDE: usize = 4;
pub const SLICE_START: usize = 500_000;

#[inline]
pub fn xorshift64(mut x: u64) -> u64 {
    x ^= x << 13;
    x ^= x >> 7;
    x ^= x << 17;
    x
}

pub fn build_backing(length: usize) -> Vec<i32> {
    (0..length as i32).collect()
}

/// Fisher-Yates: identical to content/labs/cache-locality-working-set's `random_permutation`.
pub fn random_permutation(seed: u64, n: usize) -> Vec<usize> {
    let mut perm: Vec<usize> = (0..n).collect();
    let mut state = seed.max(1);
    for i in (1..n).rev() {
        state = xorshift64(state);
        let j = (state % (i as u64 + 1)) as usize;
        perm.swap(i, j);
    }
    perm
}

pub fn expected_total(dataset: &str) -> i64 {
    let n = N as i64;
    match dataset {
        "primitiveArrays" => n * (n - 1) / 2,
        "slicesSubranges" => {
            let a = SLICE_START as i64;
            let b = a + n - 1;
            (a + b) * n / 2
        }
        "stridedAccess" => STRIDE as i64 * n * (n - 1) / 2,
        other => panic!("unknown dataset: {other}"),
    }
}

/// Opaque length provider: `#[inline(never)]` is Rust's direct
/// counterpart to the Java side's `@CompilerControl(DONT_INLINE)` — an
/// explicit optimization barrier so LLVM cannot see through the call and
/// prove the returned bound relates to the slice's own length.
#[inline(never)]
pub fn opaque_len(backing: &[i32]) -> usize {
    std::hint::black_box(backing.len())
}

#[inline(never)]
fn opaque_usize(value: usize) -> usize {
    std::hint::black_box(value)
}

// --- primitiveArrays: backing.len() == N -------------------------------

// clippy::needless_range_loop would rewrite these into the same shape as
// `primitive_safe_iterator` below — exactly the distinction this lab
// measures. An explicit index loop is the point, not an oversight.
#[allow(clippy::needless_range_loop)]
pub fn primitive_canonical(backing: &[i32]) -> i64 {
    let mut sum: i64 = 0;
    for i in 0..backing.len() {
        sum += backing[i] as i64;
    }
    sum
}

#[allow(clippy::needless_range_loop)]
pub fn primitive_opaque_limit(backing: &[i32]) -> i64 {
    let n = opaque_len(backing);
    let mut sum: i64 = 0;
    for i in 0..n {
        sum += backing[i] as i64;
    }
    sum
}

pub fn primitive_irregular_index(backing: &[i32], perm: &[usize]) -> i64 {
    let mut sum: i64 = 0;
    for &idx in perm {
        sum += backing[idx] as i64;
    }
    sum
}

pub fn primitive_safe_iterator(backing: &[i32]) -> i64 {
    backing.iter().map(|&v| v as i64).sum()
}

// --- slicesSubranges: backing.len() == 2N, window [sliceStart, sliceStart+N) ---
// Rust builds a genuine, zero-copy `&[i32]` slice for canonical/opaque/
// safeIterator; irregularIndex indexes the original backing with an
// offset (its access pattern isn't a contiguous range, so there is no
// single slice to borrow). See rust.md's equivalence-contract note for
// why Java's safeIterator instead receives a pre-copied sub-array.

// See the primitiveArrays section above: an explicit index loop here is
// the point (distinguishing "canonical"/"opaqueLimit" from
// "safeIterator"), not a style oversight clippy should simplify away.
#[allow(clippy::needless_range_loop)]
pub fn slice_canonical(backing: &[i32], start: usize, end: usize) -> i64 {
    let slice = &backing[start..end];
    let mut sum: i64 = 0;
    for i in 0..slice.len() {
        sum += slice[i] as i64;
    }
    sum
}

#[allow(clippy::needless_range_loop)]
pub fn slice_opaque_limit(backing: &[i32], start: usize, end: usize) -> i64 {
    let s = opaque_usize(start);
    let e = opaque_usize(end);
    let slice = &backing[s..e];
    let mut sum: i64 = 0;
    for i in 0..slice.len() {
        sum += slice[i] as i64;
    }
    sum
}

pub fn slice_irregular_index(backing: &[i32], perm: &[usize], slice_start: usize) -> i64 {
    let mut sum: i64 = 0;
    for &idx in perm {
        sum += backing[slice_start + idx] as i64;
    }
    sum
}

pub fn slice_safe_iterator(backing: &[i32], start: usize, end: usize) -> i64 {
    backing[start..end].iter().map(|&v| v as i64).sum()
}

// --- stridedAccess: backing.len() == N*stride, visit backing[i*stride] ---

pub fn strided_canonical(backing: &[i32], n: usize, stride: usize) -> i64 {
    let mut sum: i64 = 0;
    for i in 0..n {
        sum += backing[i * stride] as i64;
    }
    sum
}

pub fn strided_opaque_limit(backing: &[i32], n: usize, stride: usize) -> i64 {
    let limit = opaque_usize(n);
    let mut sum: i64 = 0;
    for i in 0..limit {
        sum += backing[i * stride] as i64;
    }
    sum
}

pub fn strided_irregular_index(backing: &[i32], perm: &[usize], stride: usize) -> i64 {
    let mut sum: i64 = 0;
    for &idx in perm {
        sum += backing[idx * stride] as i64;
    }
    sum
}

/// Rust's `step_by` is a built-in, safe iterator adapter for exactly this
/// access pattern — no manual index arithmetic needed, unlike the Java
/// side (which has no native strided-array iterator and falls back to
/// `IntStream`; see java.md).
pub fn strided_safe_iterator(backing: &[i32], n: usize, stride: usize) -> i64 {
    backing
        .iter()
        .step_by(stride)
        .take(n)
        .map(|&v| v as i64)
        .sum()
}

/// Isolated unchecked access — the only `unsafe` in this crate.
pub mod unchecked {
    /// Sums `n` elements at `indices[i]` (or `i*stride` for the strided
    /// caller) via `get_unchecked`, skipping bounds checks entirely.
    ///
    /// # Safety
    /// Every index this function reads must be `< backing.len()`. Callers
    /// in this lab satisfy that by construction (indices come from
    /// `0..backing.len()`, a permutation of that range, or `i*stride`
    /// where `n*stride <= backing.len()` — each checked once, cheaply, in
    /// the caller's setup, never per-element inside this function). This
    /// function performs NO validation itself — that is the isolation
    /// this lab's traps section (benchmark.md) requires: unsafe code must
    /// be narrow, and its invariant must be stated, not assumed.
    pub unsafe fn sum_unchecked(backing: &[i32], indices: impl Iterator<Item = usize>) -> i64 {
        let mut sum: i64 = 0;
        for idx in indices {
            sum += *backing.get_unchecked(idx) as i64;
        }
        sum
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn random_permutation_matches_the_cache_locality_fixture() {
        let perm = random_permutation(SEED, N);
        assert_eq!(&perm[..5], &[712069, 917377, 307023, 717218, 274070]);
        let sum: i64 = perm.iter().map(|&v| v as i64).sum();
        assert_eq!(sum, 499999500000);
    }

    #[test]
    fn primitive_arrays_all_five_variants_agree() {
        let backing = build_backing(N);
        let perm = random_permutation(SEED, N);
        let expected = expected_total("primitiveArrays");
        assert_eq!(expected, 499999500000);

        assert_eq!(primitive_canonical(&backing), expected);
        assert_eq!(primitive_opaque_limit(&backing), expected);
        assert_eq!(primitive_irregular_index(&backing, &perm), expected);
        assert_eq!(primitive_safe_iterator(&backing), expected);
        let actual_unchecked = unsafe { unchecked::sum_unchecked(&backing, 0..backing.len()) };
        assert_eq!(actual_unchecked, expected);
    }

    #[test]
    fn slices_subranges_all_five_variants_agree() {
        let backing = build_backing(2 * N);
        let perm = random_permutation(SEED, N);
        let expected = expected_total("slicesSubranges");
        assert_eq!(expected, 999999500000);
        let end = SLICE_START + N;

        assert_eq!(slice_canonical(&backing, SLICE_START, end), expected);
        assert_eq!(slice_opaque_limit(&backing, SLICE_START, end), expected);
        assert_eq!(
            slice_irregular_index(&backing, &perm, SLICE_START),
            expected
        );
        assert_eq!(slice_safe_iterator(&backing, SLICE_START, end), expected);
        let actual_unchecked = unsafe { unchecked::sum_unchecked(&backing, SLICE_START..end) };
        assert_eq!(actual_unchecked, expected);
    }

    #[test]
    fn strided_access_all_five_variants_agree() {
        let backing = build_backing(N * STRIDE);
        let perm = random_permutation(SEED, N);
        let expected = expected_total("stridedAccess");
        assert_eq!(expected, 1999998000000);

        assert_eq!(strided_canonical(&backing, N, STRIDE), expected);
        assert_eq!(strided_opaque_limit(&backing, N, STRIDE), expected);
        assert_eq!(strided_irregular_index(&backing, &perm, STRIDE), expected);
        assert_eq!(strided_safe_iterator(&backing, N, STRIDE), expected);
        let actual_unchecked =
            unsafe { unchecked::sum_unchecked(&backing, (0..N).map(|i| i * STRIDE)) };
        assert_eq!(actual_unchecked, expected);
    }
}
