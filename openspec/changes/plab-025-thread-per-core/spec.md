# Requirements

## Requirement: Explain thread-per-core

The lab MUST explain thread-per-core as an ownership and execution model, not merely one thread for every logical CPU.

## Requirement: Compare architectures

The lab MUST compare shared worker pools and owned-state execution.

## Requirement: Handoff and backpressure

The lab MUST show bounded queues, handoff cost, and overload behaviour.

## Requirement: Affinity caveats

The lab MUST explain that affinity support and scheduler control differ across Linux, macOS, Windows, and container environments.

## Requirement: Java and Rust examples

The lab MUST include buildable examples using bounded SPSC communication.

## Requirement: Trade-offs

The lab MUST explain hot partitions, underutilised cores, operational complexity, and workloads where the model is inappropriate.
