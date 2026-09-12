//! Deterministic source-data generation and the five lifecycle variants
//! — the cross-language equivalence contract
//! (../fixtures/arena-lifetimes-reuse-fixtures.json). Every variant
//! reads the identical value stream and reproduces the identical
//! checksum; allocation strategy changes B/op, allocations/op,
//! reset/close cost and contention, never the result.
//!
//! Rust has no GC and no scalar replacement the way the JVM's escape
//! analysis does — a `Box::new` in `allocate_per_item` is a REAL
//! `malloc` call every time, always, unlike Java's `allocatePerItem`
//! (java.md's own measured finding: near-zero bytes/op, because the
//! JIT scalar-replaces the tiny `MessageScratch` away entirely). This
//! is this lab's central Rust-track finding (rust.md): the same source
//! code pattern means structurally different things in each language's
//! runtime model.

pub fn xorshift64(x: u64) -> u64 {
    let mut x = x;
    x ^= x << 13;
    x ^= x >> 7;
    x ^= x << 17;
    x
}

use std::sync::Mutex;

/// An unbounded pool: `borrow()` takes a cached instance or builds a
/// fresh one; `release()` always accepts it back. Deliberately
/// unbounded — this lab's "unbounded pools" trap, mirrored from the
/// Java track's `UnboundedPool`.
pub struct UnboundedPool<T> {
    cache: Mutex<Vec<T>>,
    factory: fn() -> T,
}

impl<T> UnboundedPool<T> {
    pub fn new(factory: fn() -> T) -> Self {
        Self {
            cache: Mutex::new(Vec::new()),
            factory,
        }
    }
    pub fn borrow(&self) -> T {
        self.cache
            .lock()
            .unwrap()
            .pop()
            .unwrap_or_else(self.factory)
    }
    pub fn release(&self, t: T) {
        self.cache.lock().unwrap().push(t);
    }
}

/// A bounded pool: `release()` silently drops the instance once the
/// cache already holds `capacity` items — bounded memory footprint
/// regardless of burst size, mirroring the Java track's `BoundedPool`.
pub struct BoundedPool<T> {
    cache: Mutex<Vec<T>>,
    capacity: usize,
    factory: fn() -> T,
}

impl<T> BoundedPool<T> {
    pub fn new(capacity: usize, factory: fn() -> T) -> Self {
        Self {
            cache: Mutex::new(Vec::with_capacity(capacity)),
            capacity,
            factory,
        }
    }
    pub fn borrow(&self) -> T {
        self.cache
            .lock()
            .unwrap()
            .pop()
            .unwrap_or_else(self.factory)
    }
    pub fn release(&self, t: T) {
        let mut guard = self.cache.lock().unwrap();
        if guard.len() < self.capacity {
            guard.push(t);
        } // else: t is dropped here, capacity enforced
    }
}

pub mod message_batches {
    use super::{xorshift64, BoundedPool, UnboundedPool};

    pub const N: usize = 500_000;
    pub const BATCH_SIZE: usize = 500;
    pub const POOL_CAPACITY: usize = 64;

    #[derive(Default)]
    pub struct MessageScratch {
        pub id: u64,
        pub value: u64,
        pub flag: u32,
    }

    pub struct Source {
        pub value: Vec<u64>,
        pub flag: Vec<u32>,
    }

    pub fn generate(n: usize) -> Source {
        let mut value = Vec::with_capacity(n);
        let mut flag = Vec::with_capacity(n);
        let mut x: u64 = 100;
        for _ in 0..n {
            x = xorshift64(x);
            value.push(x % 1_000_000);
            x = xorshift64(x);
            flag.push((x % 16) as u32);
        }
        Source { value, flag }
    }

    pub fn expected_checksum(s: &Source) -> u64 {
        let mut sum = 0u64;
        for i in 0..s.value.len() {
            sum = sum
                .wrapping_add(i as u64)
                .wrapping_add(s.value[i])
                .wrapping_add(s.flag[i] as u64);
        }
        sum
    }

    /// A fresh `Box<MessageScratch>` per message — a REAL heap
    /// allocation every time (no scalar replacement in Rust; rust.md).
    pub fn allocate_per_item(s: &Source) -> u64 {
        let mut sum = 0u64;
        for i in 0..s.value.len() {
            let m = Box::new(MessageScratch {
                id: i as u64,
                value: s.value[i],
                flag: s.flag[i],
            });
            sum = sum
                .wrapping_add(m.id)
                .wrapping_add(m.value)
                .wrapping_add(m.flag as u64);
        }
        sum
    }

    /// One `Vec<MessageScratch>` allocated per batch, filled, summed,
    /// then dropped at batch end — Rust's natural analog of "one arena
    /// per batch, closed at batch end": the whole Vec's backing
    /// allocation is freed in one `Drop`, not per element.
    pub fn batch_arena(s: &Source, batch_size: usize) -> u64 {
        let mut sum = 0u64;
        let n = s.value.len();
        let mut i = 0;
        while i < n {
            let end = (i + batch_size).min(n);
            let mut batch: Vec<MessageScratch> = Vec::with_capacity(end - i);
            for j in i..end {
                batch.push(MessageScratch {
                    id: j as u64,
                    value: s.value[j],
                    flag: s.flag[j],
                });
            }
            for m in &batch {
                sum = sum
                    .wrapping_add(m.id)
                    .wrapping_add(m.value)
                    .wrapping_add(m.flag as u64);
            }
            // `batch` drops here — the measured per-batch reclamation cost.
            i = end;
        }
        sum
    }

    /// One `MessageScratch` local, reused for every message — already
    /// thread-confined by ordinary Rust ownership; no explicit
    /// thread-local storage API needed for a single-threaded pass
    /// (rust.md).
    pub fn thread_local_reuse(s: &Source) -> u64 {
        let mut m = MessageScratch::default();
        let mut sum = 0u64;
        for i in 0..s.value.len() {
            m.id = i as u64;
            m.value = s.value[i];
            m.flag = s.flag[i];
            sum = sum
                .wrapping_add(m.id)
                .wrapping_add(m.value)
                .wrapping_add(m.flag as u64);
        }
        sum
    }

    pub fn global_pool(s: &Source, pool: &UnboundedPool<MessageScratch>) -> u64 {
        let mut sum = 0u64;
        for i in 0..s.value.len() {
            let mut m = pool.borrow();
            m.id = i as u64;
            m.value = s.value[i];
            m.flag = s.flag[i];
            sum = sum
                .wrapping_add(m.id)
                .wrapping_add(m.value)
                .wrapping_add(m.flag as u64);
            pool.release(m);
        }
        sum
    }

    pub fn bounded_pool(s: &Source, pool: &BoundedPool<MessageScratch>) -> u64 {
        let mut sum = 0u64;
        for i in 0..s.value.len() {
            let mut m = pool.borrow();
            m.id = i as u64;
            m.value = s.value[i];
            m.flag = s.flag[i];
            sum = sum
                .wrapping_add(m.id)
                .wrapping_add(m.value)
                .wrapping_add(m.flag as u64);
            pool.release(m);
        }
        sum
    }
}

pub mod parse_trees {
    use super::{xorshift64, BoundedPool, UnboundedPool};

    pub const DOC_COUNT: usize = 100_000;
    pub const NODES_PER_DOC: usize = 8;
    pub const DOCS_PER_BATCH: usize = 100;
    pub const POOL_CAPACITY: usize = 64;

    pub fn generate(doc_count: usize, nodes_per_doc: usize) -> Vec<u64> {
        let mut value = Vec::with_capacity(doc_count * nodes_per_doc);
        let mut x: u64 = 101;
        for _ in 0..doc_count * nodes_per_doc {
            x = xorshift64(x);
            value.push(x % 1_000_000);
        }
        value
    }

    pub fn expected_checksum(value: &[u64]) -> u64 {
        value.iter().fold(0u64, |acc, &v| acc.wrapping_add(v))
    }

    fn sum_slice(
        scratch: &mut [u64],
        value: &[u64],
        doc_offset: usize,
        nodes_per_doc: usize,
    ) -> u64 {
        scratch.copy_from_slice(&value[doc_offset..doc_offset + nodes_per_doc]);
        scratch.iter().fold(0u64, |acc, &v| acc.wrapping_add(v))
    }

    pub fn allocate_per_item(value: &[u64], nodes_per_doc: usize) -> u64 {
        let mut sum = 0u64;
        let doc_count = value.len() / nodes_per_doc;
        for d in 0..doc_count {
            let mut scratch = vec![0u64; nodes_per_doc];
            sum = sum.wrapping_add(sum_slice(
                &mut scratch,
                value,
                d * nodes_per_doc,
                nodes_per_doc,
            ));
        }
        sum
    }

    pub fn batch_arena(value: &[u64], nodes_per_doc: usize, docs_per_batch: usize) -> u64 {
        let mut sum = 0u64;
        let doc_count = value.len() / nodes_per_doc;
        let mut d = 0;
        while d < doc_count {
            let end = (d + docs_per_batch).min(doc_count);
            let docs_in_batch = end - d;
            let mut batch: Vec<u64> = value[d * nodes_per_doc..end * nodes_per_doc].to_vec();
            debug_assert_eq!(batch.len(), docs_in_batch * nodes_per_doc);
            for &v in &batch {
                sum = sum.wrapping_add(v);
            }
            batch.clear(); // drop happens with `batch` at end of iteration regardless
            d = end;
        }
        sum
    }

    pub fn thread_local_reuse(value: &[u64], nodes_per_doc: usize) -> u64 {
        let mut scratch = vec![0u64; nodes_per_doc];
        let mut sum = 0u64;
        let doc_count = value.len() / nodes_per_doc;
        for d in 0..doc_count {
            sum = sum.wrapping_add(sum_slice(
                &mut scratch,
                value,
                d * nodes_per_doc,
                nodes_per_doc,
            ));
        }
        sum
    }

    pub fn global_pool(value: &[u64], nodes_per_doc: usize, pool: &UnboundedPool<Vec<u64>>) -> u64 {
        let mut sum = 0u64;
        let doc_count = value.len() / nodes_per_doc;
        for d in 0..doc_count {
            let mut scratch = pool.borrow();
            if scratch.len() != nodes_per_doc {
                scratch.resize(nodes_per_doc, 0);
            }
            sum = sum.wrapping_add(sum_slice(
                &mut scratch,
                value,
                d * nodes_per_doc,
                nodes_per_doc,
            ));
            pool.release(scratch);
        }
        sum
    }

    pub fn bounded_pool(value: &[u64], nodes_per_doc: usize, pool: &BoundedPool<Vec<u64>>) -> u64 {
        let mut sum = 0u64;
        let doc_count = value.len() / nodes_per_doc;
        for d in 0..doc_count {
            let mut scratch = pool.borrow();
            if scratch.len() != nodes_per_doc {
                scratch.resize(nodes_per_doc, 0);
            }
            sum = sum.wrapping_add(sum_slice(
                &mut scratch,
                value,
                d * nodes_per_doc,
                nodes_per_doc,
            ));
            pool.release(scratch);
        }
        sum
    }
}

pub mod scratch_buffers {
    use super::{xorshift64, BoundedPool, UnboundedPool};

    pub const OP_COUNT: usize = 200_000;
    pub const WORDS_PER_OP: usize = 8;
    pub const OPS_PER_BATCH: usize = 200;
    pub const POOL_CAPACITY: usize = 64;

    pub fn generate(op_count: usize, words_per_op: usize) -> Vec<u64> {
        let mut word = Vec::with_capacity(op_count * words_per_op);
        let mut x: u64 = 102;
        for _ in 0..op_count * words_per_op {
            x = xorshift64(x);
            word.push(x % 1_000_000);
        }
        word
    }

    pub fn expected_checksum(word: &[u64]) -> u64 {
        word.iter().fold(0u64, |acc, &v| acc.wrapping_add(v))
    }

    fn round_trip(buf: &mut [u8], word: &[u64], op_offset: usize, words_per_op: usize) -> u64 {
        for w in 0..words_per_op {
            buf[w * 8..w * 8 + 8].copy_from_slice(&word[op_offset + w].to_ne_bytes());
        }
        let mut sum = 0u64;
        for w in 0..words_per_op {
            sum = sum.wrapping_add(u64::from_ne_bytes(
                buf[w * 8..w * 8 + 8].try_into().unwrap(),
            ));
        }
        sum
    }

    pub fn allocate_per_item(word: &[u64], words_per_op: usize) -> u64 {
        let mut sum = 0u64;
        let op_count = word.len() / words_per_op;
        for o in 0..op_count {
            let mut buf = vec![0u8; words_per_op * 8];
            sum = sum.wrapping_add(round_trip(&mut buf, word, o * words_per_op, words_per_op));
        }
        sum
    }

    pub fn batch_arena(word: &[u64], words_per_op: usize, ops_per_batch: usize) -> u64 {
        let mut sum = 0u64;
        let op_count = word.len() / words_per_op;
        let mut o = 0;
        while o < op_count {
            let end = (o + ops_per_batch).min(op_count);
            let ops_in_batch = end - o;
            let mut buf = vec![0u8; ops_in_batch * words_per_op * 8];
            for w in 0..ops_in_batch * words_per_op {
                buf[w * 8..w * 8 + 8].copy_from_slice(&word[o * words_per_op + w].to_ne_bytes());
            }
            for w in 0..ops_in_batch * words_per_op {
                sum = sum.wrapping_add(u64::from_ne_bytes(
                    buf[w * 8..w * 8 + 8].try_into().unwrap(),
                ));
            }
            o = end;
        }
        sum
    }

    pub fn thread_local_reuse(word: &[u64], words_per_op: usize) -> u64 {
        let mut buf = vec![0u8; words_per_op * 8];
        let mut sum = 0u64;
        let op_count = word.len() / words_per_op;
        for o in 0..op_count {
            sum = sum.wrapping_add(round_trip(&mut buf, word, o * words_per_op, words_per_op));
        }
        sum
    }

    pub fn global_pool(word: &[u64], words_per_op: usize, pool: &UnboundedPool<Vec<u8>>) -> u64 {
        let mut sum = 0u64;
        let op_count = word.len() / words_per_op;
        for o in 0..op_count {
            let mut buf = pool.borrow();
            if buf.len() != words_per_op * 8 {
                buf.resize(words_per_op * 8, 0);
            }
            sum = sum.wrapping_add(round_trip(&mut buf, word, o * words_per_op, words_per_op));
            pool.release(buf);
        }
        sum
    }

    pub fn bounded_pool(word: &[u64], words_per_op: usize, pool: &BoundedPool<Vec<u8>>) -> u64 {
        let mut sum = 0u64;
        let op_count = word.len() / words_per_op;
        for o in 0..op_count {
            let mut buf = pool.borrow();
            if buf.len() != words_per_op * 8 {
                buf.resize(words_per_op * 8, 0);
            }
            sum = sum.wrapping_add(round_trip(&mut buf, word, o * words_per_op, words_per_op));
            pool.release(buf);
        }
        sum
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn message_batches_all_variants_agree() {
        let s = message_batches::generate(message_batches::N);
        assert_eq!(s.value[0], 927524);
        assert_eq!(s.flag[0], 6);

        let expected = message_batches::expected_checksum(&s);
        assert_eq!(expected, 374_972_485_120);

        assert_eq!(message_batches::allocate_per_item(&s), expected);
        assert_eq!(
            message_batches::batch_arena(&s, message_batches::BATCH_SIZE),
            expected
        );
        assert_eq!(message_batches::thread_local_reuse(&s), expected);

        let unbounded = UnboundedPool::new(message_batches::MessageScratch::default);
        assert_eq!(message_batches::global_pool(&s, &unbounded), expected);

        let bounded = BoundedPool::new(
            message_batches::POOL_CAPACITY,
            message_batches::MessageScratch::default,
        );
        assert_eq!(message_batches::bounded_pool(&s, &bounded), expected);
    }

    #[test]
    fn parse_trees_all_variants_agree() {
        let value = parse_trees::generate(parse_trees::DOC_COUNT, parse_trees::NODES_PER_DOC);
        assert_eq!(value[0], 419941);
        assert_eq!(value[1], 158871);

        let expected = parse_trees::expected_checksum(&value);
        assert_eq!(expected, 399_735_735_737);

        assert_eq!(
            parse_trees::allocate_per_item(&value, parse_trees::NODES_PER_DOC),
            expected
        );
        assert_eq!(
            parse_trees::batch_arena(
                &value,
                parse_trees::NODES_PER_DOC,
                parse_trees::DOCS_PER_BATCH
            ),
            expected
        );
        assert_eq!(
            parse_trees::thread_local_reuse(&value, parse_trees::NODES_PER_DOC),
            expected
        );

        let unbounded = UnboundedPool::new(Vec::new);
        assert_eq!(
            parse_trees::global_pool(&value, parse_trees::NODES_PER_DOC, &unbounded),
            expected
        );

        let bounded = BoundedPool::new(parse_trees::POOL_CAPACITY, Vec::new);
        assert_eq!(
            parse_trees::bounded_pool(&value, parse_trees::NODES_PER_DOC, &bounded),
            expected
        );
    }

    #[test]
    fn scratch_buffers_all_variants_agree() {
        let word =
            scratch_buffers::generate(scratch_buffers::OP_COUNT, scratch_buffers::WORDS_PER_OP);
        assert_eq!(word[0], 942758);
        assert_eq!(word[7], 972169);

        let expected = scratch_buffers::expected_checksum(&word);
        assert_eq!(expected, 799_513_392_927);

        assert_eq!(
            scratch_buffers::allocate_per_item(&word, scratch_buffers::WORDS_PER_OP),
            expected
        );
        assert_eq!(
            scratch_buffers::batch_arena(
                &word,
                scratch_buffers::WORDS_PER_OP,
                scratch_buffers::OPS_PER_BATCH
            ),
            expected
        );
        assert_eq!(
            scratch_buffers::thread_local_reuse(&word, scratch_buffers::WORDS_PER_OP),
            expected
        );

        let unbounded = UnboundedPool::new(Vec::new);
        assert_eq!(
            scratch_buffers::global_pool(&word, scratch_buffers::WORDS_PER_OP, &unbounded),
            expected
        );

        let bounded = BoundedPool::new(scratch_buffers::POOL_CAPACITY, Vec::new);
        assert_eq!(
            scratch_buffers::bounded_pool(&word, scratch_buffers::WORDS_PER_OP, &bounded),
            expected
        );
    }
}
