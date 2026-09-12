# PLAB-024: SPSC Ring Buffer

## Summary

Replace the current prototype with a complete reference laboratory for single-producer, single-consumer ring buffers.

## Scope

- bounded capacity
- head and tail ownership
- wrap-around
- full and empty detection
- acquire/release publication
- cached cursors
- false-sharing avoidance
- batching
- backpressure
- Java and Rust implementations
- JMH and Criterion benchmarks
- common correctness bugs

## Non-goals

- MPSC/MPMC algorithms
- Disruptor sequencing model
- persistent queues
