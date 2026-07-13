# Design

## Scenarios

### Shared line

```text
[counter A][counter B][unused...]
```

### Padded line

```text
line 0: [counter A][padding...]
line 1: [counter B][padding...]
```

### Read-mostly

One writer and one reader demonstrate that access pattern matters.

## State

```json
{
  "scenario": "shared-line",
  "step": 0,
  "cpu0": { "operation": "idle", "lineState": "shared" },
  "cpu1": { "operation": "idle", "lineState": "shared" },
  "owner": null,
  "invalidations": 0,
  "transfers": 0
}
```

## Events

- CPU0_WRITE
- CPU1_WRITE
- CPU0_READ
- CPU1_READ
- NEXT_STEP
- PREVIOUS_STEP
- RESET
- SELECT_SCENARIO

## Visual rules

- Colour supplementary only.
- Every state has text.
- Every transition updates an event log.
- Reduced motion uses immediate changes.
- Model labelled conceptual.

## Java

- shared counters
- manual padding
- `@Contended` with JVM flag caveat
- JMH group benchmark

## Rust

- adjacent `AtomicU64`
- `repr(align(64))`
- Criterion benchmark
- alignment assumptions documented

## Benchmark disclosure

CPU, topology, SMT, OS, JVM, Rust version, tools, pinning, warm-up, duration, raw data.
