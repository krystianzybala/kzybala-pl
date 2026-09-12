# Object serialization vs fixed binary layout — Rust

## The shared message shape

```rust
pub const MAX_SAMPLES: usize = 8;
pub const WIRE_SIZE: usize = 1 + 8 + 4 + 1 + 1 + 8 + 8 + 2 + 4 * MAX_SAMPLES + 1 + 4; // 70

#[derive(Debug, Clone, PartialEq)]
pub struct Event {
    pub version: u8, pub id: u64, pub device_id: u32, pub opcode: u8, pub flags: u8,
    pub value: f64, pub timestamp: u64, pub sample_count: u16,
    pub samples: [i32; MAX_SAMPLES], pub optional_present: bool, pub optional_field: i32,
}
```

Field offsets and byte order (little-endian throughout) are identical to
the Java `ByteBufferCodec`/`EventView` layout, so `encode()` here and
`ByteBufferCodec.encode()` on the Java side produce byte-for-byte
identical wire output for the same logical message — asserted directly in
this crate's tests and independently in Java's.

## Owned decode

```rust
pub fn decode_owned(wire: &[u8]) -> Event {
    let mut samples = [0i32; MAX_SAMPLES];
    for (i, sample) in samples.iter_mut().enumerate() {
        let off = OFF_SAMPLES + 4 * i;
        *sample = i32::from_le_bytes(wire[off..off + 4].try_into().unwrap());
    }
    Event { /* every field read out of `wire` at its fixed offset */ ..Default::default() }
}
```

Every field is copied out of `wire` into a fresh, fully-owned `Event` with
no lifetime tied back to the input — simple and correct, but the
`[i32; 8]` array and every scalar field are copied whether or not the
caller ends up using them.

## Borrowed decode

```rust
pub struct EventView<'a> { wire: &'a [u8] }

impl<'a> EventView<'a> {
    pub fn device_id(&self) -> u32 {
        u32::from_le_bytes(self.wire[OFF_DEVICE_ID..OFF_DEVICE_ID + 4].try_into().unwrap())
    }
    // ... one accessor per field, each reading directly from `self.wire`
}
```

`EventView` holds only a borrowed `&'a [u8]` — genuinely zero-copy,
zero-allocation: no field is read until its accessor is called, and no
intermediate `Event` is ever built unless `to_owned_event()` is invoked
explicitly. This is the Rust-side analogue of Java's FFM `EventView`
flyweight, but with no copy at all (Rust's borrow checker lets the view
alias the input slice directly; Java's FFM `MemorySegment` needed one copy
into off-heap memory first — a genuine, disclosed asymmetry between the
two languages' zero-copy stories, not a claim that one "wins").

## Correctness gate

`cargo test` asserts, for the same four fixture profiles as Java: owned
round-trip equality, that every `EventView` accessor matches the
corresponding field on the owned decode, that `to_owned_event()` recovers
the exact original `Event`, and the same versioned-optional-field
always-readable-slot behavior Java's suite checks. `cargo clippy --release
-- -D warnings` and `cargo fmt --check` both run clean.

## Criterion benchmark

The Criterion harness benchmarks `encode`, `decode_owned`, and a
`decode_borrowed_device_id_only` case (reading just `device_id()` through
`EventView` without ever materializing an `Event`) across the
`medium_event` and `repeated_fields` profiles, on the pinned release
profile (`[profile.bench] inherits = "release"` in `Cargo.toml`).

The runnable Cargo/Criterion project (with correctness tests in
`src/lib.rs`) is at
<a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/fixed-binary-serialization/code/rust" rel="noopener"><code>content/labs/fixed-binary-serialization/code/rust/</code></a>
in this site's repository.
