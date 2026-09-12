# Virtual Threads vs Platform Threads vs Event Loops — Rust

## No virtual threads, by design

Rust's standard library has no lightweight, user-mode thread runtime, and
this repository's policy is to avoid adding an async runtime solely to
chase a Java feature (design.md: "avoid adding a runtime solely for the lab
without justification"). Every Rust variant is therefore either an OS
thread or a single, manually-driven event loop — there is no
`high_fanout_os_threads` equivalent to Java's cheap-blocking virtual
threads, only an OS-thread scenario documented as **non-comparable**:

```rust
/// Rust's non-comparable counterpart to Java's virtual-thread-per-task variant: still one
/// OS thread per task (no virtual threads exist), kept as a separate named scenario.
pub fn high_fanout_os_threads(tasks: &[TaskSpec]) -> RunResult {
    thread_per_request(tasks)
}
```

This is exactly the semantic-equivalence contract's escape hatch: the
comparison may still be educational, but it is labeled as a design
trade-off, and `comparison-guard.js`-style discipline excludes this pair
from any cross-language throughput chart (see benchmark.md).

## Platform threads and the CPU-bound pool

```rust
pub fn thread_per_request(tasks: &[TaskSpec]) -> RunResult {
    thread::scope(|scope| {
        for t in tasks {
            scope.spawn(move || { /* run_one, accumulate */ });
        }
    });
    // ...
}
```

`thread::scope` gives borrow-checked, joined-at-scope-exit threads without
`Arc`-wrapping the task slice. `cpu_bound_pool` pulls task indices from one
shared `Mutex<VecDeque<usize>>`, with a worker count from
`thread::available_parallelism()` — the direct Rust analogue of Java's
`Executors.newFixedThreadPool(availableProcessors())`.

## The event loop: a deadline min-heap, not a busy poll

```rust
pub fn fixed_event_loop(tasks: &[TaskSpec]) -> RunResult {
    let mut heap: BinaryHeap<Reverse<(u64, usize)>> = /* one (deadline, index) per task */;
    while let Some(Reverse((deadline, idx))) = heap.pop() {
        let target = start + Duration::from_nanos(deadline);
        if target > Instant::now() { thread::sleep(target - Instant::now()); }
        // run this task's CPU stage on the loop thread
    }
}
```

Ordering tasks by deadline in a min-heap means the loop only ever sleeps
the *gap* between consecutive deadlines, not the sum of every task's wait —
the same non-blocking-timer behavior Java's `ScheduledExecutorService`
gives for free. The CPU stage for each expired deadline still executes
serially on this one thread, which is the exact mechanism under test.

## Mixed: OS threads absorb the wait, a fixed pool takes the CPU

```rust
pub fn mixed(tasks: &[TaskSpec]) -> RunResult {
    // fixed pool of `available_parallelism()` workers pull task indices from an mpsc channel
    // one OS thread per task: blocking_wait, then send its index to the pool, then join
}
```

Rust's `std::sync::mpsc` channel plays the role Java's `Future`-per-task
nesting plays: an `Arc`-free, `Mutex<Receiver<usize>>`-shared queue that a
fixed pool drains while a separate wave of short-lived OS threads absorbs
the blocking-wait fan-out. No `unsafe` is used anywhere in this crate — the
scoped-thread API and channel primitives are sufficient for every variant.

## Criterion benchmark

`benches/virtual_platform_event_loop.rs` runs each of the six functions
above against the matching dataset from `fixtures`, on the pinned release
profile (`[profile.bench] inherits = "release"`).

The runnable Cargo/Criterion project (with correctness tests in
`src/lib.rs`) is at
<a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/virtual-platform-event-loop/code/rust" rel="noopener"><code>content/labs/virtual-platform-event-loop/code/rust/</code></a>
in this site's repository.
