//! Deterministic traversal-order generation and the four variants
//! (sequential scan, random permutation, pointer chasing, blocked/tiled
//! traversal) for the "Cache Locality and Working-Set Size" lab — the
//! cross-language equivalence contract
//! (../fixtures/cache-locality-working-set-fixtures.json). `values[i] =
//! i` for every dataset (no RNG needed for the payload); only the
//! traversal ORDER is randomized. Every variant sums to the identical
//! `n*(n-1)/2` — order changes cost, never the total. No `unsafe`
//! anywhere in this crate (the evidence binary's affinity syscalls are
//! isolated in `src/bin/cache_locality_evidence.rs`).

#[inline]
pub fn xorshift64(mut x: u64) -> u64 {
    x ^= x << 13;
    x ^= x >> 7;
    x ^= x << 17;
    x
}

pub fn expected_total(n: usize) -> i64 {
    (n as i64) * ((n as i64) - 1) / 2
}

/// Fisher-Yates: a genuine random permutation of `[0, n)` (self-swaps
/// allowed, `j` inclusive of `i`).
pub fn random_permutation(seed: u64, n: usize) -> Vec<i64> {
    let mut perm: Vec<i64> = (0..n as i64).collect();
    let mut state = seed.max(1);
    for i in (1..n).rev() {
        state = xorshift64(state);
        let j = (state % (i as u64 + 1)) as usize;
        perm.swap(i, j);
    }
    perm
}

/// Sattolo's algorithm: a single n-cycle (`j` EXCLUDES `i`, unlike
/// Fisher-Yates) — identical algorithm to content/labs/cache-hierarchy's
/// `random_cycle`.
pub fn sattolo_next(seed: u64, n: usize) -> Vec<i64> {
    let mut perm: Vec<i64> = (0..n as i64).collect();
    let mut state = seed.max(1);
    for i in (1..n).rev() {
        state = xorshift64(state);
        let j = (state % i as u64) as usize;
        perm.swap(i, j);
    }
    let mut next = vec![0i64; n];
    for i in 0..n {
        next[perm[i] as usize] = perm[(i + 1) % n];
    }
    next
}

pub fn checksum(values: &[i64]) -> i64 {
    let mut c: i64 = 0;
    for &v in values {
        c = c.wrapping_mul(31).wrapping_add(v);
    }
    c
}

/// Follows `next` from index 0 for `next.len()` steps.
pub fn traversal_checksum(next: &[i64]) -> i64 {
    let mut checksum: i64 = 0;
    let mut idx: i64 = 0;
    for _ in 0..next.len() {
        idx = next[idx as usize];
        checksum = checksum.wrapping_mul(31).wrapping_add(idx);
    }
    checksum
}

// --- the four operations: each sums values[i]=i in a different order ---

/// One operation = one full pass, index 0..n-1 in natural order.
pub fn sum_sequential(n: usize) -> i64 {
    let mut sum: i64 = 0;
    for i in 0..n as i64 {
        sum = sum.wrapping_add(i);
    }
    sum
}

/// One operation = one full pass following a random permutation.
pub fn sum_permuted(perm: &[i64]) -> i64 {
    let mut sum: i64 = 0;
    for &idx in perm {
        sum = sum.wrapping_add(idx);
    }
    sum
}

/// One operation = one full dependent pointer-chase pass.
pub fn sum_pointer_chase(next: &[i64]) -> i64 {
    let mut sum: i64 = 0;
    let mut idx: i64 = 0;
    for _ in 0..next.len() {
        idx = next[idx as usize];
        sum = sum.wrapping_add(idx);
    }
    sum
}

/// One operation = one full tiled column-major pass over a `side x side`
/// row-major matrix, decomposed into `block_size x block_size` tiles.
pub fn sum_blocked(side: usize, block_size: usize) -> i64 {
    let mut sum: i64 = 0;
    let mut bc = 0;
    while bc < side {
        let c_end = (bc + block_size).min(side);
        let mut br = 0;
        while br < side {
            let r_end = (br + block_size).min(side);
            for c in bc..c_end {
                for r in br..r_end {
                    sum = sum.wrapping_add((r * side + c) as i64);
                }
            }
            br += block_size;
        }
        bc += block_size;
    }
    sum
}

#[cfg(test)]
mod tests {
    use super::*;

    const N: usize = 1_000_000;
    const SEED: u64 = 42;

    #[test]
    fn random_permutation_matches_the_fixture() {
        let perm = random_permutation(SEED, N);
        assert_eq!(&perm[..5], &[712069, 917377, 307023, 717218, 274070]);
        assert_eq!(checksum(&perm), -4372133131104027544);
        assert_eq!(sum_permuted(&perm), 499999500000);
    }

    #[test]
    fn sattolo_next_matches_the_fixture() {
        let next = sattolo_next(SEED, N);
        assert_eq!(&next[..5], &[916089, 366385, 207944, 432012, 867535]);
        assert_eq!(traversal_checksum(&next), -6982732617436105870);
        assert_eq!(sum_pointer_chase(&next), 499999500000);
    }

    #[test]
    fn sattolo_next_is_a_single_cycle_visiting_every_element_exactly_once() {
        let next = sattolo_next(SEED, N);
        let mut seen = vec![false; N];
        let mut idx: i64 = 0;
        for _ in 0..N {
            idx = next[idx as usize];
            assert!(
                !seen[idx as usize],
                "index {idx} visited twice before the cycle closed"
            );
            seen[idx as usize] = true;
        }
        assert_eq!(
            idx, 0,
            "the cycle must return to the start after exactly n steps"
        );
    }

    #[test]
    fn sequential_sum_matches_the_fixture() {
        assert_eq!(sum_sequential(N), 499999500000);
    }

    #[test]
    fn blocked_traversal_matches_the_fixture() {
        assert_eq!(sum_blocked(1_000, 32), 499999500000);
    }

    #[test]
    fn all_four_variants_agree_on_the_total() {
        let expected = expected_total(N);
        assert_eq!(expected, 499999500000);
        assert_eq!(sum_sequential(N), expected);
        assert_eq!(sum_permuted(&random_permutation(SEED, N)), expected);
        assert_eq!(sum_pointer_chase(&sattolo_next(SEED, N)), expected);
        assert_eq!(sum_blocked(1_000, 32), expected);
    }
}
