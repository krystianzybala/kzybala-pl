# PLAB-023: CAS Contention and Backoff

## Summary

Build an interactive laboratory explaining compare-and-set, failed retries, contention collapse, exponential backoff, and why lock-free does not automatically mean low latency.

## Scope

- CAS semantics
- success and failure
- retry loops
- contention
- cache-line ping-pong
- exponential backoff
- jitter
- fairness
- ABA overview
- Java VarHandle/AtomicLong example
- Rust AtomicU64 example
- JMH and Criterion benchmarks
- single-writer alternative

## Non-goals

- Full hazard-pointer or epoch-reclamation lab
- Complete ABA solution catalogue
- Universal claim that backoff always helps
