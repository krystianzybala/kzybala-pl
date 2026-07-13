# Design

## Framework

Reuse the shared Performance Lab framework and metadata contract.

## Scenarios

1. Single reader
2. Two readers
3. Reader then writer
4. Competing writers
5. Eviction and write-back
6. Cache-to-cache transfer

## State model

```json
{
  "scenario": "two-readers",
  "step": 0,
  "memoryValue": 0,
  "cpu0": {"state": "Invalid", "value": null},
  "cpu1": {"state": "Invalid", "value": null},
  "owner": null,
  "invalidations": 0,
  "transfers": 0,
  "writeBacks": 0,
  "eventLog": []
}
```

## Events

- CPU0_READ
- CPU1_READ
- CPU0_WRITE
- CPU1_WRITE
- CPU0_EVICT
- CPU1_EVICT
- NEXT_STEP
- PREVIOUS_STEP
- RESET
- SELECT_SCENARIO

## Visual model

The lab MUST show:

- one conceptual memory line
- two private cache copies
- state label for each cache copy
- ownership and sharing
- transfer direction
- invalidation count
- write-back count
- textual event log

## Caveat banner

The page MUST state:

> Conceptual MESI-style model. Real processors may implement MESIF, MOESI, directory-based coherence, proprietary protocols, different replacement policies, and undocumented microarchitectural behaviour.

## Code examples

Java:
- AtomicLong shared-writer example
- single-writer ownership example
- perf c2c instructions

Rust:
- AtomicU64 shared-writer example
- single-owner thread example
- perf c2c instructions where applicable

## Benchmark methodology

The lab SHOULD avoid presenting a synthetic "MESI benchmark" as universal truth.

It MAY include measured coherence-sensitive examples if:
- CPU and topology are disclosed
- thread placement is disclosed
- units are clear
- raw output is retained
- platform limitations are explicit
