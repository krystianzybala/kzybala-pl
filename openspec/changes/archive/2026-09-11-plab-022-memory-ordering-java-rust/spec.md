# Requirements

## Requirement: Explain ordering

The lab MUST explain why source order does not guarantee cross-thread observation order.

### Acceptance criteria

- Shows compiler and CPU reordering conceptually.
- Explains visibility, atomicity, and ordering separately.
- Explains happens-before.

## Requirement: Java VarHandle coverage

The lab MUST cover plain, opaque, acquire, release, and volatile access modes.

### Acceptance criteria

- Buildable Java examples.
- Correct publication example.
- Clear trade-offs.
- No claim that weaker modes are always faster.

## Requirement: Rust atomic coverage

The lab MUST cover Relaxed, Acquire, Release, AcqRel, and SeqCst.

### Acceptance criteria

- Buildable Rust examples.
- Ordering choices documented.
- No undefined-behaviour examples presented as valid code.

## Requirement: Litmus scenarios

The lab MUST provide deterministic educational models for message passing and store buffering.

### Acceptance criteria

- Scenario outcomes are labelled conceptual.
- Allowed and forbidden outcomes are explained.
- The model does not claim to reproduce a specific CPU.

## Requirement: Myth correction

The lab MUST explicitly reject:
- acquire/release flushes caches
- volatile means globally instantaneous
- atomics automatically make a compound algorithm correct
- SeqCst is required everywhere
