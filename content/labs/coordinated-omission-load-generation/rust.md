# Coordinated Omission and Load Generation — Rust

## The same recurrence, one `VecDeque` instead of an `ArrayDeque`

```rust
fn open_loop(params: &LoadParams, intended_send_time: impl Fn(u64, &LoadParams) -> u64) -> Outcome {
    let mut in_flight: VecDeque<u64> = VecDeque::new();
    let mut next_free_time: u64 = 0;
    for i in 0..params.count {
        let send_time = intended_send_time(i, params);
        while let Some(&front) = in_flight.front() {
            if front <= send_time { in_flight.pop_front(); } else { break; }
        }
        if in_flight.len() >= params.queue_capacity {
            missed += 1;
            continue;
        }
        let completion = next_free_time.max(send_time) + service_time;
        // ...
    }
}
```

Rust's `open_loop` takes the schedule function as a generic `impl Fn(u64,
&LoadParams) -> u64` parameter, letting `OPEN_LOOP_FIXED_RATE`,
`POISSON_LIKE_ARRIVALS`, and `BURST_SCHEDULE` share one implementation
(mirroring Java's `IntToLongFunction` parameter on the same method) — the
three variants differ only in which schedule closure they pass in, never
in the admission/queueing logic itself.

## No `unsafe`, no external time-simulation crate

Every function in this crate operates purely on `u64` logical-nanosecond
arithmetic — there is no real timer, no `std::time::Instant`, and no
external discrete-event-simulation dependency. This keeps the whole
correctness fixture deterministic across any machine and any run, with
zero flakiness risk, which is essential for a lab whose entire subject is
"honest measurement."

## Closed-loop and omission-corrected recurrences

```rust
fn closed_loop(params: &LoadParams) -> Outcome {
    let mut clock: u64 = 0;
    for i in 0..params.count {
        let service_time = params.service_model.service_time_nanos(i);
        let completion = clock + service_time;
        let response_time = completion - clock; // == service_time, always
        // ...
        clock = completion;
    }
}
```

Identical structure to the Java implementation — `response_time` is
algebraically `service_time` here too, asserted directly by
`closed_loop_response_time_always_equals_service_time`.

## Criterion benchmark

`benches/coordinated_omission_load_generation.rs` runs all five variants
against `periodic_ten_ms_stall`, plus `OpenLoopFixedRate` against
`bounded_server_overload`, on the pinned release profile.

The runnable Cargo/Criterion project (with correctness tests in
`src/lib.rs`) is at
<a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/coordinated-omission-load-generation/code/rust" rel="noopener"><code>content/labs/coordinated-omission-load-generation/code/rust/</code></a>
in this site's repository.
