# Memory-mapped files and page faults — Rust

## The shared record format

```rust
pub const RECORD_SIZE: usize = 64;
pub fn expected_value(id: u64) -> u64 { id.wrapping_mul(2_654_435_761).wrapping_add(1) }
pub fn expected_payload_byte(id: u64, index: usize) -> u8 { (id.wrapping_add(index as u64) & 0xFF) as u8 }
pub fn checksum_record(id: u64, value: u64, payload: &[u8]) -> u64 { /* ... */ }
```

Identical derivation formulas to the Java side, asserted against the same
values in both languages' test suites.

## Buffered read

```rust
pub fn buffered_read_checksum(path: &Path, record_count: u64) -> IoResult<u64> {
    let mut file = File::open(path)?;
    let mut buf = [0u8; RECORD_SIZE];
    for _ in 0..record_count {
        file.read_exact(&mut buf)?; // one explicit read() per record
        // decode + fold into checksum
    }
    Ok(checksum)
}
```

## Mmap sequential and random read (via `memmap2`)

The standard library has no direct `mmap` API — this lab uses the
`memmap2` crate, a small, widely-used, safe-API wrapper that design.md
explicitly names as this track's Rust focus ("memmap or direct mmap
wrapper with safe slice boundaries").

```rust
/// # Safety / isolation note
/// `Mmap::map` is `unsafe` because the file could be mutated by another
/// process while mapped — undefined behavior, not an I/O error. This
/// function is the ONLY unsafe call site for the read path in this crate;
/// every other function only ever touches the resulting safe `&[u8]`.
pub fn map_readonly(path: &Path) -> IoResult<Mmap> {
    let file = File::open(path)?;
    unsafe { Mmap::map(&file) } // SAFETY: see isolation note above
}

pub fn mmap_sequential_checksum(mapped: &Mmap, record_count: u64) -> u64 {
    for i in 0..record_count as usize {
        let off = i * RECORD_SIZE;
        let (id, value, payload) = decode_record(&mapped[off..off + RECORD_SIZE]);
        checksum = checksum.wrapping_add(checksum_record(id, value, payload));
    }
    checksum
}
```

Once mapped, `Mmap` derefs to `&[u8]` — every subsequent read in this
module is ordinary safe slice indexing; the single `unsafe` block is
confined to the mapping call itself and documented with a `# Safety`
section per the "isolate and document unsafe code" requirement.
`mmap_random_checksum` performs the identical decode over a
caller-supplied permutation of record indices instead of sequential
order — the Rust-side twin of Java's `readRandomChecksum`.

## Warm vs cold

The Criterion benchmark's `warm_mmap_sequential_read` reuses one `Mmap`
across all samples after an untimed warm-up pass; `cold_mapped_sequential_read`
calls `map_readonly` fresh inside every `b.iter(...)` closure. As in the
Java benchmark, this isolates "fresh mapping + first traversal" cost from
"reused, already-touched mapping" cost — see theory.md's "Assumptions and
scope" for why this is not proof of true OS-page-cache eviction on this
development machine.

## Mapped write and flush

```rust
/// # Safety / isolation note
/// Same constraint as `map_readonly`: concurrent external mutation of the
/// backing file during `MmapMut::map_mut` is undefined behavior. This is
/// the only unsafe call site for the write path in this crate.
pub fn map_readwrite(path: &Path, file_size: u64) -> IoResult<MmapMut> {
    let file = OpenOptions::new().read(true).write(true).create(true).truncate(true).open(path)?;
    file.set_len(file_size)?;
    unsafe { MmapOptions::new().map_mut(&file) } // SAFETY: see isolation note above
}

pub fn write_all_records(mapped: &mut MmapMut, record_count: u64) { /* writes every record */ }
pub fn flush(mapped: &MmapMut) -> IoResult<()> { mapped.flush() } // forces dirty pages to storage
```

`write_all_records` and `flush` are separate functions for the same
reason as the Java side: the benchmark measures the memory-only write
loop and the storage-forcing flush as two distinct costs.

## Correctness gate

`cargo test` asserts the same properties as the Java suite over the same
small `fixedRecords` fixture: buffered read, mmap sequential read, mmap
random read (same deterministic permutation algorithm — an LCG-driven
Fisher-Yates shuffle, verified to produce the identical index order as
Java's for the same seed), and a full mmap-write-then-read-back round
trip all reproduce the exact same checksum. `cargo clippy --release -- -D
warnings` and `cargo fmt --check` both run clean.

## Criterion benchmark

The Criterion harness benchmarks all five variants over the same 64 MiB /
1,048,576-record dataset as Java, on the pinned release profile
(`[profile.bench] inherits = "release"`).

The runnable Cargo/Criterion project (with correctness tests in
`src/lib.rs`) is at
<a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/memory-mapped-files/code/rust" rel="noopener"><code>content/labs/memory-mapped-files/code/rust/</code></a>
in this site's repository.
