# PLAB-022: Memory Ordering in Java and Rust

## Summary

Build an interactive laboratory comparing memory ordering concepts in Java VarHandles and Rust atomics.

## Scope

- Program order versus observed order
- Compiler and CPU reordering
- happens-before
- data races
- plain, opaque, acquire, release, volatile in Java
- Relaxed, Acquire, Release, AcqRel, SeqCst in Rust
- message-passing litmus test
- store buffering litmus test
- publication
- visibility
- synchronisation edges
- Java and Rust examples
- conceptual hardware model with store buffers
- limitations and myths

## Non-goals

- Full formal memory-model proof system
- Cycle-accurate CPU simulation
- Claiming acquire/release flushes caches
- Treating Java and Rust memory models as identical

## Success criteria

- The user can explain acquire/release publication.
- The user can distinguish visibility from atomicity.
- The user can map Java and Rust orderings without assuming exact equivalence.
