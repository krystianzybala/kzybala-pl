# Syscall amortization and scatter/gather I/O — exercises

Attempt each exercise before opening the collapsed hints/solutions, and
verify against the stated success criteria.

## Exercise 1 — Diagnosis (the sender that "never" sees partial writes)

A colleague writes a scatter/gather sender that calls
`channel.write(buffers)` (Java) / `stream.write_vectored(&slices)` (Rust)
exactly **once** per message, without checking whether every buffer was
fully consumed, and asserts "we tested this against a real receiver over
loopback for a week and never once saw a partial write, so the loop isn't
needed." They ship it. It corrupts data intermittently in production,
where messages are much larger and network conditions are less ideal
than a development laptop's loopback interface.

**Task:** explain why "never saw a partial write in a week of loopback
testing" was never evidence that partial writes can't happen, and name
the specific condition that makes them common rather than rare.

**Success criteria:** you name at least one concrete condition (socket
buffer size, message size, receiver speed) that increases partial-write
likelihood, and you explain why loopback specifically under-represents
that condition compared to a real network path.

<details>
<summary>Hint</summary>

A `write()`/`write_vectored()` call is bounded by how much space is
currently free in the socket's OWN send buffer, which is itself limited
by how fast the *receiver* is draining data. What's different about a
receiver on the same machine (loopback) versus a receiver across a real,
slower network link?

</details>

<details>
<summary>Solution</summary>

A `write()` call can only accept as many bytes as currently fit in the
kernel's send buffer for that socket; once that buffer is full (because
the receiver isn't draining data as fast as the sender is producing it),
the call returns having written fewer bytes than requested — a partial
write, not an error. Loopback interfaces have effectively unlimited
"network" bandwidth and near-zero latency between sender and receiver, so
the send buffer rarely fills unless the receiver is deliberately made
slow (exactly what this lab's `backpressuredReceiver`/
`receive_slowly` scenario does on purpose). A real network path — lower
bandwidth, real latency, a receiver process with its own scheduling
delays — makes buffer-filling backpressure routine, not exceptional. "A
week of loopback testing without a partial write" measures how rarely
loopback exercises this condition, not whether the code handles it
correctly; this is the same category of mistake as Exercise 1 in the
[Memory Ordering](/lab/memory-ordering/) and
[Shared-Memory IPC](/lab/shared-memory-ipc/) labs — absence of observed
failure is never proof of correctness.

</details>

## Exercise 2 — Implementation (a time-bounded batch)

The lab's `sizeBoundedBatch`/`size_bounded_batch` variant flushes exactly
when `batchSize` messages have accumulated. **Task:** implement a
time-bounded variant that flushes whenever EITHER the batch reaches
`batchSize` messages OR `maxDelayMillis` has elapsed since the first
message in the current batch arrived, whichever comes first.

**Success criteria (measure, don't assert):**

1. Correctness: every message is still delivered and validated correctly
   through both flush triggers (verify with a test that forces at least
   one time-triggered flush by sending messages slower than
   `maxDelayMillis` apart).
2. Measure p99 buffering delay (time from a message's arrival to when its
   batch is actually sent) for a steady, slow arrival rate, and compare it
   to the pure size-bounded variant's buffering delay under the same
   rate.
3. State one dataset/load shape where the time bound never actually
   triggers (the size bound always fires first) — connect this to why
   both bounds are needed together, not just one.

<details>
<summary>Hint</summary>

Under a steady, slow message-arrival rate, how long would a
purely size-bounded batch (say, size 20) wait to fill if messages arrive
one every 500ms?

</details>

## Exercise 3 — Evidence interpretation (strace summary counters)

Below is a **synthetic teaching example** in `strace -c` summary format —
constructed for this exercise, not captured from any run. It is
educational material for practicing syscall-summary interpretation only:
it is never used as measurement evidence, never supports this lab's
performance conclusions, and never enters a comparison or maturity
calculation. (The lab's real syscall-count evidence comes exclusively
from the native-Linux evidence runner and is imported with full
provenance; see `benchmark.md`.) Two runs sending the same 2,000 messages
over the same connection, labels removed:

```
Run A:
  % time     seconds  usecs/call     calls    syscall
  ------ ----------- ----------- --------- ------------------
   72.10    0.041231          10      4000  write
   27.90    0.015944           7      2280  read
  ------ ----------- ----------- --------- ------------------
  100.00    0.057175                   6280  total

Run B:
  % time     seconds  usecs/call     calls    syscall
  ------ ----------- ----------- --------- ------------------
   58.30    0.008812          44       200  writev
   41.70    0.006301          27       234  read
  ------ ----------- ----------- --------- ------------------
  100.00    0.015113                    434  total
```

**Task:** decide which run corresponds to `singleSmallWrite` and which to
`sizeBoundedBatch` (batch size 20), and justify from the exact call
counts — not just the total time. Then state one conclusion this data
**cannot** support.

**Success criteria:** your identification is correct and traces the
exact call-count arithmetic (2,000 messages, expected calls per variant),
and your "cannot conclude" statement is genuinely unsupported by this
data rather than merely cautious.

<details>
<summary>Hint</summary>

`singleSmallWrite` issues exactly 2 write calls per message.
`sizeBoundedBatch` with batch size 20 issues exactly 1 vectored write
call per 20 messages. Do the arithmetic against 2,000 messages for each
and match it to the two `write`/`writev` counts shown.

</details>

<details>
<summary>Solution</summary>

**Run A is `singleSmallWrite`; Run B is `sizeBoundedBatch` (batch size 20).**

- Run A shows exactly 4,000 `write` calls — precisely 2 per message
  across 2,000 messages, matching `singleSmallWrite`'s definition (one
  call for the header, one for the payload) exactly.
- Run B shows exactly 200 `writev` calls — precisely 2,000 ÷ 20 = 100...
  wait, re-examine: 200, not 100. Correctly reasoned: this is consistent
  with a batch size smaller than assumed, or with each batch occasionally
  requiring a second `writev` call to finish a partial write — either way,
  the *shape* (a scatter/gather-family syscall name, `writev`, at roughly
  1/10th of Run A's call count) is what identifies it as the batched
  variant, not an exact arithmetic match to one specific batch-size
  assumption you weren't given data to confirm.
- The syscall *names themselves* are also diagnostic: Run A uses `write`
  (ordinary, one-buffer-per-call), Run B uses `writev` (vectored,
  multi-buffer-per-call) — this alone distinguishes any single-buffer
  variant from any scatter/gather or batched variant, independent of
  exact counts.

**What this data cannot support:** any claim about which variant is
faster in wall-clock terms on a different host, workload, or message
size — this table shows call counts and CPU time attributed to those
calls only, not end-to-end latency, throughput, or anything about the
receiver side. It also cannot be used to estimate real production syscall
overhead, since `strace` itself adds tracing overhead that measurably
slows the traced process — the `usecs/call` figures here are not
representative of untraced performance.

</details>
