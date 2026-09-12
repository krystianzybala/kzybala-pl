# PLAB-010: False Sharing Reference Laboratory

## Summary

Build the first complete reference lab explaining false sharing through theory, an interactive cache-line model, Java and Rust examples, benchmark methodology, trade-offs and an investigation task.

## Motivation

False sharing is often reduced to “add padding”. The lab must show the mechanism: independent variables sharing a coherence unit and causing invalidation traffic under concurrent writes.

## Scope

- Cache-line explanation
- Shared and padded scenarios
- Step-by-step transitions
- Conceptual MESI-like states
- Java and Rust implementations
- JMH and Criterion methodology
- Common mistakes
- When padding helps or hurts
- Quiz and investigation task
- Mobile and reduced-motion support

## Non-goals

- Cycle-accurate MESI simulation
- Universal 64-byte cache-line claim
- Fabricated benchmark numbers
- Full coherence-protocol course

## Success criteria

- User explains why independent counters interfere.
- User distinguishes false sharing from locks and data races.
- Shared and padded layouts are comparable in Java and Rust.
- Visualisation is clearly labelled conceptual.
- Benchmarks are reproducible.
