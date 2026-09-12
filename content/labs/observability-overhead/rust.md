# Logging, Metrics and Profiling Overhead — Rust

## No `tracing`/`log` crate — `format!` and a guard, directly

```rust
Variant::DisabledEagerLogging => {
    let message = format!("event id={id} checksum={checksum}");
    format_count += 1;
    if !LOG_LEVEL_DISABLED { logged_count += 1; }
    black_box(message);
}
Variant::DisabledLazyLogging => {
    if !LOG_LEVEL_DISABLED {
        let message = format!("event id={id} checksum={checksum}");
        format_count += 1;
        logged_count += 1;
        black_box(message);
    }
}
```

Per this lab's non-goal against adding frameworks the mechanism doesn't
need, both variants use plain `format!` directly rather than the `tracing`
or `log` crates — the cost being measured (building a `String` before vs.
after a boolean check) is a property of *any* logging approach, not
specific to one crate's macro expansion.

## Sampled tracing via `std::backtrace`, no new dependency

```rust
Variant::SampledTracing => {
    if params.sample_rate > 0 && id % params.sample_rate == 0 {
        let trace = Backtrace::force_capture();
        sampled_count += 1;
        let _ = black_box(trace);
    }
}
```

`std::backtrace::Backtrace::force_capture()` (stable since Rust 1.65)
performs a real stack walk regardless of the `RUST_BACKTRACE` environment
variable — the Rust standard library equivalent of Java's
`Thread.currentThread().getStackTrace()`, needing no external crate.

## The same deterministic bounded-queue model as Java

```rust
Variant::AsyncBoundedLogging => {
    if async_occupancy < params.async_queue_capacity {
        async_occupancy += 1;
        logged_count += 1;
    } else {
        dropped_count += 1;
    }
}
```

Same reasoning as the Java implementation: a real background-thread
consumer would make the drop count depend on scheduling timing, which
this repository's correctness suites must never do. `black_box` (from
`std::hint`) prevents the compiler from proving `message`/`trace` are
unused and eliminating the very cost this lab measures.

## Criterion benchmark

`benches/observability_overhead.rs` runs each of the seven variants
against the matching dataset, on the pinned release profile.

The runnable Cargo/Criterion project (with correctness tests in
`src/lib.rs`) is at
<a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/observability-overhead/code/rust" rel="noopener"><code>content/labs/observability-overhead/code/rust/</code></a>
in this site's repository.
