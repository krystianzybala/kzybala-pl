# UDP ingest, batching and packet loss — Rust

## Wire format

Identical to Java: an 8-byte little-endian sequence prefix followed by a
deterministic payload pattern, fixed to one of the three datagram-size
profiles. `send_burst`/`send_steady` connect exclusively to
`127.0.0.1` — this lab tests over loopback only, labeled everywhere.

## Raw receive variants

```rust
pub fn receive_allocating_per_packet(&self, message_count: u64, datagram_size: usize) -> std::io::Result<u64> {
    for _ in 0..message_count {
        let mut buf = vec![0u8; datagram_size]; // fresh allocation every call
        let (len, _) = self.socket.recv_from(&mut buf)?;
        // validate and discard
    }
}

pub fn receive_with_reused_buffer(&self, message_count: u64, datagram_size: usize) -> std::io::Result<u64> {
    let mut buf = vec![0u8; datagram_size]; // allocated once
    for _ in 0..message_count {
        let (len, _) = self.socket.recv_from(&mut buf)?;
        // validate and discard
    }
}
```

## Copying handoff

```rust
pub fn run(&self, message_count: u64, datagram_size: usize) -> std::io::Result<IngestResult> {
    let (tx, rx) = sync_channel(self.capacity); // BOUNDED channel
    let consumer = thread::spawn(move || { /* drains rx, validates, counts */ });

    let mut buf = vec![0u8; datagram_size];
    for _ in 0..message_count {
        let (len, _) = self.socket.recv_from(&mut buf)?;
        let copy = buf[..len].to_vec(); // one allocation + copy per datagram
        if tx.try_send(copy).is_err() {
            application_dropped += 1; // explicit, counted — never silent
        }
    }
    // drop(tx) signals the consumer; join
}
```

`try_send` (non-blocking) rather than the blocking `send` is what turns a
full channel into an explicit, counted drop instead of stalling the
receive loop — same reasoning as Java's `queue.offer(...)`.

## Zero-copy view handoff with a bounded lifetime

```rust
/// # Safety
/// Sound only because the receiver never reuses slot `i % slab_size`
/// until the consumer has sent it back on `freed`, confirming the read
/// is done — see the struct-level isolation note.
unsafe impl Sync for Slab { /* UnsafeCell-backed buffers */ }

for i in 0..message_count {
    let idx = (i % slab_size) as usize;
    while let Ok(freed) = freed_rx.try_recv() { in_flight[freed] = false; }
    if in_flight[idx] {
        self.socket.recv_from(&mut discard_buf)?; // still drains the socket
        application_dropped += 1;                  // explicit drop, not silent
        continue;
    }
    unsafe {
        let target = slab.get_mut(idx);   // ZERO-COPY: OS writes directly into the slab slot
        target.clear(); target.resize(datagram_size, 0);
        let (len, _) = self.socket.recv_from(target)?;
        target.truncate(len);
    }
    in_flight[idx] = true;
    ready_tx.send(Some(idx))?; // hand the consumer an INDEX, not a copy
}
```

`Slab` wraps its buffers in `UnsafeCell` and is marked `unsafe impl Sync`
so an `Arc<Slab>` can cross the thread boundary — sound only because of
the exact protocol above (check `in_flight` before writing, never after),
which the type system cannot express on its own. This is the same
category of reasoning as `shared-memory-ipc`'s `AtomicU64`-from-raw-pointer
pattern: one small, isolated, documented unsafe boundary standing in for
an invariant enforced by a hand-verified protocol rather than the
compiler. `#[allow(clippy::mut_from_ref)]` on the one accessor that needs
it is deliberate and documented, not a suppressed real warning.

## Correctness gate

`cargo test --release` sends real UDP datagrams over loopback and asserts
the same properties as the Java suite: full accounting, zero corruption,
full delivery with ample capacity, explicit non-zero drops with an
undersized channel/slab. `cargo clippy --release --all-targets -- -D
warnings` and `cargo fmt --check` both run clean.

## Criterion benchmark

The Criterion harness benchmarks all four measurable variants across the
same three datagram-size profiles as Java, on the pinned release profile.

The runnable Cargo/Criterion project (with correctness tests in
`src/lib.rs`) is at
<a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/udp-ingest-batching/code/rust" rel="noopener"><code>content/labs/udp-ingest-batching/code/rust/</code></a>
in this site's repository.
