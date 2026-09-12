# PLAB-025: Thread-per-Core Architecture

## Summary

Build an advanced laboratory explaining thread-per-core execution, state ownership, partitioning, affinity, handoff, backpressure, and predictable latency.

## Scope

- thread-per-core model
- shared-nothing ownership
- partitioning
- single-writer state
- SPSC communication
- CPU affinity
- scheduler migration
- NUMA caveats
- load imbalance
- backpressure
- Java and Rust examples
- benchmark and topology disclosure

## Non-goals

- Full matching engine
- Full actor-system comparison
- Kernel-bypass implementation
