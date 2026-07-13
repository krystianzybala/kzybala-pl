# Design

## Scenarios

1. Normal produce/consume
2. Wrap-around
3. Full buffer
4. Empty buffer
5. Stale cached cursor
6. Batch publication
7. Incorrect ordering bug

## State

Track:
- capacity
- head
- tail
- cachedHead
- cachedTail
- slots
- published slots
- producer state
- consumer state
- dropped/rejected writes

## Correctness

The lab MUST explicitly separate:
- reservation
- payload write
- publication
- payload read
- consumption acknowledgement
