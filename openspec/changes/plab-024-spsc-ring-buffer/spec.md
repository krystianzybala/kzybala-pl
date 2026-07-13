# Requirements

## Requirement: Explain ownership

The producer owns head updates and the consumer owns tail updates.

## Requirement: Publication ordering

The lab MUST explain release publication and acquire observation.

## Requirement: Bounded behaviour

The lab MUST show full, empty, wrap-around, and backpressure.

## Requirement: Java and Rust

The lab MUST include buildable zero-allocation steady-state examples.

## Requirement: Benchmark methodology

JMH and Criterion benchmarks MUST disclose batching, capacity, payload size, pinning, warm-up, and units.

## Requirement: Failure modes

The lab MUST demonstrate at least one incorrect publication-ordering bug and one overwrite bug.
