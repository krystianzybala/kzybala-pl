# Shared-memory IPC — Rust

## Header and slot layout

Identical to Java: `HEADER_SIZE = 192` (capacity/protocolVersion at
offsets 0/4, `writer_seq`/`reader_seq` at offsets 64/128, each on its own
64-byte region), `SLOT_SIZE = 1024` (4-byte slot header + up to 1020
bytes payload), same little-endian byte order throughout.

## Cursor fields via `AtomicU64` over shared memory

```rust
/// # Safety
/// Sound because: offsets (64, 128) are 8-byte aligned (mmap returns
/// page-aligned base addresses); every access to those offsets in this
/// crate goes through this same atomic reference construction, never a
/// plain read/write; the referenced memory outlives every use (owned by
/// `self.mmap`).
unsafe fn atomic_u64_at(mmap: &MmapMut, offset: usize) -> &AtomicU64 {
    let ptr = mmap.as_ptr().add(offset) as *const AtomicU64;
    &*ptr
}
```

The standard library has no API for "treat this byte range of mapped
memory as an atomic integer" — constructing an `&AtomicU64` from a raw
pointer into the mapping is the standard pattern production shared-memory
crates use for exactly this reason (see sources.md). This is the only
`unsafe` reasoning pattern in this crate beyond the mapping calls
themselves; `writer_seq_ref()`/`reader_seq_ref()` are the only two call
sites, and every other read/write in this module (slot payloads) is
ordinary safe slice indexing.

```rust
pub fn try_publish(&mut self, payload: &[u8]) -> bool {
    // reserve -> write_slot -> self.writer_seq_ref().store(.., Ordering::Release)
}
```

Same `Ordering::Release`/`Ordering::Acquire` pairing as the
[SPSC Ring Buffer](/lab/spsc-ring-buffer/) lab's Rust side — the mechanism
is identical; what differs is that `self.mmap` here is backed by a file
another OS process independently maps, not process-heap memory.

## Publish, batch publish, consume, and the zero-copy view

```rust
pub fn try_publish_batch(&mut self, payloads: &[&[u8]]) -> usize { /* several slots, ONE writer_seq store */ }
pub fn try_consume(&mut self, out: &mut [u8]) -> Option<usize> { /* copies the slot out */ }
pub fn try_consume_view(&mut self) -> Option<&[u8]> { /* borrows directly from self.mmap, no copy */ }
```

`try_consume_view` returns a slice borrowed directly from the mapped
memory — Rust's borrow checker enforces that this view cannot outlive the
next mutation through `self`, which is exactly the safety property a
flyweight needs and gets checked at compile time here, unlike Java's
`SlotView` (which relies on documented convention, not the type system,
since `MemorySegment` access isn't lifetime-checked the same way).

## The socket baseline

```rust
pub fn run_socket_baseline(message_count: u64, payload_size: usize) -> IoResult<u64> {
    // a loopback TcpListener/TcpStream pair on one OS thread each
}
```

Same reasoning as the Java side: sockets go through the real kernel
network stack regardless of thread vs. process, so a two-thread structure
is a fair baseline here.

## The real cross-process proof

```
producer <segmentPath> <capacity> <messageCount> <payloadSize> [resume] [startIndex]
consumer <segmentPath> <messageCount> <payloadSize> [startIndex]
```

`tests/process_launcher.rs` launches the `producer` and `consumer`
binaries as genuinely separate OS processes via `std::process::Command`,
waits for the segment file to reach its expected size, and asserts every
message round-trips correctly —
`restarted_consumer_resumes_without_loss_or_duplication` kills a consumer
mid-stream and starts a fresh one that resumes from the segment's own
`reader_seq`, mirroring Java's `ProcessLauncherTest` exactly.

## Correctness gate

`cargo test --release` runs both the fast in-process protocol-logic unit
tests in `src/lib.rs` and the slower `tests/process_launcher.rs`
integration tests (real spawned processes) — both must pass. `cargo
clippy --release --all-targets -- -D warnings` and `cargo fmt --check`
both run clean.

## Criterion benchmark

The Criterion harness benchmarks `socket_baseline`,
`shared_memory_copy_payload`, and `shared_memory_slot_view` across the
same three payload profiles as Java, on the pinned release profile.

The runnable Cargo/Criterion project (with correctness tests in
`src/lib.rs` and the cross-process test in `tests/`) is at
<a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/shared-memory-ipc/code/rust" rel="noopener"><code>content/labs/shared-memory-ipc/code/rust/</code></a>
in this site's repository.
