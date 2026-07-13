# Design

## Scenarios

1. Single thread
2. Two contenders
3. Many contenders
4. Fixed backoff
5. Exponential backoff
6. Single-writer comparison

## State

Track:
- value
- contenders
- attempts
- successful CAS
- failed CAS
- retries
- backoff delay
- ownership transfers
- completion latency

All displayed latency values are simulated unless backed by disclosed benchmark data.
