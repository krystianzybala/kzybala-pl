//! The five call-site-shape variants (monomorphic, bimorphic, megamorphic
//! `dyn` dispatch, manual enum `match` dispatch, an oversized/never-
//! inlined callee) over three datasets sharing one strategy pool — the
//! cross-language equivalence contract
//! (../fixtures/inlining-call-site-shape-fixtures.json). No `unsafe`
//! anywhere in this crate.
//!
//! **A real language-mechanism difference, not smoothed over** (this
//! lab's "comparing trait object to Java sealed dispatch as identical"
//! trap, benchmark.md): Java's C2 profiles a call site at runtime and
//! speculatively INLINES through it while it stays mono/bimorphic,
//! falling back to a vtable call only once it turns megamorphic. Rust's
//! `dyn Trait` is ALWAYS a vtable call — LLVM never inlines through it
//! regardless of how many concrete types a given `Box<dyn LongStrategy>`
//! slot happens to hold at runtime, because that decision is made at
//! compile time, not from a runtime type profile. A mono/bimorphic/
//! megamorphic cost gradient can still appear here — but if it does, it
//! comes from the CPU's own indirect-branch predictor (an architectural
//! effect, present in both languages once a call falls back to a vtable)
//! rather than from the compiler choosing to inline or not. Never
//! attribute a measured Rust gradient on this axis to "the compiler
//! optimized the monomorphic case" without the assembly evidence
//! (`cargo asm`) to back it up — for `dyn Trait`, it almost never did.

pub const N: usize = 1_000_000;

#[inline]
pub fn xorshift64(mut x: u64) -> u64 {
    x ^= x << 13;
    x ^= x >> 7;
    x ^= x << 17;
    x
}

/// pricingFunctions: input[i] = i — linear, no RNG.
pub fn pricing_functions(n: usize) -> Vec<i64> {
    (0..n as i64).collect()
}

/// codecStrategies: xorshift64 stream, seed=42, unsigned-mod 1_000_000.
pub fn codec_strategies(n: usize) -> Vec<i64> {
    stream(42, n)
}

/// validationRules: xorshift64 stream, seed=43, unsigned-mod 1_000_000.
pub fn validation_rules(n: usize) -> Vec<i64> {
    stream(43, n)
}

fn stream(seed: u64, n: usize) -> Vec<i64> {
    let mut out = Vec::with_capacity(n);
    let mut x = seed;
    for _ in 0..n {
        x = xorshift64(x);
        out.push((x % 1_000_000) as i64);
    }
    out
}

pub fn inputs_for(dataset: &str, n: usize) -> Vec<i64> {
    match dataset {
        "pricingFunctions" => pricing_functions(n),
        "codecStrategies" => codec_strategies(n),
        "validationRules" => validation_rules(n),
        other => panic!("unknown dataset: {other}"),
    }
}

// --- the shared strategy pool: six DISTINCT concrete types ---------------

pub trait LongStrategy {
    fn apply(&self, x: i64) -> i64;
}

pub struct AddOne;
impl LongStrategy for AddOne {
    fn apply(&self, x: i64) -> i64 {
        x + 1
    }
}

pub struct MulThree;
impl LongStrategy for MulThree {
    fn apply(&self, x: i64) -> i64 {
        x * 3
    }
}

pub struct XorMask;
impl LongStrategy for XorMask {
    fn apply(&self, x: i64) -> i64 {
        x ^ 0x5A5A
    }
}

pub struct ShiftAddSeven;
impl LongStrategy for ShiftAddSeven {
    fn apply(&self, x: i64) -> i64 {
        (x << 1) + 7
    }
}

pub struct SubEleven;
impl LongStrategy for SubEleven {
    fn apply(&self, x: i64) -> i64 {
        x - 11
    }
}

pub struct ShiftOrOne;
impl LongStrategy for ShiftOrOne {
    fn apply(&self, x: i64) -> i64 {
        (x >> 2) | 1
    }
}

pub fn pool() -> [Box<dyn LongStrategy>; 6] {
    [
        Box::new(AddOne),
        Box::new(MulThree),
        Box::new(XorMask),
        Box::new(ShiftAddSeven),
        Box::new(SubEleven),
        Box::new(ShiftOrOne),
    ]
}

/// The manual switch/enum-dispatch alternative — the same six transforms,
/// selected by a closed `match`. LLVM compiles this to a jump table or a
/// chain of compares with no vtable at all, inlinable regardless of the
/// per-index mix — the direct counterpart of Java's `StrategyKind`.
#[derive(Clone, Copy)]
pub enum StrategyKind {
    AddOne,
    MulThree,
    XorMask,
    ShiftAddSeven,
    SubEleven,
    ShiftOrOne,
}

impl StrategyKind {
    pub fn for_index(i: usize) -> StrategyKind {
        match i {
            0 => StrategyKind::AddOne,
            1 => StrategyKind::MulThree,
            2 => StrategyKind::XorMask,
            3 => StrategyKind::ShiftAddSeven,
            4 => StrategyKind::SubEleven,
            5 => StrategyKind::ShiftOrOne,
            other => panic!("no strategy at index {other}"),
        }
    }

    pub fn apply(self, x: i64) -> i64 {
        match self {
            StrategyKind::AddOne => x + 1,
            StrategyKind::MulThree => x * 3,
            StrategyKind::XorMask => x ^ 0x5A5A,
            StrategyKind::ShiftAddSeven => (x << 1) + 7,
            StrategyKind::SubEleven => x - 11,
            StrategyKind::ShiftOrOne => (x >> 2) | 1,
        }
    }
}

// --- the five variants -----------------------------------------------

/// Monomorphic: the call site only ever sees `AddOne` — but because it
/// is still a `dyn` call, LLVM does not speculatively inline it the way
/// C2 would (see this module's doc comment).
pub fn sum_monomorphic(inputs: &[i64]) -> i64 {
    let strategy: Box<dyn LongStrategy> = Box::new(AddOne);
    let mut sum: i64 = 0;
    for &x in inputs {
        sum = sum.wrapping_add(strategy.apply(x));
    }
    sum
}

/// Bimorphic: alternates between exactly two concrete types.
pub fn sum_bimorphic(inputs: &[i64]) -> i64 {
    let pair: [Box<dyn LongStrategy>; 2] = [Box::new(AddOne), Box::new(MulThree)];
    let mut sum: i64 = 0;
    for (i, &x) in inputs.iter().enumerate() {
        sum = sum.wrapping_add(pair[i % 2].apply(x));
    }
    sum
}

/// Megamorphic: round-robins all six concrete types through one `dyn` call site.
pub fn sum_megamorphic(inputs: &[i64]) -> i64 {
    let pool = pool();
    let mut sum: i64 = 0;
    for (i, &x) in inputs.iter().enumerate() {
        sum = sum.wrapping_add(pool[i % 6].apply(x));
    }
    sum
}

/// The IDENTICAL six-way round-robin as [`sum_megamorphic`], via `match`
/// instead of `dyn` dispatch — no vtable to defeat in the first place.
pub fn sum_switch_dispatch(inputs: &[i64]) -> i64 {
    let mut sum: i64 = 0;
    for (i, &x) in inputs.iter().enumerate() {
        sum = sum.wrapping_add(StrategyKind::for_index(i % 6).apply(x));
    }
    sum
}

/// Rust exposes "never inline this" as an explicit, deterministic
/// attribute rather than an implicit bytecode-size threshold (the Java
/// side pads `bigAddOne`'s body past HotSpot's size heuristics instead —
/// see java.md). Same logical strategy as [`sum_monomorphic`] (`x + 1`),
/// forced never to inline.
#[inline(never)]
fn add_one_never_inline(x: i64) -> i64 {
    x + 1
}

pub fn sum_oversized_callee(inputs: &[i64]) -> i64 {
    let mut sum: i64 = 0;
    for &x in inputs {
        sum = sum.wrapping_add(add_one_never_inline(x));
    }
    sum
}

pub fn run(variant: &str, inputs: &[i64]) -> i64 {
    match variant {
        "monomorphic" => sum_monomorphic(inputs),
        "bimorphic" => sum_bimorphic(inputs),
        "megamorphic" => sum_megamorphic(inputs),
        "switchDispatch" => sum_switch_dispatch(inputs),
        "oversizedCallee" => sum_oversized_callee(inputs),
        other => panic!("unknown variant: {other}"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn assert_dataset(
        dataset: &str,
        first5: &[i64],
        mono_and_oversized: i64,
        bimorphic: i64,
        mega_and_switch: i64,
    ) {
        let inputs = inputs_for(dataset, N);
        assert_eq!(&inputs[..5], first5);

        let mono = sum_monomorphic(&inputs);
        let oversized = sum_oversized_callee(&inputs);
        assert_eq!(mono, mono_and_oversized);
        assert_eq!(mono, oversized);

        assert_eq!(sum_bimorphic(&inputs), bimorphic);

        let mega = sum_megamorphic(&inputs);
        let via_switch = sum_switch_dispatch(&inputs);
        assert_eq!(mega, mega_and_switch);
        assert_eq!(mega, via_switch);
    }

    #[test]
    fn pricing_functions_all_variants_agree() {
        assert_dataset(
            "pricingFunctions",
            &[0, 1, 2, 3, 4],
            500000500000,
            1000000000000,
            687543385141,
        );
    }

    #[test]
    fn codec_strategies_all_variants_agree() {
        assert_dataset(
            "codecStrategies",
            &[805674, 905471, 320954, 629736, 84162],
            499866710459,
            999276779457,
            687169279832,
        );
    }

    #[test]
    fn validation_rules_all_variants_agree() {
        assert_dataset(
            "validationRules",
            &[75435, 585150, 59539, 476429, 785191],
            499956129091,
            999456305265,
            687433423258,
        );
    }

    #[test]
    fn strategy_kind_matches_the_dyn_pool_for_every_index() {
        let pool = pool();
        for (i, strategy) in pool.iter().enumerate() {
            for &x in &[0i64, 1, 999_999, 123_456] {
                assert_eq!(strategy.apply(x), StrategyKind::for_index(i).apply(x));
            }
        }
    }
}
