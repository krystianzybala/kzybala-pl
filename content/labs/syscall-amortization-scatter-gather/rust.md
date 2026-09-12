# Syscall amortization and scatter/gather I/O — Rust

## Wire format and accounting

Identical to Java: a 16-byte header (8-byte LE sequence + 8-byte LE
payload length) followed by a deterministic payload.

```rust
pub struct WriteAccounting {
    pub write_calls: u64,   // honest proxy for kernel crossings — see theory.md
    pub bytes_written: u64,
    pub partial_writes: u64,
    pub messages_sent: u64,
}
```

## Single small write and coalesced buffer

```rust
// Single small write: two separate write() calls per message.
for buf in [&header, &payload] {
    let (wc, bw, pw) = write_all_counted(stream, buf)?; // loop handles partial writes
}

// Coalesced buffer: one copy, then one write() call.
let mut combined = Vec::with_capacity(header.len() + payload.len());
combined.extend_from_slice(&header);
combined.extend_from_slice(&payload);
```

## Scatter/gather write — the partial-write state machine

```rust
fn write_vectored_all(stream: &mut TcpStream, buffers: &[Vec<u8>]) -> IoResult<WriteAccounting> {
    let mut offsets = vec![0usize; buffers.len()];
    loop {
        let slices: Vec<IoSlice> = buffers.iter().zip(&offsets)
            .filter(|(b, &off)| off < b.len())
            .map(|(b, &off)| IoSlice::new(&b[off..]))
            .collect();
        if slices.is_empty() { break; }
        let n = stream.write_vectored(&slices)?; // ONE gathering write() call, no copy
        // advance each buffer's own offset by however much of `n` it consumed
    }
}
```

Unlike Java's `ByteBuffer[]`, Rust's `IoSlice<'a>` does not mutate in
place to track how much of it was consumed — a partial vectored write
requires the caller to reconstruct the remaining slices itself. This
function rebuilds the `IoSlice` array fresh from each buffer's own
tracked offset every loop iteration (rather than trying to "advance" an
`IoSlice` in place, which runs into `IoSlice`'s borrow-lifetime
constraints) — the explicit "partial-write state machine" design.md's
Rust track calls for.

## Size-bounded batch

```rust
pub fn size_bounded_batch(stream: &mut TcpStream, message_count: u64, payload_size: usize, batch_size: u64)
    -> IoResult<WriteAccounting>
{
    // accumulates batch_size messages' header+payload Vec<u8> buffers,
    // then calls write_vectored_all ONCE for the whole batch
}
```

## Backpressured receiver scenario

```rust
pub fn receive(stream: &mut TcpStream, message_count: u64, per_message_delay_millis: u64) -> IoResult<u64>
```

A non-zero `per_message_delay_millis` deliberately slows the receiver;
paired with `scatter_gather_write` sending large-enough payloads, this
reliably induces real partial writes on the sender side —
`backpressured_receiver_still_delivers_every_message_despite_induced_partial_writes`
verifies every byte still arrives correctly.

## Correctness gate

`cargo test --release` asserts the same properties as the Java suite:
full delivery, expected write-call counts per variant, and correct
behavior under induced backpressure. `cargo clippy --release --all-targets
-- -D warnings` and `cargo fmt --check` both run clean.

## Criterion benchmark

The Criterion harness benchmarks all four throughput-measurable variants
across two of the three dataset profiles, on the pinned release profile.

The runnable Cargo/Criterion project (with correctness tests in
`src/lib.rs`) is at
<a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/syscall-amortization-scatter-gather/code/rust" rel="noopener"><code>content/labs/syscall-amortization-scatter-gather/code/rust/</code></a>
in this site's repository.
