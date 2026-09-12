//! The five "speculative assumption" variants — mirrored from the Java
//! side for a shared correctness total — over three datasets, for the
//! "Deoptimization and uncommon traps" lab. No `unsafe` anywhere in this
//! crate.
//!
//! **A real language-mechanism difference, not smoothed over** (this
//! lab's "equating Rust branch misprediction with JVM deoptimization"
//! trap, benchmark.md): there is no runtime deoptimization mechanism in
//! Rust. Every dispatch decision here (`dyn Strategy`) is fixed at
//! **compile time** — LLVM never watches a call site's runtime type
//! history and never "assumed" a single type would keep appearing, so
//! there is no assumption for a later type to violate, and no
//! recompilation event to trigger. Where the Java side shows a genuine
//! latency spike at the moment its JIT's speculative assumption breaks
//! (`profileShiftAfterWarmup`, `lateSubtypeLoading`), the expected Rust
//! result is a FLAT cost across the entire run — the flatness itself is
//! this lab's cross-language finding, not a missing effect to explain
//! away. `rareExceptionPath`'s Rust counterpart uses a `Result`-based
//! cold path (no `panic!`/unwind) for exactly the same reason: no
//! recompilation cycle exists here to demonstrate with a real panic.

pub const N: usize = 1_000_000;
pub const SHIFT_POINT: usize = 500_000;
pub const RARE_EXCEPTION_INDEX: usize = 700_000;
pub const NULL_STRIDE: usize = 1000;
pub const FALLBACK_EXCEPTION: i64 = -1;
pub const FALLBACK_NULL: i64 = 0;

#[inline]
pub fn xorshift64(mut x: u64) -> u64 {
    x ^= x << 13;
    x ^= x >> 7;
    x ^= x << 17;
    x
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

/// strategyDispatch: input[i] = i (linear, no RNG).
pub fn strategy_dispatch(n: usize) -> Vec<i64> {
    (0..n as i64).collect()
}

/// parsingMixedRecords: xorshift64 stream, seed=50.
pub fn parsing_mixed_records(n: usize) -> Vec<i64> {
    stream(50, n)
}

/// rareValidationFailure: xorshift64 stream, seed=51.
pub fn rare_validation_failure(n: usize) -> Vec<i64> {
    stream(51, n)
}

pub fn inputs_for(dataset: &str, n: usize) -> Vec<i64> {
    match dataset {
        "strategyDispatch" => strategy_dispatch(n),
        "parsingMixedRecords" => parsing_mixed_records(n),
        "rareValidationFailure" => rare_validation_failure(n),
        other => panic!("unknown dataset: {other}"),
    }
}

pub trait Strategy {
    fn apply(&self, v: i64) -> i64;
}

pub struct TypeA;
impl Strategy for TypeA {
    fn apply(&self, v: i64) -> i64 {
        v + 1
    }
}

pub struct TypeB;
impl Strategy for TypeB {
    fn apply(&self, v: i64) -> i64 {
        v * 3
    }
}

/// Constructed for the first time at `SHIFT_POINT` in `lateSubtypeLoading`
/// — unlike Java, this has no compile-time-invalidatable effect: `dyn
/// Strategy` was already a vtable call for every variant, from the very
/// first line of `main`.
pub struct TypeC;
impl Strategy for TypeC {
    fn apply(&self, v: i64) -> i64 {
        v ^ 0x5A5A
    }
}

pub fn sum_stable_type_profile(inputs: &[i64]) -> i64 {
    let a: Box<dyn Strategy> = Box::new(TypeA);
    let mut sum: i64 = 0;
    for &v in inputs {
        sum = sum.wrapping_add(a.apply(v));
    }
    sum
}

pub fn sum_profile_shift_after_warmup(inputs: &[i64]) -> i64 {
    let a: Box<dyn Strategy> = Box::new(TypeA);
    let b: Box<dyn Strategy> = Box::new(TypeB);
    let mut sum: i64 = 0;
    for (i, &v) in inputs.iter().enumerate() {
        let s: &dyn Strategy = if i < SHIFT_POINT || i % 2 == 0 {
            a.as_ref()
        } else {
            b.as_ref()
        };
        sum = sum.wrapping_add(s.apply(v));
    }
    sum
}

/// Rust's cold-path counterpart: a `Result`, never a `panic!`/unwind —
/// see this module's doc comment for why.
pub fn sum_rare_exception_path(inputs: &[i64]) -> i64 {
    let a: Box<dyn Strategy> = Box::new(TypeA);
    let mut sum: i64 = 0;
    for (i, &v) in inputs.iter().enumerate() {
        let result: Result<i64, &'static str> = if i == RARE_EXCEPTION_INDEX {
            Err("deliberate, deterministic rare-path error")
        } else {
            Ok(a.apply(v))
        };
        sum = sum.wrapping_add(result.unwrap_or(FALLBACK_EXCEPTION));
    }
    sum
}

pub fn sum_late_subtype_loading(inputs: &[i64]) -> i64 {
    let a: Box<dyn Strategy> = Box::new(TypeA);
    let mut sum: i64 = 0;
    for (i, &v) in inputs.iter().enumerate() {
        if i < SHIFT_POINT {
            sum = sum.wrapping_add(a.apply(v));
        } else {
            // First-ever construction of TypeC, right here — with no
            // compile-time-fixed dispatch decision to invalidate.
            let c: Box<dyn Strategy> = Box::new(TypeC);
            sum = sum.wrapping_add(c.apply(v));
        }
    }
    sum
}

pub fn sum_nullability_shift(inputs: &[i64]) -> i64 {
    let a: Box<dyn Strategy> = Box::new(TypeA);
    let mut sum: i64 = 0;
    for (i, &v) in inputs.iter().enumerate() {
        let maybe: Option<i64> = if i >= SHIFT_POINT && (i - SHIFT_POINT) % NULL_STRIDE == 0 {
            None
        } else {
            Some(v)
        };
        sum = sum.wrapping_add(match maybe {
            Some(x) => a.apply(x),
            None => FALLBACK_NULL,
        });
    }
    sum
}

pub fn run(variant: &str, inputs: &[i64]) -> i64 {
    match variant {
        "stableTypeProfile" => sum_stable_type_profile(inputs),
        "profileShiftAfterWarmup" => sum_profile_shift_after_warmup(inputs),
        "rareExceptionPath" => sum_rare_exception_path(inputs),
        "lateSubtypeLoading" => sum_late_subtype_loading(inputs),
        "nullabilityShift" => sum_nullability_shift(inputs),
        other => panic!("unknown variant: {other}"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn assert_dataset(
        dataset: &str,
        first5: &[i64],
        stable: i64,
        shift: i64,
        exception: i64,
        late: i64,
        null: i64,
    ) {
        let inputs = inputs_for(dataset, N);
        assert_eq!(&inputs[..5], first5);

        assert_eq!(sum_stable_type_profile(&inputs), stable);
        assert_eq!(sum_profile_shift_after_warmup(&inputs), shift);
        assert_eq!(sum_rare_exception_path(&inputs), exception);
        assert_eq!(sum_late_subtype_loading(&inputs), late);
        assert_eq!(sum_nullability_shift(&inputs), null);
    }

    #[test]
    fn strategy_dispatch_matches_the_fixture() {
        assert_dataset(
            "strategyDispatch",
            &[0, 1, 2, 3, 4],
            500000500000,
            875000250000,
            499999799998,
            500121915392,
            499625749500,
        );
    }

    #[test]
    fn parsing_mixed_records_matches_the_fixture() {
        assert_dataset(
            "parsingMixedRecords",
            &[963762, 958315, 339298, 9905, 79709],
            500317225994,
            750461615780,
            500316711341,
            500445453298,
            500058690181,
        );
    }

    #[test]
    fn rare_validation_failure_matches_the_fixture() {
        assert_dataset(
            "rareValidationFailure",
            &[971379, 504874, 867083, 317012, 469816],
            500280750092,
            750435887722,
            500280423156,
            500397852152,
            500030587273,
        );
    }

    #[test]
    fn types_compute_expected_transforms() {
        assert_eq!(TypeA.apply(42), 43);
        assert_eq!(TypeB.apply(42), 126);
        assert_eq!(TypeC.apply(42), 42 ^ 0x5A5A);
    }
}
