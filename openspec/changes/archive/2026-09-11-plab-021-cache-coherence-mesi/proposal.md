# PLAB-021: Cache Coherence and MESI

## Summary

Build a complete interactive laboratory explaining cache coherence, line ownership, invalidation traffic, and MESI-style state transitions across multiple CPU cores.

## Motivation

False sharing is difficult to understand without first understanding coherence. The existing False Sharing laboratory shows the symptom. This change explains the mechanism beneath it.

## Scope

- Coherence versus consistency
- Modified, Exclusive, Shared, Invalid states
- Read miss
- Write miss
- Read-for-ownership
- Invalidation
- Cache-to-cache transfer
- Write-back
- Eviction
- Shared-reader and competing-writer scenarios
- Java and Rust examples
- Linux perf c2c methodology
- Interactive deterministic visual model
- Quiz, investigation task, sources, and caveats

## Non-goals

- Cycle-accurate simulation
- Claiming MESI is universal
- Modelling a specific Apple Silicon implementation
- Full memory-ordering semantics
- Full false-sharing implementation, already covered separately

## Success criteria

- The user can explain why writes require ownership.
- The user can distinguish coherence from memory ordering.
- The user can follow line-state transitions across two cores.
- The lab clearly distinguishes conceptual protocol states from processor-specific implementations.
