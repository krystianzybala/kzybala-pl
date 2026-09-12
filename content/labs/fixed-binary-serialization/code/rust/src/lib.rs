//! Fixed binary layout for the `Event` message shared with the Java side.
//! Wire size and field offsets are identical (little-endian) to
//! `EventView`/`ByteBufferCodec` in `code/java/`, so a wire-compatibility
//! test can assert byte-for-byte equality across languages.

pub const MAX_SAMPLES: usize = 8;
pub const WIRE_SIZE: usize = 1 + 8 + 4 + 1 + 1 + 8 + 8 + 2 + 4 * MAX_SAMPLES + 1 + 4;

const OFF_VERSION: usize = 0;
const OFF_ID: usize = 1;
const OFF_DEVICE_ID: usize = 9;
const OFF_OPCODE: usize = 13;
const OFF_FLAGS: usize = 14;
const OFF_VALUE: usize = 15;
const OFF_TIMESTAMP: usize = 23;
const OFF_SAMPLE_COUNT: usize = 31;
const OFF_SAMPLES: usize = 33;
const OFF_OPTIONAL_PRESENT: usize = OFF_SAMPLES + 4 * MAX_SAMPLES;
const OFF_OPTIONAL_FIELD: usize = OFF_OPTIONAL_PRESENT + 1;

/// The "owned decode" variant's target type: every field materialized as a
/// plain Rust value, no reference back into the wire buffer.
#[derive(Debug, Clone, PartialEq)]
pub struct Event {
    pub version: u8,
    pub id: u64,
    pub device_id: u32,
    pub opcode: u8,
    pub flags: u8,
    pub value: f64,
    pub timestamp: u64,
    pub sample_count: u16,
    pub samples: [i32; MAX_SAMPLES],
    pub optional_present: bool,
    pub optional_field: i32,
}

pub fn encode(event: &Event) -> [u8; WIRE_SIZE] {
    let mut buf = [0u8; WIRE_SIZE];
    buf[OFF_VERSION] = event.version;
    buf[OFF_ID..OFF_ID + 8].copy_from_slice(&event.id.to_le_bytes());
    buf[OFF_DEVICE_ID..OFF_DEVICE_ID + 4].copy_from_slice(&event.device_id.to_le_bytes());
    buf[OFF_OPCODE] = event.opcode;
    buf[OFF_FLAGS] = event.flags;
    buf[OFF_VALUE..OFF_VALUE + 8].copy_from_slice(&event.value.to_bits().to_le_bytes());
    buf[OFF_TIMESTAMP..OFF_TIMESTAMP + 8].copy_from_slice(&event.timestamp.to_le_bytes());
    buf[OFF_SAMPLE_COUNT..OFF_SAMPLE_COUNT + 2].copy_from_slice(&event.sample_count.to_le_bytes());
    for i in 0..MAX_SAMPLES {
        let off = OFF_SAMPLES + 4 * i;
        buf[off..off + 4].copy_from_slice(&event.samples[i].to_le_bytes());
    }
    buf[OFF_OPTIONAL_PRESENT] = if event.optional_present { 1 } else { 0 };
    buf[OFF_OPTIONAL_FIELD..OFF_OPTIONAL_FIELD + 4]
        .copy_from_slice(&event.optional_field.to_le_bytes());
    buf
}

/// Variant: owned decode — materializes a fresh, fully-owned [`Event`] with
/// no lifetime tied to `wire`. Correct and simple, but every decode pays for
/// a `[i32; 8]` copy and a full field walk even if the caller only wants one
/// field.
pub fn decode_owned(wire: &[u8]) -> Event {
    assert_eq!(
        wire.len(),
        WIRE_SIZE,
        "wire must be exactly WIRE_SIZE bytes"
    );
    let mut samples = [0i32; MAX_SAMPLES];
    for (i, sample) in samples.iter_mut().enumerate() {
        let off = OFF_SAMPLES + 4 * i;
        *sample = i32::from_le_bytes(wire[off..off + 4].try_into().unwrap());
    }
    Event {
        version: wire[OFF_VERSION],
        id: u64::from_le_bytes(wire[OFF_ID..OFF_ID + 8].try_into().unwrap()),
        device_id: u32::from_le_bytes(wire[OFF_DEVICE_ID..OFF_DEVICE_ID + 4].try_into().unwrap()),
        opcode: wire[OFF_OPCODE],
        flags: wire[OFF_FLAGS],
        value: f64::from_bits(u64::from_le_bytes(
            wire[OFF_VALUE..OFF_VALUE + 8].try_into().unwrap(),
        )),
        timestamp: u64::from_le_bytes(wire[OFF_TIMESTAMP..OFF_TIMESTAMP + 8].try_into().unwrap()),
        sample_count: u16::from_le_bytes(
            wire[OFF_SAMPLE_COUNT..OFF_SAMPLE_COUNT + 2]
                .try_into()
                .unwrap(),
        ),
        samples,
        optional_present: wire[OFF_OPTIONAL_PRESENT] != 0,
        optional_field: i32::from_le_bytes(
            wire[OFF_OPTIONAL_FIELD..OFF_OPTIONAL_FIELD + 4]
                .try_into()
                .unwrap(),
        ),
    }
}

/// Variant: borrowed decode — a zero-copy view holding only `&'a [u8]`.
/// Every accessor reads its field directly from the borrowed slice on
/// demand; nothing is copied or allocated until (and unless) [`EventView::to_owned_event`]
/// is called. This is the Rust-side analogue of Java's `EventView` FFM
/// flyweight.
#[derive(Debug, Clone, Copy)]
pub struct EventView<'a> {
    wire: &'a [u8],
}

impl<'a> EventView<'a> {
    pub fn new(wire: &'a [u8]) -> Self {
        assert_eq!(
            wire.len(),
            WIRE_SIZE,
            "wire must be exactly WIRE_SIZE bytes"
        );
        EventView { wire }
    }

    pub fn version(&self) -> u8 {
        self.wire[OFF_VERSION]
    }

    pub fn id(&self) -> u64 {
        u64::from_le_bytes(self.wire[OFF_ID..OFF_ID + 8].try_into().unwrap())
    }

    pub fn device_id(&self) -> u32 {
        u32::from_le_bytes(
            self.wire[OFF_DEVICE_ID..OFF_DEVICE_ID + 4]
                .try_into()
                .unwrap(),
        )
    }

    pub fn opcode(&self) -> u8 {
        self.wire[OFF_OPCODE]
    }

    pub fn flags(&self) -> u8 {
        self.wire[OFF_FLAGS]
    }

    pub fn value(&self) -> f64 {
        f64::from_bits(u64::from_le_bytes(
            self.wire[OFF_VALUE..OFF_VALUE + 8].try_into().unwrap(),
        ))
    }

    pub fn timestamp(&self) -> u64 {
        u64::from_le_bytes(
            self.wire[OFF_TIMESTAMP..OFF_TIMESTAMP + 8]
                .try_into()
                .unwrap(),
        )
    }

    pub fn sample_count(&self) -> u16 {
        u16::from_le_bytes(
            self.wire[OFF_SAMPLE_COUNT..OFF_SAMPLE_COUNT + 2]
                .try_into()
                .unwrap(),
        )
    }

    pub fn sample(&self, index: usize) -> i32 {
        assert!(index < MAX_SAMPLES, "sample index out of range");
        let off = OFF_SAMPLES + 4 * index;
        i32::from_le_bytes(self.wire[off..off + 4].try_into().unwrap())
    }

    pub fn optional_present(&self) -> bool {
        self.wire[OFF_OPTIONAL_PRESENT] != 0
    }

    pub fn optional_field(&self) -> i32 {
        i32::from_le_bytes(
            self.wire[OFF_OPTIONAL_FIELD..OFF_OPTIONAL_FIELD + 4]
                .try_into()
                .unwrap(),
        )
    }

    /// Materializes the full owned [`Event`] — the cost this view lets a
    /// caller defer or avoid entirely.
    pub fn to_owned_event(&self) -> Event {
        decode_owned(self.wire)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn small_command() -> Event {
        Event {
            version: 1,
            id: 42,
            device_id: 0,
            opcode: 7,
            flags: 1,
            value: 0.0,
            timestamp: 0,
            sample_count: 0,
            samples: [0; MAX_SAMPLES],
            optional_present: false,
            optional_field: 0,
        }
    }

    fn medium_event() -> Event {
        Event {
            version: 1,
            id: 1_000_000_007,
            device_id: 314_159,
            opcode: 2,
            flags: 0,
            value: 98.6,
            timestamp: 1_731_000_000_000,
            sample_count: 0,
            samples: [0; MAX_SAMPLES],
            optional_present: false,
            optional_field: 0,
        }
    }

    fn repeated_fields() -> Event {
        Event {
            version: 1,
            id: 99,
            device_id: 5,
            opcode: 3,
            flags: 0,
            value: 0.0,
            timestamp: 0,
            sample_count: 8,
            samples: [10, -20, 30, -40, 50, -60, 70, -80],
            optional_present: false,
            optional_field: 0,
        }
    }

    fn versioned_optional_field() -> Event {
        Event {
            version: 2,
            id: 555,
            device_id: 8,
            opcode: 9,
            flags: 4,
            value: 0.0,
            timestamp: 0,
            sample_count: 0,
            samples: [0; MAX_SAMPLES],
            optional_present: true,
            optional_field: 123_456,
        }
    }

    fn all_fixtures() -> Vec<Event> {
        vec![
            small_command(),
            medium_event(),
            repeated_fields(),
            versioned_optional_field(),
        ]
    }

    #[test]
    fn owned_round_trips_all_profiles() {
        for event in all_fixtures() {
            let wire = encode(&event);
            assert_eq!(wire.len(), WIRE_SIZE);
            let decoded = decode_owned(&wire);
            assert_eq!(event, decoded);
        }
    }

    #[test]
    fn borrowed_view_matches_owned_decode_without_materializing() {
        for event in all_fixtures() {
            let wire = encode(&event);
            let view = EventView::new(&wire);
            assert_eq!(view.id(), event.id);
            assert_eq!(view.device_id(), event.device_id);
            assert_eq!(view.value().to_bits(), event.value.to_bits());
            assert_eq!(view.timestamp(), event.timestamp);
            assert_eq!(view.sample_count() as usize, event.sample_count as usize);
            for i in 0..MAX_SAMPLES {
                assert_eq!(view.sample(i), event.samples[i]);
            }
            assert_eq!(view.optional_present(), event.optional_present);
            assert_eq!(view.optional_field(), event.optional_field);
            assert_eq!(view.to_owned_event(), event);
        }
    }

    #[test]
    fn versioned_optional_field_slot_is_always_readable() {
        let with_optional = versioned_optional_field();
        let wire = encode(&with_optional);
        let decoded = decode_owned(&wire);
        assert_eq!(decoded.optional_field, 123_456);
        assert!(decoded.optional_present);

        let without_optional = small_command();
        let wire2 = encode(&without_optional);
        let decoded2 = decode_owned(&wire2);
        assert_eq!(decoded2.optional_field, 0);
        assert!(!decoded2.optional_present);
    }

    /// Cross-language wire-compatibility fixture: these exact bytes must
    /// match what Java's ByteBufferCodec/FfmFlyweightCodec produce for the
    /// same fixture (asserted independently in the Java suite via
    /// `byteBufferAndFfmWireBytesAreIdentical`-style equivalence, and here by
    /// construction since both sides use the same offsets/little-endian order
    /// documented in `code/fixtures/fixed-binary-serialization-fixtures.json`).
    #[test]
    fn medium_event_wire_bytes_are_deterministic() {
        let wire = encode(&medium_event());
        assert_eq!(wire[OFF_VERSION], 1);
        assert_eq!(
            u64::from_le_bytes(wire[OFF_ID..OFF_ID + 8].try_into().unwrap()),
            1_000_000_007
        );
        assert_eq!(wire.len(), 70);
    }
}
