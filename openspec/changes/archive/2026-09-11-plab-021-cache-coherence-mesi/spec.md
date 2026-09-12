# Requirements

## Requirement: Explain coherence

The lab MUST explain cache coherence as a mechanism ensuring a coherent view of a cache line across participating cores.

### Acceptance criteria

- Distinguishes coherence from consistency.
- Distinguishes coherence from memory ordering.
- Explains ownership, sharing, invalidation, and write-back.
- Avoids universal claims about one protocol.

## Requirement: Interactive state transitions

The lab MUST expose explicit state transitions for a MESI-style model.

### Acceptance criteria

- Two cores can read, write, and evict.
- Each cache copy has a visible textual state.
- Ownership transfer is visible.
- Invalidations and write-backs are counted.
- Reset is deterministic.
- All controls are keyboard accessible.

## Requirement: Protocol caveats

The lab MUST explain that MESI is a teaching model, not a universal implementation contract.

### Acceptance criteria

- Mentions MESIF, MOESI, directory-based protocols, and proprietary variants.
- Avoids claiming Apple Silicon uses the exact displayed protocol.
- Labels the visualisation conceptual.

## Requirement: Java and Rust examples

The lab MUST include buildable examples showing shared-writer and ownership-oriented approaches.

### Acceptance criteria

- Java example compiles.
- Rust example compiles.
- Memory-ordering choices are documented.
- Examples do not claim to directly control coherence states.

## Requirement: Diagnostic methodology

The lab MUST explain how coherence traffic can be investigated on supported Linux systems.

### Acceptance criteria

- Includes perf c2c methodology.
- Includes hardware and OS caveats.
- Does not imply perf c2c works identically on all platforms.
- Includes an investigation task.

## Requirement: Educational completion

The lab MUST include:
- common mistakes
- when coherence matters
- when it is not the bottleneck
- at least three review questions
- authoritative sources
