//! Companion code for the Performance Lab "Cache hierarchy" lab
//! (kzybala.pl/lab/cache-hierarchy/). See `rust.md` next to this crate for
//! the full explanation of the pointer-chase technique used below.

/// A sequential cycle: 0 -> 1 -> 2 -> ... -> size-1 -> 0. Maximal spatial locality.
pub fn sequential_cycle(size: usize) -> Vec<u64> {
    (0..size).map(|i| ((i + 1) % size) as u64).collect()
}

/// A random single-cycle permutation built with Sattolo's algorithm. Plain
/// Fisher-Yates can produce several short sub-cycles, which would let a
/// pointer chase loop through only a small hot subset of the array —
/// Sattolo's algorithm guarantees exactly one cycle covering all `size`
/// elements.
pub fn random_cycle(size: usize, seed: u64) -> Vec<u64> {
    let mut perm: Vec<usize> = (0..size).collect();
    let mut state = seed.max(1); // xorshift64 needs a non-zero seed
    let mut next_rand = |bound: usize| -> usize {
        // xorshift64: a small, dependency-free PRNG — good enough for
        // building a benchmark fixture, not for anything security-sensitive.
        state ^= state << 13;
        state ^= state >> 7;
        state ^= state << 17;
        (state % bound as u64) as usize
    };
    for i in (1..size).rev() {
        // next_rand(i), not next_rand(i + 1) as in Fisher-Yates — excluding
        // self-swaps is what makes Sattolo's algorithm produce a single
        // cycle instead of a random permutation (usually several disjoint
        // cycles).
        let j = next_rand(i);
        perm.swap(i, j);
    }
    let mut next = vec![0u64; size];
    for i in 0..size {
        next[perm[i]] = perm[(i + 1) % size] as u64;
    }
    next
}

/// Traversal checksum: follows the cycle from index 0 for exactly
/// `next.len()` steps, mixing every visited index with wrapping arithmetic
/// — byte-identical to the Java `ChaseTables.traversalChecksum`, the
/// semantic-equivalence oracle proving both languages built the same table.
pub fn traversal_checksum(next: &[u64]) -> i64 {
    let mut checksum: i64 = 0;
    let mut idx: u64 = 0;
    for _ in 0..next.len() {
        idx = next[idx as usize];
        checksum = checksum.wrapping_mul(31).wrapping_add(idx as i64);
    }
    checksum
}

#[cfg(test)]
mod tests {
    use super::*;

    // Confirms `next[]` is a single N-cycle: following it from 0 visits
    // every index exactly once before returning to 0.
    fn assert_single_cycle(next: &[u64]) {
        let size = next.len();
        let mut visited = vec![false; size];
        let mut idx = 0usize;
        for _ in 0..size {
            assert!(
                !visited[idx],
                "cycle revisited index {idx} before covering all {size} elements"
            );
            visited[idx] = true;
            idx = next[idx] as usize;
        }
        assert_eq!(
            idx, 0,
            "cycle did not return to the start after visiting all elements"
        );
        assert!(
            visited.iter().all(|&v| v),
            "cycle did not cover every element"
        );
    }

    #[test]
    fn sequential_cycle_is_a_single_cycle() {
        assert_single_cycle(&sequential_cycle(64));
    }

    // Shared fixture ../fixtures/cache-hierarchy-fixtures.json — the Java
    // suite pins exactly the same values (identical xorshift64 + Sattolo),
    // proving byte-identical datasets across languages.
    #[test]
    fn traversal_checksums_match_the_shared_cross_language_fixture() {
        assert_eq!(
            traversal_checksum(&random_cycle(2048, 42)),
            8738039620073195968
        );
        assert_eq!(
            traversal_checksum(&sequential_cycle(2048)),
            6272464722101566464
        );
        assert_eq!(
            traversal_checksum(&random_cycle(12345, 42)),
            -7097521173149448694
        );
        assert_eq!(
            traversal_checksum(&sequential_cycle(12345)),
            6737410350348517348
        );
        assert_eq!(
            traversal_checksum(&random_cycle(2048, 7)),
            2077544042594837246
        );
    }

    #[test]
    fn random_cycle_is_a_single_cycle() {
        assert_single_cycle(&random_cycle(64, 42));
        assert_single_cycle(&random_cycle(1000, 7));
    }

    #[test]
    fn random_cycle_differs_from_sequential_order() {
        let seq = sequential_cycle(64);
        let rand = random_cycle(64, 42);
        assert_ne!(seq, rand);
    }
}
