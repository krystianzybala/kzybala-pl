//! Companion code for the Performance Lab "MPSC Queues and Producer
//! Contention" (kzybala.pl/lab/mpsc-contention/). See `rust.md` next to
//! this crate for the full explanation.
//!
//! Five fan-in variants, mirroring `code/java/`'s kernels method-for-
//! method. See `code/fixtures/mpsc-contention-fixtures.json` for the
//! shared dataset constants both languages assert against identically.

pub mod fixtures {
    pub const PRODUCER_COUNTS: [usize; 2] = [2, 4];
    pub const ITEMS_PER_PRODUCER: usize = 2_000;
    pub const CAPACITY: usize = 1_024;
    pub const BATCH_SIZE: usize = 32;
    pub const MAX_SPIN_ITERATIONS: u64 = 200_000_000;

    pub fn encode(producer_id: usize, local_seq: usize) -> i64 {
        ((producer_id as i64) << 32) | (local_seq as i64 & 0xFFFF_FFFF)
    }

    pub fn decode_producer(item: i64) -> usize {
        ((item as u64) >> 32) as usize
    }

    pub fn decode_seq(item: i64) -> usize {
        (item as u32) as usize
    }
}

/// Per-producer FIFO order (the only ordering MPSC guarantees) and exact
/// total count, zero duplicates. Global cross-producer interleaving is
/// deliberately never asserted. Shared by every variant's test.
pub fn is_correct(received: &[i64], producer_count: usize, items_per_producer: usize) -> bool {
    if received.len() != producer_count * items_per_producer {
        return false;
    }
    let mut next_expected = vec![0usize; producer_count];
    for &item in received {
        let producer = fixtures::decode_producer(item);
        let seq = fixtures::decode_seq(item);
        if producer >= producer_count {
            return false;
        }
        if seq != next_expected[producer] {
            return false;
        }
        next_expected[producer] += 1;
    }
    next_expected.iter().all(|&c| c == items_per_producer)
}

pub struct MpscResult {
    pub received: Vec<i64>,
    pub cas_failures: u64,
}

pub mod shared_mpsc {
    //! Dmitry Vyukov's bounded multi-producer/single-consumer ring —
    //! same mechanism as the Java `SharedMpscKernel`: every cell carries
    //! its own sequence number; producers claim a position with a CAS
    //! retry loop on the shared `enqueue_pos`, and the per-cell sequence
    //! signals both "ready to read" and "free to reuse."

    use super::{fixtures::*, MpscResult};
    use std::cell::UnsafeCell;
    use std::sync::atomic::{AtomicI64, Ordering};
    use std::sync::Arc;
    use std::thread;

    struct Cell {
        sequence: AtomicI64,
        data: UnsafeCell<i64>,
    }
    unsafe impl Sync for Cell {}

    pub(crate) struct Ring {
        mask: usize,
        cells: Box<[Cell]>,
        pub(crate) enqueue_pos: AtomicI64,
    }
    unsafe impl Sync for Ring {}

    impl Ring {
        pub(crate) fn new(capacity: usize) -> Self {
            assert!(
                capacity.is_power_of_two(),
                "capacity must be a power of two"
            );
            let cells: Vec<Cell> = (0..capacity)
                .map(|i| Cell {
                    sequence: AtomicI64::new(i as i64),
                    data: UnsafeCell::new(0),
                })
                .collect();
            Ring {
                mask: capacity - 1,
                cells: cells.into_boxed_slice(),
                enqueue_pos: AtomicI64::new(0),
            }
        }

        /// # Safety: exactly one producer may hold a successfully-claimed
        /// `pos` at a time (guaranteed by the CAS below), so the write is
        /// exclusive; the following `sequence.store` release-publishes it.
        pub(crate) fn produce_claimed(&self, idx: usize, pos: i64, value: i64) {
            unsafe { *self.cells[idx].data.get() = value };
            self.cells[idx].sequence.store(pos + 1, Ordering::Release);
        }

        /// # Safety: only the single consumer thread calls this, and only
        /// after observing (via `sequence`) that the producer's publish
        /// happened-before this read.
        pub(crate) fn consume_ready(&self, idx: usize, dequeue_pos: i64, capacity: usize) -> i64 {
            let value = unsafe { *self.cells[idx].data.get() };
            self.cells[idx]
                .sequence
                .store(dequeue_pos + capacity as i64, Ordering::Release);
            value
        }

        pub(crate) fn cell_sequence(&self, idx: usize) -> i64 {
            self.cells[idx].sequence.load(Ordering::Acquire)
        }
    }

    pub fn run(producer_count: usize, items_per_producer: usize, capacity: usize) -> MpscResult {
        let ring = Arc::new(Ring::new(capacity));
        let mask = ring.mask;

        let producer_handles: Vec<_> = (0..producer_count)
            .map(|producer_id| {
                let ring = Arc::clone(&ring);
                thread::spawn(move || {
                    let mut local_failures = 0u64;
                    for seq in 0..items_per_producer {
                        let value = encode(producer_id, seq);
                        let mut spins: u64 = 0;
                        loop {
                            let pos = ring.enqueue_pos.load(Ordering::Acquire);
                            let idx = (pos as usize) & mask;
                            let cell_seq = ring.cell_sequence(idx);
                            let diff = cell_seq - pos;
                            if diff == 0 {
                                if ring
                                    .enqueue_pos
                                    .compare_exchange(
                                        pos,
                                        pos + 1,
                                        Ordering::AcqRel,
                                        Ordering::Relaxed,
                                    )
                                    .is_ok()
                                {
                                    ring.produce_claimed(idx, pos, value);
                                    break;
                                }
                                local_failures += 1;
                            }
                            spins += 1;
                            if spins >= MAX_SPIN_ITERATIONS {
                                panic!("producer {producer_id} spun out waiting for capacity");
                            }
                            if spins & 0xFFFF == 0 {
                                std::hint::spin_loop();
                            }
                        }
                    }
                    local_failures
                })
            })
            .collect();

        let total = producer_count * items_per_producer;
        let consumer_ring = Arc::clone(&ring);
        let consumer_handle = thread::spawn(move || {
            let mut received = Vec::with_capacity(total);
            let mut dequeue_pos: i64 = 0;
            let mut spins: u64 = 0;
            while received.len() < total {
                let idx = (dequeue_pos as usize) & mask;
                let diff = consumer_ring.cell_sequence(idx) - (dequeue_pos + 1);
                if diff == 0 {
                    received.push(consumer_ring.consume_ready(idx, dequeue_pos, capacity));
                    dequeue_pos += 1;
                    spins = 0;
                } else {
                    spins += 1;
                    if spins >= MAX_SPIN_ITERATIONS {
                        panic!(
                            "consumer spun out waiting for data, received {}/{}",
                            received.len(),
                            total
                        );
                    }
                    if spins & 0xFFFF == 0 {
                        std::hint::spin_loop();
                    }
                }
            }
            received
        });

        let mut cas_failures = 0u64;
        for h in producer_handles {
            cas_failures += h.join().unwrap();
        }
        let received = consumer_handle.join().unwrap();
        MpscResult {
            received,
            cas_failures,
        }
    }
}

pub mod batched_claim {
    //! Same ring as `shared_mpsc`, but a producer reserves `batch_size`
    //! contiguous positions with one `fetch_add` instead of retrying a
    //! per-item CAS.

    use super::shared_mpsc::Ring;
    use super::{fixtures::*, MpscResult};
    use std::sync::atomic::Ordering;
    use std::sync::Arc;
    use std::thread;

    pub fn run(
        producer_count: usize,
        items_per_producer: usize,
        capacity: usize,
        batch_size: usize,
    ) -> MpscResult {
        let ring = Arc::new(Ring::new(capacity));

        let producer_handles: Vec<_> = (0..producer_count)
            .map(|producer_id| {
                let ring = Arc::clone(&ring);
                thread::spawn(move || {
                    let mut seq = 0usize;
                    while seq < items_per_producer {
                        let this_batch = batch_size.min(items_per_producer - seq);
                        let base_pos = ring
                            .enqueue_pos
                            .fetch_add(this_batch as i64, Ordering::AcqRel);
                        for i in 0..this_batch {
                            let pos = base_pos + i as i64;
                            let idx = (pos as usize) & (capacity - 1);
                            let mut spins: u64 = 0;
                            while ring.cell_sequence(idx) != pos {
                                spins += 1;
                                if spins >= MAX_SPIN_ITERATIONS {
                                    panic!("producer {producer_id} spun out waiting for capacity");
                                }
                                if spins & 0xFFFF == 0 {
                                    std::hint::spin_loop();
                                }
                            }
                            ring.produce_claimed(idx, pos, encode(producer_id, seq + i));
                        }
                        seq += this_batch;
                    }
                })
            })
            .collect();

        let total = producer_count * items_per_producer;
        let consumer_ring = Arc::clone(&ring);
        let consumer_handle = thread::spawn(move || {
            let mut received = Vec::with_capacity(total);
            let mut dequeue_pos: i64 = 0;
            let mut spins: u64 = 0;
            while received.len() < total {
                let idx = (dequeue_pos as usize) & (capacity - 1);
                let diff = consumer_ring.cell_sequence(idx) - (dequeue_pos + 1);
                if diff == 0 {
                    received.push(consumer_ring.consume_ready(idx, dequeue_pos, capacity));
                    dequeue_pos += 1;
                    spins = 0;
                } else {
                    spins += 1;
                    if spins >= MAX_SPIN_ITERATIONS {
                        panic!(
                            "consumer spun out waiting for data, received {}/{}",
                            received.len(),
                            total
                        );
                    }
                    if spins & 0xFFFF == 0 {
                        std::hint::spin_loop();
                    }
                }
            }
            received
        });

        for h in producer_handles {
            h.join().unwrap();
        }
        let received = consumer_handle.join().unwrap();
        MpscResult {
            received,
            cas_failures: 0,
        }
    }
}

pub mod per_producer_fan_in {
    //! No shared structure at all: each producer owns a private SPSC
    //! ring (same discipline as the site's SPSC Ring Buffer lab); the
    //! consumer round-robins across all of them.

    use super::{fixtures::*, MpscResult};
    use std::cell::UnsafeCell;
    use std::sync::atomic::{AtomicI64, Ordering};
    use std::sync::Arc;
    use std::thread;

    struct SpscRing {
        mask: usize,
        data: Box<[UnsafeCell<i64>]>,
        head: AtomicI64, // published cursor (producer writes)
        tail: AtomicI64, // acknowledged cursor (consumer writes)
    }
    unsafe impl Sync for SpscRing {}

    impl SpscRing {
        fn new(capacity: usize) -> Self {
            let data: Vec<UnsafeCell<i64>> = (0..capacity).map(|_| UnsafeCell::new(0)).collect();
            SpscRing {
                mask: capacity - 1,
                data: data.into_boxed_slice(),
                head: AtomicI64::new(0),
                tail: AtomicI64::new(0),
            }
        }

        fn try_produce(&self, value: i64) -> bool {
            let h = self.head.load(Ordering::Relaxed);
            let t = self.tail.load(Ordering::Acquire);
            if h - t == (self.mask + 1) as i64 {
                return false;
            }
            unsafe { *self.data[(h as usize) & self.mask].get() = value };
            self.head.store(h + 1, Ordering::Release);
            true
        }

        fn try_consume(&self) -> Option<i64> {
            let t = self.tail.load(Ordering::Relaxed);
            let h = self.head.load(Ordering::Acquire);
            if t == h {
                return None;
            }
            let value = unsafe { *self.data[(t as usize) & self.mask].get() };
            self.tail.store(t + 1, Ordering::Release);
            Some(value)
        }
    }

    pub fn run(producer_count: usize, items_per_producer: usize, capacity: usize) -> MpscResult {
        let rings: Vec<Arc<SpscRing>> = (0..producer_count)
            .map(|_| Arc::new(SpscRing::new(capacity)))
            .collect();

        let producer_handles: Vec<_> = (0..producer_count)
            .map(|producer_id| {
                let ring = Arc::clone(&rings[producer_id]);
                thread::spawn(move || {
                    for seq in 0..items_per_producer {
                        let value = encode(producer_id, seq);
                        let mut spins: u64 = 0;
                        while !ring.try_produce(value) {
                            spins += 1;
                            if spins >= MAX_SPIN_ITERATIONS {
                                panic!("producer {producer_id} spun out waiting for capacity");
                            }
                            if spins & 0xFFFF == 0 {
                                std::hint::spin_loop();
                            }
                        }
                    }
                })
            })
            .collect();

        let total = producer_count * items_per_producer;
        let consumer_rings = rings.clone();
        let consumer_handle = thread::spawn(move || {
            let mut received = Vec::with_capacity(total);
            let mut spins: u64 = 0;
            let mut cursor = 0usize;
            while received.len() < total {
                let ring = &consumer_rings[cursor];
                cursor = (cursor + 1) % consumer_rings.len();
                match ring.try_consume() {
                    Some(value) => {
                        received.push(value);
                        spins = 0;
                    }
                    None => {
                        spins += 1;
                        if spins >= MAX_SPIN_ITERATIONS {
                            panic!(
                                "consumer spun out waiting for data, received {}/{}",
                                received.len(),
                                total
                            );
                        }
                    }
                }
            }
            received
        });

        for h in producer_handles {
            h.join().unwrap();
        }
        let received = consumer_handle.join().unwrap();
        MpscResult {
            received,
            cas_failures: 0,
        }
    }
}

pub mod mutex_queue {
    //! The "obviously correct" baseline: a `VecDeque` guarded by a
    //! `Mutex` — no atomics, no lock-free reasoning.

    use super::{fixtures::*, MpscResult};
    use std::collections::VecDeque;
    use std::sync::{Arc, Mutex};
    use std::thread;

    pub fn run(producer_count: usize, items_per_producer: usize, capacity: usize) -> MpscResult {
        let queue: Arc<Mutex<VecDeque<i64>>> =
            Arc::new(Mutex::new(VecDeque::with_capacity(capacity)));

        let producer_handles: Vec<_> = (0..producer_count)
            .map(|producer_id| {
                let queue = Arc::clone(&queue);
                thread::spawn(move || {
                    for seq in 0..items_per_producer {
                        let value = encode(producer_id, seq);
                        let mut spins: u64 = 0;
                        loop {
                            {
                                let mut q = queue.lock().unwrap();
                                if q.len() < capacity {
                                    q.push_back(value);
                                    break;
                                }
                            }
                            spins += 1;
                            if spins >= MAX_SPIN_ITERATIONS {
                                panic!("producer {producer_id} spun out waiting for capacity");
                            }
                            if spins & 0xFFFF == 0 {
                                std::hint::spin_loop();
                            }
                        }
                    }
                })
            })
            .collect();

        let total = producer_count * items_per_producer;
        let consumer_queue = Arc::clone(&queue);
        let consumer_handle = thread::spawn(move || {
            let mut received = Vec::with_capacity(total);
            let mut spins: u64 = 0;
            while received.len() < total {
                let value = consumer_queue.lock().unwrap().pop_front();
                match value {
                    Some(v) => {
                        received.push(v);
                        spins = 0;
                    }
                    None => {
                        spins += 1;
                        if spins >= MAX_SPIN_ITERATIONS {
                            panic!(
                                "consumer spun out waiting for data, received {}/{}",
                                received.len(),
                                total
                            );
                        }
                    }
                }
            }
            received
        });

        for h in producer_handles {
            h.join().unwrap();
        }
        let received = consumer_handle.join().unwrap();
        MpscResult {
            received,
            cas_failures: 0,
        }
    }
}

pub mod library_queue {
    //! Reference point, not a lab-authored mechanism: `std::sync::mpsc`
    //! — Rust's own standard-library unbounded multi-producer,
    //! single-consumer channel, used exactly as shipped.

    use super::{fixtures::*, MpscResult};
    use std::sync::mpsc;
    use std::thread;

    pub fn run(producer_count: usize, items_per_producer: usize) -> MpscResult {
        let (tx, rx) = mpsc::channel::<i64>();

        let producer_handles: Vec<_> = (0..producer_count)
            .map(|producer_id| {
                let tx = tx.clone();
                thread::spawn(move || {
                    for seq in 0..items_per_producer {
                        tx.send(encode(producer_id, seq))
                            .expect("consumer dropped receiver");
                    }
                })
            })
            .collect();
        drop(tx); // the consumer's recv loop below relies on senders eventually being dropped

        let total = producer_count * items_per_producer;
        let mut received = Vec::with_capacity(total);
        while received.len() < total {
            received.push(
                rx.recv()
                    .expect("all senders dropped before total was reached"),
            );
        }

        for h in producer_handles {
            h.join().unwrap();
        }
        MpscResult {
            received,
            cas_failures: 0,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn assert_all_correct(run: impl Fn(usize) -> MpscResult) {
        for &producer_count in fixtures::PRODUCER_COUNTS.iter() {
            let result = run(producer_count);
            assert!(
                is_correct(&result.received, producer_count, fixtures::ITEMS_PER_PRODUCER),
                "producer_count={producer_count} did not preserve per-producer FIFO order and exact counts"
            );
        }
    }

    #[test]
    fn shared_mpsc_preserves_order_and_counts() {
        assert_all_correct(|p| {
            shared_mpsc::run(p, fixtures::ITEMS_PER_PRODUCER, fixtures::CAPACITY)
        });
    }

    #[test]
    fn batched_claim_preserves_order_and_counts() {
        assert_all_correct(|p| {
            batched_claim::run(
                p,
                fixtures::ITEMS_PER_PRODUCER,
                fixtures::CAPACITY,
                fixtures::BATCH_SIZE,
            )
        });
    }

    #[test]
    fn per_producer_fan_in_preserves_order_and_counts() {
        assert_all_correct(|p| {
            per_producer_fan_in::run(p, fixtures::ITEMS_PER_PRODUCER, fixtures::CAPACITY)
        });
    }

    #[test]
    fn mutex_queue_preserves_order_and_counts() {
        assert_all_correct(|p| {
            mutex_queue::run(p, fixtures::ITEMS_PER_PRODUCER, fixtures::CAPACITY)
        });
    }

    #[test]
    fn library_queue_preserves_order_and_counts() {
        assert_all_correct(|p| library_queue::run(p, fixtures::ITEMS_PER_PRODUCER));
    }
}
