# Design

## Scenarios

1. Broken publication
2. Release/acquire message passing
3. Relaxed counter
4. Store-buffering litmus test
5. Sequential consistency comparison

## Interactive state

```json
{
  "scenario": "message-passing",
  "step": 0,
  "thread0": {"pc": 0, "buffer": []},
  "thread1": {"pc": 0, "buffer": []},
  "memory": {"data": 0, "flag": 0},
  "observations": [],
  "ordering": "relaxed"
}
```

## Visualisation

Display:
- thread-local program order
- store buffers
- shared memory
- synchronisation edge
- observed reads
- happens-before graph

## Java mapping

- plain access
- getOpaque/setOpaque
- getAcquire/setRelease
- getVolatile/setVolatile

## Rust mapping

- Relaxed
- Acquire
- Release
- AcqRel
- SeqCst

The page MUST state that language-level semantics differ and direct one-to-one equivalence is approximate.
