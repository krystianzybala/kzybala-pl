# Deterministic Low-Latency Pipeline Capstone — Rust

## The identical recurrence, `Vec<VecDeque<u32>>` per shard

```rust
let mut queues: Vec<VecDeque<u32>> = (0..num_shards).map(|_| VecDeque::new()).collect();
// ...
let shard = if num_shards > 1 { (key as usize) % num_shards } else { 0 };
if queues[shard].len() < capacity {
    queues[shard].push_back(id);
} else {
    dropped += 1;
}
```

No `unsafe`, no external queue crate — `std::collections::VecDeque` per
shard is sufficient for this lab's deterministic correctness fixture, and
mirrors the Java `ArrayDeque[]` array exactly.

## Fault/restart, mirrored exactly

```rust
if variant == PipelineVariant::FaultRestartProfile && tick == params.restart_at_tick {
    let victim = params.restart_shard % num_shards;
    restart_loss += queues[victim].len() as u32;
    queues[victim].clear();
}
```

Same accounting discipline as Java: `restart_loss` is a distinct outcome
from `dropped` and `delivered`, checked directly by
`invariant_holds()` (`produced == delivered + dropped + restart_loss`).

## Zero-copy decode (the real, non-fixture implementation)

The real event-replay harness (exercised by the Criterion benchmark, not
this correctness fixture) reads each fixed-layout event as a borrowed
`&[u8]` slice and reinterprets the first four bytes as the key with
`u32::from_le_bytes`, never copying the payload into an owned buffer —
the same "owned vs. borrowed decode" contrast the
[Fixed Binary Serialization](/lab/fixed-binary-serialization/) lab
teaches directly, reused here as one pipeline stage rather than the whole
subject.

## Criterion benchmark

`benches/deterministic_low_latency_pipeline.rs` runs all four variants
(`NaiveObjectQueue`/`Optimized` against `medium_events_uniform_burst`,
`OverloadProfile`/`FaultRestartProfile` against
`medium_events_hot_key_burst`), on the pinned release profile.

The runnable Cargo/Criterion project (with correctness tests in
`src/lib.rs`) is at
<a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/deterministic-low-latency-pipeline/code/rust" rel="noopener"><code>content/labs/deterministic-low-latency-pipeline/code/rust/</code></a>
in this site's repository.
