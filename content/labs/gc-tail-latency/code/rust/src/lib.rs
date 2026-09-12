//! Deterministic input generation and the five allocation-pattern
//! variants — the cross-language equivalence contract
//! (../fixtures/gc-tail-latency-fixtures.json). Every variant reads the
//! identical value stream and accumulates the identical checksum;
//! allocation pattern (and, on the Java side, collector choice) changes
//! GC pressure and pause behavior, never the result.
//!
//! Rust has no garbage collector: `Box::new` allocates on the system
//! allocator and `Drop` reclaims deterministically and synchronously the
//! instant a value goes out of scope. There is no stop-the-world pause
//! for this code to exhibit — the honest cross-language question is not
//! "how fast is Rust's GC pause" (there isn't one) but "what does
//! allocator latency look like instead, and what does dropping a large
//! retained structure cost as a single synchronous event." The evidence
//! binary in `src/bin/gc_tail_harness.rs` measures exactly that, and
//! `rust.md` documents why it is not a percentile-pause chart of the
//! same phenomenon as the Java track's JFR evidence.

pub const N: usize = 1_000_000;

/// The small payload every variant allocates (or reuses) — a value plus
/// a small extra buffer, mirroring Java's `Node`.
pub struct Node {
    pub value: u64,
    pub extra: Vec<u64>,
}

impl Node {
    pub fn new(value: u64, extra_size: usize) -> Self {
        let mut extra = vec![0u64; extra_size];
        extra[0] = value;
        Node { value, extra }
    }
}

pub fn xorshift64(x: u64) -> u64 {
    let mut x = x;
    x ^= x << 13;
    x ^= x >> 7;
    x ^= x << 17;
    x
}

fn stream(seed: u64, n: usize) -> Vec<u64> {
    let mut out = Vec::with_capacity(n);
    let mut x = seed;
    for _ in 0..n {
        x = xorshift64(x);
        out.push(x % 1_000_000);
    }
    out
}

/// objectGraphChurn: value\[i\] = i (linear); extraSize = 8.
pub fn object_graph_churn(n: usize) -> Vec<u64> {
    (0..n as u64).collect()
}

/// messagePipeline: xorshift64 stream, seed=60; extraSize = 16.
pub fn message_pipeline(n: usize) -> Vec<u64> {
    stream(60, n)
}

/// retainedCache: xorshift64 stream, seed=61; extraSize = 4.
pub fn retained_cache(n: usize) -> Vec<u64> {
    stream(61, n)
}

pub fn values_for(dataset: &str, n: usize) -> Vec<u64> {
    match dataset {
        "objectGraphChurn" => object_graph_churn(n),
        "messagePipeline" => message_pipeline(n),
        "retainedCache" => retained_cache(n),
        other => panic!("unknown dataset: {other}"),
    }
}

pub fn extra_size_for(dataset: &str) -> usize {
    match dataset {
        "objectGraphChurn" => 8,
        "messagePipeline" => 16,
        "retainedCache" => 4,
        other => panic!("unknown dataset: {other}"),
    }
}

pub fn expected_total(values: &[u64]) -> u64 {
    values.iter().fold(0u64, |acc, &v| acc.wrapping_add(v))
}

const BURST_QUIET_LEN: usize = 100;
const BURST_LEN: usize = 20;

/// Low allocation/reuse: one Node, mutated in place every iteration.
pub fn sum_low_allocation_reuse(values: &[u64], extra_size: usize) -> u64 {
    let mut reused = Node::new(0, extra_size);
    let mut sum = 0u64;
    for &v in values {
        reused.value = v;
        reused.extra[0] = v;
        sum = sum.wrapping_add(reused.value);
    }
    sum
}

/// Steady high allocation: a fresh boxed Node every iteration, dropped immediately.
pub fn sum_steady_high_allocation(values: &[u64], extra_size: usize) -> u64 {
    let mut sum = 0u64;
    for &v in values {
        let n = Box::new(Node::new(v, extra_size));
        sum = sum.wrapping_add(n.value);
    }
    sum
}

/// Bursty allocation: alternating quiet (reused) and burst (larger, fresh boxed) blocks.
pub fn sum_bursty_allocation(values: &[u64], extra_size: usize) -> u64 {
    let mut reused = Node::new(0, extra_size);
    let mut sum = 0u64;
    let mut i = 0usize;
    let n = values.len();
    while i < n {
        let quiet_end = (i + BURST_QUIET_LEN).min(n);
        while i < quiet_end {
            reused.value = values[i];
            sum = sum.wrapping_add(reused.value);
            i += 1;
        }
        let burst_end = (i + BURST_LEN).min(n);
        while i < burst_end {
            let fresh = Box::new(Node::new(values[i], extra_size * 8));
            sum = sum.wrapping_add(fresh.value);
            i += 1;
        }
    }
    sum
}

/// Growing live set: a fresh boxed Node every iteration, retained forever
/// in a `Vec` — live set grows monotonically to n nodes by the end of the
/// run. Returns the checksum and the retained Vec, so the caller can time
/// its drop as a single synchronous reclamation event.
pub fn sum_growing_live_set(values: &[u64], extra_size: usize) -> (u64, Vec<Box<Node>>) {
    let mut retained = Vec::with_capacity(values.len());
    let mut sum = 0u64;
    for &v in values {
        let n = Box::new(Node::new(v, extra_size));
        sum = sum.wrapping_add(n.value);
        retained.push(n);
    }
    let mut check = 0u64;
    for n in &retained {
        check = check.wrapping_add(n.value);
    }
    assert_eq!(
        check, sum,
        "retained live set does not match the running sum"
    );
    (sum, retained)
}

/// Collector matrix: a documented pass-through. There is no collector to
/// select in Rust — this variant is identical code to
/// `sum_steady_high_allocation`, included only for matrix parity with
/// the Java track (`rust.md`).
pub fn sum_collector_matrix(values: &[u64], extra_size: usize) -> u64 {
    sum_steady_high_allocation(values, extra_size)
}

pub fn run(variant: &str, values: &[u64], extra_size: usize) -> u64 {
    match variant {
        "lowAllocationReuse" => sum_low_allocation_reuse(values, extra_size),
        "steadyHighAllocation" => sum_steady_high_allocation(values, extra_size),
        "burstyAllocation" => sum_bursty_allocation(values, extra_size),
        "growingLiveSet" => sum_growing_live_set(values, extra_size).0,
        "collectorMatrix" => sum_collector_matrix(values, extra_size),
        other => panic!("unknown variant: {other}"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn assert_dataset(dataset: &str, first5: [u64; 5], expected: u64) {
        let values = values_for(dataset, N);
        assert_eq!(&values[0..5], &first5);
        assert_eq!(expected_total(&values), expected);

        let extra_size = extra_size_for(dataset);
        assert_eq!(sum_low_allocation_reuse(&values, extra_size), expected);
        assert_eq!(sum_steady_high_allocation(&values, extra_size), expected);
        assert_eq!(sum_bursty_allocation(&values, extra_size), expected);
        assert_eq!(sum_growing_live_set(&values, extra_size).0, expected);
        assert_eq!(sum_collector_matrix(&values, extra_size), expected);
    }

    #[test]
    fn object_graph_churn_all_variants_agree() {
        assert_dataset("objectGraphChurn", [0, 1, 2, 3, 4], 499_999_500_000);
    }

    #[test]
    fn message_pipeline_all_variants_agree() {
        assert_dataset(
            "messagePipeline",
            [185660, 657890, 900124, 364735, 174128],
            500_229_738_955,
        );
    }

    #[test]
    fn retained_cache_all_variants_agree() {
        assert_dataset(
            "retainedCache",
            [193277, 370083, 430581, 204826, 290517],
            499_839_503_787,
        );
    }
}
