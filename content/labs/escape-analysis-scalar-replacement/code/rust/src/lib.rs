//! The five usage-pattern variants (non-escaping, returned, stored into
//! a field, passed to an opaque call, identity-observed) over three
//! datasets, for the "Escape analysis and scalar replacement" lab — the
//! cross-language equivalence contract
//! (../fixtures/escape-analysis-scalar-replacement-fixtures.json). No
//! `unsafe` anywhere in this crate.
//!
//! **A real language-mechanism difference, not smoothed over** (this
//! lab's "comparing stack Rust value to heap Java object with different
//! semantics" trap, benchmark.md): `Aggregate` here is a plain,
//! `Copy`-able value type. Rust never heap-allocates a plain value
//! unless you explicitly ask for it (`Box::new`, `Vec`, `Rc`, …) — there
//! is no runtime "escape analysis" step deciding whether it is safe to
//! keep it off the heap, because the heap was never the default in the
//! first place. None of the five variants below allocates — in sharp
//! contrast to Java, where only the `nonEscaping` shape reliably avoids
//! the heap and the other four pay a real allocation (32 B/op, GC pauses
//! included). Development wiring runs still show real spread between
//! them (`returnedObject`/`passedToOpaqueCall` costing several times
//! `nonEscaping`) — but that gap comes from `#[inline(never)]` call-
//! boundary overhead (a lost optimization opportunity), never from
//! allocation; see rust.md for measured numbers and how to tell the two
//! apart from real evidence. `sum_boxed` (below, NOT part of the
//! cross-language correctness matrix) shows what asking Rust to actually
//! heap-allocate looks like — and, measured during development, it was
//! still cheaper than Java's forced-materialization variants, because
//! Rust's allocator carries no GC tracing cost.

pub const N: usize = 1_000_000;

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

pub struct Pair {
    pub x: Vec<i64>,
    pub y: Vec<i64>,
}

/// coordinate: x[i] = i (linear); y = xorshift64 stream seed=42.
pub fn coordinate(n: usize) -> Pair {
    Pair {
        x: (0..n as i64).collect(),
        y: stream(42, n),
    }
}

/// resultWrapper: x = xorshift64 stream seed=43; y = seed=44.
pub fn result_wrapper(n: usize) -> Pair {
    Pair {
        x: stream(43, n),
        y: stream(44, n),
    }
}

/// parserState: x = xorshift64 stream seed=45; y = seed=46.
pub fn parser_state(n: usize) -> Pair {
    Pair {
        x: stream(45, n),
        y: stream(46, n),
    }
}

pub fn pair_for(dataset: &str, n: usize) -> Pair {
    match dataset {
        "coordinate" => coordinate(n),
        "resultWrapper" => result_wrapper(n),
        "parserState" => parser_state(n),
        other => panic!("unknown dataset: {other}"),
    }
}

pub fn expected_total(x: &[i64], y: &[i64]) -> i64 {
    let mut sum: i64 = 0;
    for i in 0..x.len() {
        sum = sum.wrapping_add(x[i]).wrapping_add(y[i]);
    }
    sum
}

/// The small, two-field aggregate every variant constructs — `Copy`,
/// exactly like a Java `record` in shape, but with none of the "does
/// this need a heap slot" question a JVM object carries.
#[derive(Clone, Copy)]
pub struct Aggregate {
    pub x: i64,
    pub y: i64,
}

impl Aggregate {
    pub fn sum(&self) -> i64 {
        self.x + self.y
    }
}

/// The "stored into field" variant's target — a field an `Aggregate`
/// value gets copied into, overwriting the previous one each iteration.
#[derive(Default)]
pub struct AggregateHolder {
    last: Option<Aggregate>,
}

impl AggregateHolder {
    pub fn store(&mut self, a: Aggregate) {
        self.last = Some(a);
    }
    pub fn last(&self) -> Aggregate {
        self.last.expect("store() must run before last()")
    }
}

/// Passed-to-opaque-call barrier: `#[inline(never)]` is Rust's
/// deterministic counterpart to the Java side's `@CompilerControl
/// (DONT_INLINE)` — forces a real, non-inlined call boundary. Because
/// `Aggregate` is passed BY VALUE (a plain copy, no pointer), this still
/// costs nothing beyond the call itself: there is no heap object whose
/// escape needs proving.
#[inline(never)]
fn opaque_sum(a: Aggregate) -> i64 {
    a.sum()
}

#[inline(never)]
fn opaque_build(x: i64, y: i64) -> Aggregate {
    Aggregate { x, y }
}

/// Non-escaping: built, read locally, discarded.
pub fn sum_non_escaping(x: &[i64], y: &[i64]) -> i64 {
    let mut sum: i64 = 0;
    for i in 0..x.len() {
        let a = Aggregate { x: x[i], y: y[i] };
        sum = sum.wrapping_add(a.sum());
    }
    sum
}

/// Returned object: a helper function builds and returns the aggregate BY VALUE.
pub fn sum_returned_object(x: &[i64], y: &[i64]) -> i64 {
    let mut sum: i64 = 0;
    for i in 0..x.len() {
        let a = opaque_build(x[i], y[i]);
        sum = sum.wrapping_add(a.sum());
    }
    sum
}

/// Stored into field: copied into a longer-lived holder each iteration.
pub fn sum_stored_into_field(x: &[i64], y: &[i64], holder: &mut AggregateHolder) -> i64 {
    let mut sum: i64 = 0;
    for i in 0..x.len() {
        let a = Aggregate { x: x[i], y: y[i] };
        holder.store(a);
        sum = sum.wrapping_add(holder.last().sum());
    }
    sum
}

/// Passed to an opaque (never-inlined) call.
pub fn sum_passed_to_opaque_call(x: &[i64], y: &[i64]) -> i64 {
    let mut sum: i64 = 0;
    for i in 0..x.len() {
        let a = Aggregate { x: x[i], y: y[i] };
        sum = sum.wrapping_add(opaque_sum(a));
    }
    sum
}

/// Identity observed: takes the local's address (Rust's closest analog
/// to Java's `identityHashCode` — forcing a stable memory location for
/// the value) through `black_box`, for its SIDE EFFECT only; the address
/// itself is discarded, never folded into `sum`. Note what this does NOT
/// do: force a HEAP allocation — at most it forces a stack slot, which
/// is the entire point of the cross-language contrast this lab teaches.
pub fn sum_identity_observed(x: &[i64], y: &[i64]) -> i64 {
    let mut sum: i64 = 0;
    for i in 0..x.len() {
        let a = Aggregate { x: x[i], y: y[i] };
        let _address = std::hint::black_box(&a as *const Aggregate as usize);
        sum = sum.wrapping_add(a.sum());
    }
    sum
}

/// NOT part of the cross-language correctness matrix — an explicit,
/// genuine heap allocation per element, for a real reference point on
/// what "always pay the heap cost, like Java" actually costs in Rust.
/// See rust.md.
pub fn sum_boxed(x: &[i64], y: &[i64]) -> i64 {
    let mut sum: i64 = 0;
    for i in 0..x.len() {
        let a = Box::new(Aggregate { x: x[i], y: y[i] });
        sum = sum.wrapping_add(a.sum());
    }
    sum
}

pub fn run(variant: &str, x: &[i64], y: &[i64], holder: &mut AggregateHolder) -> i64 {
    match variant {
        "nonEscaping" => sum_non_escaping(x, y),
        "returnedObject" => sum_returned_object(x, y),
        "storedIntoField" => sum_stored_into_field(x, y, holder),
        "passedToOpaqueCall" => sum_passed_to_opaque_call(x, y),
        "identityObserved" => sum_identity_observed(x, y),
        other => panic!("unknown variant: {other}"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn assert_dataset(dataset: &str, x_first5: &[i64], y_first5: &[i64], expected: i64) {
        let pair = pair_for(dataset, N);
        assert_eq!(&pair.x[..5], x_first5);
        assert_eq!(&pair.y[..5], y_first5);
        assert_eq!(expected_total(&pair.x, &pair.y), expected);

        let mut holder = AggregateHolder::default();
        assert_eq!(sum_non_escaping(&pair.x, &pair.y), expected);
        assert_eq!(sum_returned_object(&pair.x, &pair.y), expected);
        assert_eq!(
            sum_stored_into_field(&pair.x, &pair.y, &mut holder),
            expected
        );
        assert_eq!(sum_passed_to_opaque_call(&pair.x, &pair.y), expected);
        assert_eq!(sum_identity_observed(&pair.x, &pair.y), expected);
        assert_eq!(sum_boxed(&pair.x, &pair.y), expected);
    }

    #[test]
    fn coordinate_all_variants_agree() {
        assert_dataset(
            "coordinate",
            &[0, 1, 2, 3, 4],
            &[805674, 905471, 320954, 629736, 84162],
            999865210459,
        );
    }

    #[test]
    fn result_wrapper_all_variants_agree() {
        assert_dataset(
            "resultWrapper",
            &[75435, 585150, 59539, 476429, 785191],
            &[869484, 806586, 360012, 316142, 143781],
            1000116545743,
        );
    }

    #[test]
    fn parser_state_all_variants_agree() {
        assert_dataset(
            "parserState",
            &[139245, 651899, 615781, 740619, 242304],
            &[884718, 14393, 986654, 804012, 226311],
            1000153707544,
        );
    }

    #[test]
    fn aggregate_computes_sum_correctly() {
        let a = Aggregate { x: 7, y: 35 };
        assert_eq!(a.sum(), 42);
    }
}
