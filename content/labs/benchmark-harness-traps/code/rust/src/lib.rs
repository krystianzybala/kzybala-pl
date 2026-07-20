//! The four kernels measured by the "Benchmark Harness Traps" lab, defined
//! once and shared by every Criterion variant — trap and corrected
//! variants call exactly the same code, so any difference in reported cost
//! comes from the measurement harness, never from the work itself.
//!
//! Semantics are the cross-language equivalence contract
//! (../fixtures/benchmark-harness-traps-fixtures.json): all arithmetic is
//! 64-bit wrapping (`wrapping_*` == Java long overflow), xorshift shifts
//! are logical, and the Java implementations produce the identical fixture
//! values. This crate contains no `unsafe` code.

/// Repository-canonical xorshift64 step (same as the other labs).
#[inline]
pub fn xorshift64(mut x: u64) -> u64 {
    x ^= x << 13;
    x ^= x >> 7;
    x ^= x << 17;
    x
}

/// Tiny scalar operation: `rounds` xorshift64 steps from `seed`.
pub fn mix_scalar(seed: u64, rounds: u32) -> u64 {
    let mut x = seed;
    for _ in 0..rounds {
        x = xorshift64(x);
    }
    x
}

/// Deterministic dataset: the xorshift64 stream from `seed`.
pub fn fill_array(seed: u64, length: usize) -> Vec<u64> {
    let mut out = Vec::with_capacity(length);
    let mut x = seed;
    for _ in 0..length {
        x = xorshift64(x);
        out.push(x);
    }
    out
}

/// Array reduction: wrapping 64-bit sum.
pub fn reduce(values: &[u64]) -> u64 {
    let mut sum: u64 = 0;
    for v in values {
        sum = sum.wrapping_add(*v);
    }
    sum
}

/// Deterministic parser input: `count` decimal values
/// (stream value mod 1_000_000), comma-joined. Built once in setup — the
/// parser benchmarks reuse this input, they never rebuild it in the
/// measured operation.
pub fn build_parser_input(seed: u64, count: usize) -> String {
    let mut out = String::with_capacity(count * 7);
    let mut x = seed;
    for i in 0..count {
        x = xorshift64(x);
        if i > 0 {
            out.push(',');
        }
        out.push_str(&(x % 1_000_000).to_string());
    }
    out
}

/// Parses the comma-separated decimal input and folds it into a wrapping
/// checksum (`checksum*31 + value`); verifies the value count so a short or
/// corrupted parse cannot silently pass.
pub fn parse_checksum(input: &str, expected_count: usize) -> u64 {
    let mut checksum: u64 = 0;
    let mut count = 0usize;
    for field in input.split(',') {
        let mut value: u64 = 0;
        for b in field.bytes() {
            value = value * 10 + u64::from(b - b'0');
        }
        checksum = checksum.wrapping_mul(31).wrapping_add(value);
        count += 1;
    }
    assert_eq!(
        count, expected_count,
        "parsed {count} values, expected {expected_count}"
    );
    checksum
}

/// Stateful counter — the state-leakage dataset. Correct benchmarks reset
/// it per iteration (or declare the leakage); the trap is letting one
/// sample's state silently change the next sample's work.
pub struct StatefulCounter {
    state: u64,
}

impl StatefulCounter {
    pub fn new(seed: u64) -> StatefulCounter {
        StatefulCounter { state: seed }
    }

    /// One wrapping LCG step; returns the new state.
    pub fn advance(&mut self) -> u64 {
        self.state = self
            .state
            .wrapping_mul(6364136223846793005)
            .wrapping_add(1442695040888963407);
        self.state
    }

    pub fn state(&self) -> u64 {
        self.state
    }

    pub fn reset(&mut self, seed: u64) {
        self.state = seed;
    }
}

/// `steps` counter advances from `seed` — the fixture oracle.
pub fn counter_after(seed: u64, steps: u32) -> u64 {
    let mut counter = StatefulCounter::new(seed);
    let mut last = seed;
    for _ in 0..steps {
        last = counter.advance();
    }
    last
}

#[cfg(test)]
mod tests {
    use super::*;

    // Fixture values (../fixtures/benchmark-harness-traps-fixtures.json)
    // are stored as signed 64-bit decimals for Java parity; compare via
    // `as i64` on the bit-identical unsigned results.
    const SCALAR_MIXED: i64 = 2260733264014075113;
    const REDUCTION_SUM: i64 = 6622022393378204083;
    const PARSER_CHECKSUM: i64 = 1274698891203359752;
    const PARSER_INPUT_LENGTH: usize = 1760;
    const PARSER_INPUT_PREFIX: &str = "888327,51652,763743,795107,470850,165125";
    const COUNTER_FINAL: i64 = 206428032307178832;

    #[test]
    fn scalar_mix_matches_the_shared_fixture() {
        assert_eq!(mix_scalar(42, 1000) as i64, SCALAR_MIXED);
    }

    #[test]
    fn array_reduction_matches_the_shared_fixture() {
        let data = fill_array(42, 4096);
        assert_eq!(data.len(), 4096);
        assert_eq!(reduce(&data) as i64, REDUCTION_SUM);
    }

    #[test]
    fn parser_input_and_checksum_match_the_shared_fixture() {
        let input = build_parser_input(7, 256);
        assert_eq!(input.len(), PARSER_INPUT_LENGTH);
        assert!(input.starts_with(PARSER_INPUT_PREFIX), "{}", &input[..40]);
        assert_eq!(parse_checksum(&input, 256) as i64, PARSER_CHECKSUM);
    }

    #[test]
    #[should_panic(expected = "expected 255")]
    fn parser_rejects_a_wrong_value_count() {
        let input = build_parser_input(7, 256);
        parse_checksum(&input, 255);
    }

    #[test]
    fn stateful_counter_matches_the_shared_fixture() {
        assert_eq!(counter_after(0, 10000) as i64, COUNTER_FINAL);
    }

    #[test]
    fn stateful_counter_reset_prevents_state_leakage_between_runs() {
        let mut counter = StatefulCounter::new(0);
        let mut first = 0;
        for _ in 0..10000 {
            first = counter.advance();
        }
        // the LEAK: running again without reset continues from mutated state
        let mut leaked = 0;
        for _ in 0..10000 {
            leaked = counter.advance();
        }
        // the CORRECTED form: reset restores the fixture result exactly
        counter.reset(0);
        let mut reset_run = 0;
        for _ in 0..10000 {
            reset_run = counter.advance();
        }
        assert_eq!(first as i64, COUNTER_FINAL);
        assert_eq!(reset_run as i64, COUNTER_FINAL);
        assert_ne!(
            leaked, first,
            "a leaked-state run must diverge from the fixture"
        );
    }
}
