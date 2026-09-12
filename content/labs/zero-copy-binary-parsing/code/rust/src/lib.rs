//! Deterministic source-data generation, wire-format encoding and the
//! five decoder variants — the cross-language equivalence contract
//! (../fixtures/zero-copy-binary-parsing-fixtures.json). Every frame is
//! big-endian (network byte order): `msg_type`(1B) + `seq`(4B BE u32) +
//! a dataset-specific length/count field(4B BE u32) + payload
//! (variable). Every decoder variant reads the identical encoded bytes
//! and reproduces the identical checksum; decoding strategy changes
//! ns/message, B/message, bytes copied and validation cost, never the
//! result.
//!
//! Rust's borrowed `&[u8]` slice IS a zero-copy view by construction —
//! there is no separate "flyweight reader" type to build the way Java's
//! `MemorySegment`-backed approach needs one. `validated_zero_copy_view`
//! below never allocates; `mutable_in_place_update` needs a `&mut [u8]`
//! specifically, and the borrow checker enforces that no read-only view
//! of the same bytes can be alive at the same time — a compile-time,
//! not runtime, version of this lab's "returning views past buffer
//! lifetime" trap (rust.md).

pub fn xorshift64(x: u64) -> u64 {
    let mut x = x;
    x ^= x << 13;
    x ^= x >> 7;
    x ^= x << 17;
    x
}

fn read_u32_be(buf: &[u8], offset: usize) -> u32 {
    u32::from_be_bytes(buf[offset..offset + 4].try_into().unwrap())
}

fn read_u64_be(buf: &[u8], offset: usize) -> u64 {
    u64::from_be_bytes(buf[offset..offset + 8].try_into().unwrap())
}

fn write_u32_be(buf: &mut [u8], offset: usize, value: u32) {
    buf[offset..offset + 4].copy_from_slice(&value.to_be_bytes());
}

pub mod fixed_header {
    use super::*;

    pub const N: usize = 200_000;
    const PAYLOAD_WORD_CYCLE: [usize; 4] = [4, 8, 16, 32];

    pub fn encode(n: usize) -> (Vec<u8>, Vec<usize>) {
        let mut offsets = Vec::with_capacity(n);
        let mut total = 0usize;
        for i in 0..n {
            offsets.push(total);
            total += 9 + PAYLOAD_WORD_CYCLE[i % 4] * 8;
        }
        let mut buf = vec![0u8; total];
        let mut x: u64 = 110;
        for i in 0..n {
            let base = offsets[i];
            let words = PAYLOAD_WORD_CYCLE[i % 4];
            buf[base] = (i % 4) as u8;
            write_u32_be(&mut buf, base + 1, i as u32);
            write_u32_be(&mut buf, base + 5, words as u32);
            for w in 0..words {
                x = xorshift64(x);
                let v = x % 1_000_000;
                buf[base + 9 + w * 8..base + 9 + w * 8 + 8].copy_from_slice(&v.to_be_bytes());
            }
        }
        (buf, offsets)
    }

    fn validate(buf: &[u8], base: usize) {
        assert!(
            base + 9 <= buf.len(),
            "frame header out of bounds at offset {base}"
        );
        let words = read_u32_be(buf, base + 5) as usize;
        assert!(
            base + 9 + words * 8 <= buf.len(),
            "frame payload out of bounds at offset {base}"
        );
    }

    fn sum_viewed(buf: &[u8], base: usize) -> u64 {
        let msg_type = buf[base] as u64;
        let seq = read_u32_be(buf, base + 1) as u64;
        let words = read_u32_be(buf, base + 5) as usize;
        let mut sum = msg_type + seq;
        for w in 0..words {
            sum = sum.wrapping_add(read_u64_be(buf, base + 9 + w * 8));
        }
        sum
    }

    pub fn expected_checksum(buf: &[u8], offsets: &[usize], n: usize) -> u64 {
        let mut sum = 0u64;
        for &base in offsets.iter().take(n) {
            sum = sum.wrapping_add(sum_viewed(buf, base));
        }
        sum
    }

    /// Copies the payload into a fresh `Vec<u64>` before summing.
    pub fn copying_decoder(buf: &[u8], offsets: &[usize], n: usize) -> u64 {
        let mut sum = 0u64;
        for &base in offsets.iter().take(n) {
            validate(buf, base);
            let words = read_u32_be(buf, base + 5) as usize;
            let mut payload = Vec::with_capacity(words);
            for w in 0..words {
                payload.push(read_u64_be(buf, base + 9 + w * 8));
            }
            sum = sum
                .wrapping_add(buf[base] as u64)
                .wrapping_add(read_u32_be(buf, base + 1) as u64);
            for v in payload {
                sum = sum.wrapping_add(v);
            }
        }
        sum
    }

    /// Individually heap-boxes every value — Rust's real-allocation analog of Java's boxed `List<Long>`.
    pub fn object_building_decoder(buf: &[u8], offsets: &[usize], n: usize) -> u64 {
        let mut sum = 0u64;
        for &base in offsets.iter().take(n) {
            validate(buf, base);
            let words = read_u32_be(buf, base + 5) as usize;
            let mut payload: Vec<Box<u64>> = Vec::with_capacity(words);
            for w in 0..words {
                payload.push(Box::new(read_u64_be(buf, base + 9 + w * 8)));
            }
            sum = sum
                .wrapping_add(buf[base] as u64)
                .wrapping_add(read_u32_be(buf, base + 1) as u64);
            for v in payload {
                sum = sum.wrapping_add(*v);
            }
        }
        sum
    }

    /// Validates bounds once per message, then reads directly from the borrowed slice — zero allocation.
    pub fn validated_zero_copy_view(buf: &[u8], offsets: &[usize], n: usize) -> u64 {
        let mut sum = 0u64;
        for &base in offsets.iter().take(n) {
            validate(buf, base);
            sum = sum.wrapping_add(sum_viewed(buf, base));
        }
        sum
    }

    /// Defers validation to first field access.
    pub fn lazy_field_decode(buf: &[u8], offsets: &[usize], n: usize) -> u64 {
        let mut sum = 0u64;
        for &base in offsets.iter().take(n) {
            let mut validated = false;
            if !validated {
                validate(buf, base);
                validated = true;
            }
            debug_assert!(validated);
            sum = sum.wrapping_add(sum_viewed(buf, base));
        }
        sum
    }

    /// Round-trips `seq` through an XOR mutation (write, verify, restore) on a `&mut [u8]`, then sums.
    pub fn mutable_in_place_update(buf: &mut [u8], offsets: &[usize], n: usize) -> u64 {
        let mut sum = 0u64;
        for &base in offsets.iter().take(n) {
            validate(buf, base);
            let seq_offset = base + 1;
            let original = read_u32_be(buf, seq_offset);
            let mutated = original ^ 0xFFFF_FFFF;
            write_u32_be(buf, seq_offset, mutated);
            let read_back = read_u32_be(buf, seq_offset);
            assert_eq!(
                read_back, mutated,
                "in-place mutation round-trip failed at offset {seq_offset}"
            );
            write_u32_be(buf, seq_offset, original);
            sum = sum.wrapping_add(sum_viewed(buf, base));
        }
        sum
    }
}

pub mod nested_repeated {
    use super::*;

    pub const N: usize = 150_000;
    const RECORD_COUNT_CYCLE: [usize; 3] = [2, 4, 8];

    pub struct SubRecord {
        pub id: u32,
        pub amount: u64,
    }

    pub fn encode(n: usize) -> (Vec<u8>, Vec<usize>) {
        let mut offsets = Vec::with_capacity(n);
        let mut total = 0usize;
        for i in 0..n {
            offsets.push(total);
            total += 9 + RECORD_COUNT_CYCLE[i % 3] * 12;
        }
        let mut buf = vec![0u8; total];
        let mut x: u64 = 111;
        for i in 0..n {
            let base = offsets[i];
            let count = RECORD_COUNT_CYCLE[i % 3];
            buf[base] = (i % 4) as u8;
            write_u32_be(&mut buf, base + 1, i as u32);
            write_u32_be(&mut buf, base + 5, count as u32);
            let mut rec_base = base + 9;
            for _ in 0..count {
                x = xorshift64(x);
                let id = (x % 1_000_000) as u32;
                x = xorshift64(x);
                let amount = x % 10_000_000;
                write_u32_be(&mut buf, rec_base, id);
                buf[rec_base + 4..rec_base + 12].copy_from_slice(&amount.to_be_bytes());
                rec_base += 12;
            }
        }
        (buf, offsets)
    }

    fn validate(buf: &[u8], base: usize) {
        assert!(
            base + 9 <= buf.len(),
            "frame header out of bounds at offset {base}"
        );
        let count = read_u32_be(buf, base + 5) as usize;
        assert!(
            base + 9 + count * 12 <= buf.len(),
            "frame payload out of bounds at offset {base}"
        );
    }

    fn sum_viewed(buf: &[u8], base: usize) -> u64 {
        let msg_type = buf[base] as u64;
        let seq = read_u32_be(buf, base + 1) as u64;
        let count = read_u32_be(buf, base + 5) as usize;
        let mut sum = msg_type + seq;
        let mut rec_base = base + 9;
        for _ in 0..count {
            sum = sum
                .wrapping_add(read_u32_be(buf, rec_base) as u64)
                .wrapping_add(read_u64_be(buf, rec_base + 4));
            rec_base += 12;
        }
        sum
    }

    pub fn expected_checksum(buf: &[u8], offsets: &[usize], n: usize) -> u64 {
        let mut sum = 0u64;
        for &base in offsets.iter().take(n) {
            sum = sum.wrapping_add(sum_viewed(buf, base));
        }
        sum
    }

    pub fn copying_decoder(buf: &[u8], offsets: &[usize], n: usize) -> u64 {
        let mut sum = 0u64;
        for &base in offsets.iter().take(n) {
            validate(buf, base);
            let count = read_u32_be(buf, base + 5) as usize;
            let mut ids = Vec::with_capacity(count);
            let mut amounts = Vec::with_capacity(count);
            let mut rec_base = base + 9;
            for _ in 0..count {
                ids.push(read_u32_be(buf, rec_base));
                amounts.push(read_u64_be(buf, rec_base + 4));
                rec_base += 12;
            }
            sum = sum
                .wrapping_add(buf[base] as u64)
                .wrapping_add(read_u32_be(buf, base + 1) as u64);
            for i in 0..count {
                sum = sum.wrapping_add(ids[i] as u64).wrapping_add(amounts[i]);
            }
        }
        sum
    }

    pub fn object_building_decoder(buf: &[u8], offsets: &[usize], n: usize) -> u64 {
        let mut sum = 0u64;
        for &base in offsets.iter().take(n) {
            validate(buf, base);
            let count = read_u32_be(buf, base + 5) as usize;
            let mut records: Vec<SubRecord> = Vec::with_capacity(count);
            let mut rec_base = base + 9;
            for _ in 0..count {
                records.push(SubRecord {
                    id: read_u32_be(buf, rec_base),
                    amount: read_u64_be(buf, rec_base + 4),
                });
                rec_base += 12;
            }
            sum = sum
                .wrapping_add(buf[base] as u64)
                .wrapping_add(read_u32_be(buf, base + 1) as u64);
            for r in &records {
                sum = sum.wrapping_add(r.id as u64).wrapping_add(r.amount);
            }
        }
        sum
    }

    pub fn validated_zero_copy_view(buf: &[u8], offsets: &[usize], n: usize) -> u64 {
        let mut sum = 0u64;
        for &base in offsets.iter().take(n) {
            validate(buf, base);
            sum = sum.wrapping_add(sum_viewed(buf, base));
        }
        sum
    }

    pub fn lazy_field_decode(buf: &[u8], offsets: &[usize], n: usize) -> u64 {
        let mut sum = 0u64;
        for &base in offsets.iter().take(n) {
            let mut validated = false;
            if !validated {
                validate(buf, base);
                validated = true;
            }
            debug_assert!(validated);
            sum = sum.wrapping_add(sum_viewed(buf, base));
        }
        sum
    }

    pub fn mutable_in_place_update(buf: &mut [u8], offsets: &[usize], n: usize) -> u64 {
        let mut sum = 0u64;
        for &base in offsets.iter().take(n) {
            validate(buf, base);
            let first_id_offset = base + 9;
            let original = read_u32_be(buf, first_id_offset);
            let mutated = original ^ 0xFFFF_FFFF;
            write_u32_be(buf, first_id_offset, mutated);
            let read_back = read_u32_be(buf, first_id_offset);
            assert_eq!(
                read_back, mutated,
                "in-place mutation round-trip failed at offset {first_id_offset}"
            );
            write_u32_be(buf, first_id_offset, original);
            sum = sum.wrapping_add(sum_viewed(buf, base));
        }
        sum
    }
}

pub mod utf8_field {
    use super::*;

    pub const N: usize = 150_000;
    const TEXT_LEN_CYCLE: [usize; 4] = [8, 16, 32, 64];

    pub fn encode(n: usize) -> (Vec<u8>, Vec<usize>) {
        let mut offsets = Vec::with_capacity(n);
        let mut total = 0usize;
        for i in 0..n {
            offsets.push(total);
            total += 9 + TEXT_LEN_CYCLE[i % 4];
        }
        let mut buf = vec![0u8; total];
        let mut x: u64 = 112;
        for i in 0..n {
            let base = offsets[i];
            let len = TEXT_LEN_CYCLE[i % 4];
            buf[base] = (i % 4) as u8;
            write_u32_be(&mut buf, base + 1, i as u32);
            write_u32_be(&mut buf, base + 5, len as u32);
            for j in 0..len {
                x = xorshift64(x);
                let b = 0x20u32 + (x % 95) as u32;
                buf[base + 9 + j] = b as u8;
            }
        }
        (buf, offsets)
    }

    fn validate(buf: &[u8], base: usize) {
        assert!(
            base + 9 <= buf.len(),
            "frame header out of bounds at offset {base}"
        );
        let len = read_u32_be(buf, base + 5) as usize;
        assert!(
            base + 9 + len <= buf.len(),
            "frame payload out of bounds at offset {base}"
        );
    }

    fn sum_viewed(buf: &[u8], base: usize, len: usize) -> u64 {
        let msg_type = buf[base] as u64;
        let seq = read_u32_be(buf, base + 1) as u64;
        let mut sum = msg_type + seq;
        for j in 0..len {
            sum = sum.wrapping_add(buf[base + 9 + j] as u64);
        }
        sum
    }

    pub fn expected_checksum(buf: &[u8], offsets: &[usize], n: usize) -> u64 {
        let mut sum = 0u64;
        for &base in offsets.iter().take(n) {
            let len = read_u32_be(buf, base + 5) as usize;
            sum = sum.wrapping_add(sum_viewed(buf, base, len));
        }
        sum
    }

    /// Materializes a real, independently-owned `String` — a genuine copy and UTF-8 validation/decode.
    pub fn copying_decoder(buf: &[u8], offsets: &[usize], n: usize) -> u64 {
        let mut sum = 0u64;
        for &base in offsets.iter().take(n) {
            validate(buf, base);
            let len = read_u32_be(buf, base + 5) as usize;
            let text =
                String::from_utf8(buf[base + 9..base + 9 + len].to_vec()).expect("valid UTF-8");
            sum = sum
                .wrapping_add(buf[base] as u64)
                .wrapping_add(read_u32_be(buf, base + 1) as u64);
            for c in text.chars() {
                sum = sum.wrapping_add(c as u64);
            }
        }
        sum
    }

    pub struct DecodedTextMessage {
        pub msg_type: u8,
        pub seq: u32,
        pub text: String,
    }

    /// Wraps the materialized String in a small struct — one more allocation layer than copying_decoder.
    pub fn object_building_decoder(buf: &[u8], offsets: &[usize], n: usize) -> u64 {
        let mut sum = 0u64;
        for &base in offsets.iter().take(n) {
            validate(buf, base);
            let len = read_u32_be(buf, base + 5) as usize;
            let text =
                String::from_utf8(buf[base + 9..base + 9 + len].to_vec()).expect("valid UTF-8");
            let message = DecodedTextMessage {
                msg_type: buf[base],
                seq: read_u32_be(buf, base + 1),
                text,
            };
            sum = sum
                .wrapping_add(message.msg_type as u64)
                .wrapping_add(message.seq as u64);
            for c in message.text.chars() {
                sum = sum.wrapping_add(c as u64);
            }
        }
        sum
    }

    /// Validates UTF-8 validity ONCE (a real `std::str::from_utf8` check, never skipped), then sums raw
    /// bytes directly from the borrowed slice. No `String`, no copy — the checksum never needs one.
    pub fn validated_zero_copy_view(buf: &[u8], offsets: &[usize], n: usize) -> u64 {
        let mut sum = 0u64;
        for &base in offsets.iter().take(n) {
            validate(buf, base);
            let len = read_u32_be(buf, base + 5) as usize;
            let text_bytes = &buf[base + 9..base + 9 + len];
            std::str::from_utf8(text_bytes).expect("valid UTF-8"); // real validation, view stays &[u8]
            sum = sum.wrapping_add(sum_viewed(buf, base, len));
        }
        sum
    }

    /// Defers the UTF-8 validity check to first field access — still a real check, just deferred.
    pub fn lazy_field_decode(buf: &[u8], offsets: &[usize], n: usize) -> u64 {
        let mut sum = 0u64;
        for &base in offsets.iter().take(n) {
            validate(buf, base);
            let len = read_u32_be(buf, base + 5) as usize;
            let mut validated = false;
            if !validated {
                let text_bytes = &buf[base + 9..base + 9 + len];
                std::str::from_utf8(text_bytes).expect("valid UTF-8");
                validated = true;
            }
            debug_assert!(validated);
            sum = sum.wrapping_add(sum_viewed(buf, base, len));
        }
        sum
    }

    /// Round-trips the text's first byte through an XOR mutation (flip low bit, verify, restore), then sums.
    pub fn mutable_in_place_update(buf: &mut [u8], offsets: &[usize], n: usize) -> u64 {
        let mut sum = 0u64;
        for &base in offsets.iter().take(n) {
            validate(buf, base);
            let len = read_u32_be(buf, base + 5) as usize;
            let first_byte_offset = base + 9;
            let original = buf[first_byte_offset];
            let mutated = original ^ 0x01;
            buf[first_byte_offset] = mutated;
            assert_eq!(
                buf[first_byte_offset], mutated,
                "in-place mutation round-trip failed at offset {first_byte_offset}"
            );
            buf[first_byte_offset] = original;
            sum = sum.wrapping_add(sum_viewed(buf, base, len));
        }
        sum
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fixed_header_all_variants_agree() {
        let (buf, offsets) = fixed_header::encode(fixed_header::N);
        let expected = fixed_header::expected_checksum(&buf, &offsets, fixed_header::N);
        assert_eq!(expected, 1_519_448_080_523);

        assert_eq!(
            fixed_header::copying_decoder(&buf, &offsets, fixed_header::N),
            expected
        );
        assert_eq!(
            fixed_header::object_building_decoder(&buf, &offsets, fixed_header::N),
            expected
        );
        assert_eq!(
            fixed_header::validated_zero_copy_view(&buf, &offsets, fixed_header::N),
            expected
        );
        assert_eq!(
            fixed_header::lazy_field_decode(&buf, &offsets, fixed_header::N),
            expected
        );

        let mut mutable_buf = buf.clone();
        assert_eq!(
            fixed_header::mutable_in_place_update(&mut mutable_buf, &offsets, fixed_header::N),
            expected
        );
        assert_eq!(
            mutable_buf, buf,
            "buffer must be unchanged after the mutate/restore round trip"
        );
    }

    #[test]
    fn nested_repeated_all_variants_agree() {
        let (buf, offsets) = nested_repeated::encode(nested_repeated::N);
        let expected = nested_repeated::expected_checksum(&buf, &offsets, nested_repeated::N);
        assert_eq!(expected, 3_861_687_000_575);

        assert_eq!(
            nested_repeated::copying_decoder(&buf, &offsets, nested_repeated::N),
            expected
        );
        assert_eq!(
            nested_repeated::object_building_decoder(&buf, &offsets, nested_repeated::N),
            expected
        );
        assert_eq!(
            nested_repeated::validated_zero_copy_view(&buf, &offsets, nested_repeated::N),
            expected
        );
        assert_eq!(
            nested_repeated::lazy_field_decode(&buf, &offsets, nested_repeated::N),
            expected
        );

        let mut mutable_buf = buf.clone();
        assert_eq!(
            nested_repeated::mutable_in_place_update(
                &mut mutable_buf,
                &offsets,
                nested_repeated::N
            ),
            expected
        );
        assert_eq!(mutable_buf, buf);
    }

    #[test]
    fn utf8_field_all_variants_agree() {
        let (buf, offsets) = utf8_field::encode(utf8_field::N);
        let expected = utf8_field::expected_checksum(&buf, &offsets, utf8_field::N);
        assert_eq!(expected, 11_605_683_083);

        assert_eq!(
            utf8_field::copying_decoder(&buf, &offsets, utf8_field::N),
            expected
        );
        assert_eq!(
            utf8_field::object_building_decoder(&buf, &offsets, utf8_field::N),
            expected
        );
        assert_eq!(
            utf8_field::validated_zero_copy_view(&buf, &offsets, utf8_field::N),
            expected
        );
        assert_eq!(
            utf8_field::lazy_field_decode(&buf, &offsets, utf8_field::N),
            expected
        );

        let mut mutable_buf = buf.clone();
        assert_eq!(
            utf8_field::mutable_in_place_update(&mut mutable_buf, &offsets, utf8_field::N),
            expected
        );
        assert_eq!(mutable_buf, buf);
    }

    #[test]
    fn invalid_utf8_is_rejected() {
        let bad: Vec<u8> = vec![0xC0, 0xC0]; // built at runtime so the compiler can't fold the check away
        assert!(std::str::from_utf8(&bad).is_err());
    }
}
