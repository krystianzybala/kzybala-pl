# Requirements

## Explain the mechanism

The lab MUST explain false sharing as coherence traffic caused by independent mutable data in one coherence unit.

### Acceptance criteria
- Distinguishes data races.
- Distinguishes lock contention.
- Avoids universal cache-line assumptions.

## Interactive shared-line scenario

### Acceptance criteria
- Both CPUs can write.
- State visible in text.
- Invalidation count visible.
- Reset exact.
- Keyboard accessible.

## Padded comparison

### Acceptance criteria
- Shared and padded layouts selectable.
- Separate coherence units shown.
- Memory-footprint trade-off explained.
- No claim padding always helps.

## Java example

### Acceptance criteria
- Uses JMH.
- Shared and padded variants.
- Prevents elimination.
- Documents `@Contended` flags.
- Documents thread assignment.

## Rust example

### Acceptance criteria
- Uses atomics.
- Adjacent and aligned variants.
- Uses Criterion or equivalent.
- Documents ordering and alignment assumptions.

## Honest results

### Acceptance criteria
- Simulated counters labelled.
- Measured results include environment.
- Charts include units and uncertainty or distribution.

## Educational completion

### Acceptance criteria
- Common mistakes.
- When to use.
- When not to use.
- Investigation task.
- At least three review questions.
- Sources.
