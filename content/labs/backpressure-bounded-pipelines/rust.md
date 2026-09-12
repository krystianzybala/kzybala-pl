# Backpressure and Bounded Pipelines — Rust

## The same six policies, no `unsafe`

The Rust `simulate` function mirrors the Java simulator exactly — one
deterministic tick loop over `std::collections::VecDeque`/`HashMap`, no
`unsafe` anywhere, since the mechanism under test is queueing *policy*,
not memory layout:

```rust
pub fn simulate(policy: Policy, params: &PipelineParams) -> SimResult {
    let mut queue: VecDeque<u64> = VecDeque::new();
    // ...
    match policy {
        Policy::Unbounded => queue.push_back(id),
        Policy::BoundedReject => { /* push or reject */ }
        Policy::BoundedBlock => { /* push or backlog */ }
        Policy::DropOldest => { /* pop_front, then push */ }
        Policy::CoalesceByKey => { /* overwrite pending value */ }
        Policy::LoadShedding => { /* proactive occupancy-based drop */ }
    }
}
```

## Coalesce-by-key without a `LinkedHashMap`

Rust's standard library has no ordered hash map, so the coalescing policy
is built from two structures instead of one: an insertion-ordered
`VecDeque<key>` recording *first*-arrival order, and a `HashMap<key,
value>` holding the current pending value per key:

```rust
Policy::CoalesceByKey => {
    if coalesce_values.insert(key, id).is_some() {
        superseded += 1;
    } else {
        coalesce_order.push_back(key); // only recorded on first arrival
    }
}
```

Because `coalesce_order` only gains an entry the *first* time a key
appears, and `HashMap::insert` on an existing key silently overwrites the
value in place, this reproduces `LinkedHashMap`'s exact semantics
(overwrite value, preserve original position) with two plain std types
instead of one ordered map type.

## Bounded block: a backlog, drained before new production

```rust
if policy == Policy::BoundedBlock {
    while !block_backlog.is_empty() && queue.len() < params.capacity {
        queue.push_back(block_backlog.pop_front().unwrap());
    }
}
```

Same model as Java: since this is a deterministic single-threaded
simulation, "the producer blocks until there's room" means anything that
didn't fit this tick is retried, in FIFO order, before any of next tick's
new production — zero drops, increased sojourn time. The real threaded
version (a bounded channel's blocking `send`) is exercised by the
Criterion benchmark, not this deterministic model.

## Criterion benchmark

`benches/backpressure_bounded_pipelines.rs` measures the same six
functions against `sustained_overload` (`hot_key_skew` for
`coalesce_by_key`), on the pinned release profile.

The runnable Cargo/Criterion project (with correctness tests in
`src/lib.rs`) is at
<a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/backpressure-bounded-pipelines/code/rust" rel="noopener"><code>content/labs/backpressure-bounded-pipelines/code/rust/</code></a>
in this site's repository.
