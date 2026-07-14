# SPSC ring buffer — Rust project

Companion code for the [SPSC Ring Buffer](https://kzybala.pl/lab/spsc-ring-buffer/)
Performance Lab. Not part of this site's own build or CI — a standalone
Cargo project you clone/copy out and run on your own machine.

## Test

```sh
cargo test
```

Confirms `Producer::try_produce` rejects reservations when full,
`Consumer::try_consume` reports empty before anything is published, values
are delivered in FIFO order across wrap-around, and the pair is correct
with a real producer thread and a real consumer thread.

## Benchmark

```sh
cargo bench
```

Runs `spsc_ring_buffer_capacity_1024`, spawning a real producer thread and
a real consumer thread over one 1024-slot buffer and measuring steady-state
pipeline throughput for 200,000 items per iteration. Full HTML report and
raw per-sample CSV are written to `target/criterion/`.

Requires a recent stable Rust toolchain (edition 2021).
