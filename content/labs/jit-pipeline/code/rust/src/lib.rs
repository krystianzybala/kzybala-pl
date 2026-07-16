//! The shared pricing kernel, identically defined to the Java
//! `PricingKernel` (same xorshift64 input stream, same exact integer
//! math). The fixture `../fixtures/jit-pipeline-fixtures.json` pins the
//! totals both languages must produce — the semantic-equivalence oracle.
//! This crate exists as the ahead-of-time (AOT) baseline of the JIT
//! Pipeline lab: a SEPARATE scenario, never compared against JVM warm-up.

pub const INPUTS: usize = 1024;
pub const SEED: u64 = 7;

#[derive(Clone, Copy)]
pub enum Pricer {
    Basic,
    Discount,
    Surge,
}

impl Pricer {
    #[inline]
    pub fn price(self, amount: u64) -> u64 {
        match self {
            Pricer::Basic => amount * 100,
            Pricer::Discount => amount * 90 + 5,
            Pricer::Surge => amount * 150 - 3,
        }
    }
}

/// The fixed input stream: 1 + (xorshift64(seed) % 1000).
pub fn amounts() -> Vec<u64> {
    let mut state = SEED.max(1);
    (0..INPUTS)
        .map(|_| {
            state ^= state << 13;
            state ^= state >> 7;
            state ^= state << 17;
            1 + state % 1000
        })
        .collect()
}

/// One pass: total of pricer over every amount.
pub fn total(pricer: Pricer, amounts: &[u64]) -> u64 {
    amounts.iter().map(|&a| pricer.price(a)).sum()
}

/// One pass with a rotating pricer mix.
pub fn mixed_total(pricers: &[Pricer], amounts: &[u64]) -> u64 {
    amounts
        .iter()
        .enumerate()
        .map(|(i, &a)| pricers[i % pricers.len()].price(a))
        .sum()
}

#[cfg(test)]
mod tests {
    use super::*;

    // Shared fixture ../fixtures/jit-pipeline-fixtures.json — the Java
    // suite pins exactly the same values.
    #[test]
    fn input_stream_matches_the_shared_fixture() {
        let a = amounts();
        assert_eq!(&a[..3], &[328, 653, 744]);
    }

    #[test]
    fn totals_match_the_shared_fixture() {
        let a = amounts();
        assert_eq!(total(Pricer::Basic, &a), 50215100);
        assert_eq!(total(Pricer::Discount, &a), 45198710);
        assert_eq!(total(Pricer::Surge, &a), 75319578);
        assert_eq!(
            mixed_total(&[Pricer::Basic, Pricer::Discount, Pricer::Surge], &a),
            56392272
        );
        assert_eq!(
            mixed_total(&[Pricer::Basic, Pricer::Discount], &a),
            47716500
        );
    }
}
