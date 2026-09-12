//! Fixed constants and the deterministic sum used by every variant — the
//! cross-language equivalence contract
//! (../fixtures/safepoints-ttsp-fixtures.json). The actual stop-the-world
//! coordinator lives in `src/bin/safepoint_coordinator.rs` — see that
//! file's doc comment for why Rust has no runtime equivalent to hand to
//! the compiler here: there is no JIT-inserted polling, no safepoint,
//! nothing to measure until you build coordination explicitly, which is
//! precisely this lab's Rust-track focus ("Stop-the-world coordination
//! implemented explicitly as a contrast").

pub const TOTAL_ITERATIONS: u64 = 400_000_000;
pub const CHUNK_SIZE: u64 = 50_000_000;
pub const NUM_CHUNKS: u64 = 8;
pub const FLEET_SIZE: u64 = 16;
pub const PER_FLEET_WORKER_ITERATIONS: u64 = TOTAL_ITERATIONS / FLEET_SIZE;
pub const IDLE_THREAD_COUNT: usize = 300;
pub const NATIVE_SLEEP_MICROS: u32 = 50_000;
pub const EXPECTED_TOTAL: u64 = 79_999_999_800_000_000;

/// sum(i) for i in [start, start+count), 64-bit wrapping.
pub fn sum_range(start: u64, count: u64) -> u64 {
    let mut sum: u64 = 0;
    for i in start..start + count {
        sum = sum.wrapping_add(i);
    }
    sum
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn single_range_sums_to_the_expected_total() {
        assert_eq!(sum_range(0, TOTAL_ITERATIONS), EXPECTED_TOTAL);
    }

    #[test]
    fn fleet_partitioning_sums_to_the_identical_total() {
        let mut total: u64 = 0;
        for w in 0..FLEET_SIZE {
            total = total.wrapping_add(sum_range(
                w * PER_FLEET_WORKER_ITERATIONS,
                PER_FLEET_WORKER_ITERATIONS,
            ));
        }
        assert_eq!(total, EXPECTED_TOTAL);
    }

    #[test]
    fn chunked_summation_matches_the_whole_range_sum() {
        let mut total: u64 = 0;
        for c in 0..NUM_CHUNKS {
            total = total.wrapping_add(sum_range(c * CHUNK_SIZE, CHUNK_SIZE));
        }
        assert_eq!(NUM_CHUNKS * CHUNK_SIZE, TOTAL_ITERATIONS);
        assert_eq!(total, EXPECTED_TOTAL);
    }
}
