//! Companion code for the Performance Lab "SPSC Ring Buffer" lab
//! (kzybala.pl/lab/spsc-ring-buffer/). See `rust.md` next to this crate for
//! the full explanation.
//!
//! A bounded single-producer/single-consumer ring buffer of `u64` values —
//! zero allocation on the [`Producer::try_produce`]/[`Consumer::try_consume`]
//! hot path. Capacity MUST be a power of two so the slot index is a cheap
//! `& mask` instead of `% capacity`.
//!
//! Ownership discipline, not atomics, is what makes this correct: only
//! [`Producer`] ever writes a slot, only [`Consumer`] ever reads one, and
//! the two are only ever handed out once, as a pair, by
//! [`RingBuffer::new`]. `head`/`tail` use `Ordering::Release`/`Acquire` so a
//! payload written before publication is guaranteed visible to the
//! consumer once it observes the published head (see the Memory Ordering
//! lab) — `cached_tail`/`cached_head` are plain fields on each single-owner
//! handle, never touched by the other side, so they need no
//! synchronization at all.

use std::cell::UnsafeCell;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;

struct Shared {
    slots: Box<[UnsafeCell<u64>]>,
    mask: u64,
    head: AtomicU64, // published cursor; producer releases, consumer acquires
    tail: AtomicU64, // acknowledged cursor; consumer releases, producer acquires
}

// SAFETY: `slots` is only ever reached through `Producer::try_produce` or
// `Consumer::try_consume`, and the head/tail acquire/release protocol in
// each guarantees the two handles never access the same slot at the same
// time (a slot is only written after the capacity check confirms the
// consumer has acknowledged it, and only read after the head check
// confirms the producer has published it).
unsafe impl Sync for Shared {}

/// Creates a bound pair of ring-buffer handles. `capacity` must be a power
/// of two.
pub fn ring_buffer(capacity: usize) -> (Producer, Consumer) {
    assert!(capacity.is_power_of_two(), "capacity must be a power of two");
    let slots = (0..capacity).map(|_| UnsafeCell::new(0)).collect();
    let shared = Arc::new(Shared {
        slots,
        mask: (capacity - 1) as u64,
        head: AtomicU64::new(0),
        tail: AtomicU64::new(0),
    });
    (
        Producer { shared: Arc::clone(&shared), reserve_index: 0, cached_tail: 0 },
        Consumer { shared, read_index: 0, cached_head: 0 },
    )
}

pub struct Producer {
    shared: Arc<Shared>,
    reserve_index: u64,
    cached_tail: u64,
}

impl Producer {
    /// Reserves a slot, writes `value` into it, then publishes. Returns
    /// `false` (rejected) rather than overwriting a slot the consumer has
    /// not yet acknowledged.
    pub fn try_produce(&mut self, value: u64) -> bool {
        let capacity = self.shared.slots.len() as u64;
        if self.reserve_index - self.cached_tail == capacity {
            self.cached_tail = self.shared.tail.load(Ordering::Acquire);
            if self.reserve_index - self.cached_tail == capacity {
                return false; // genuinely full
            }
        }
        let idx = (self.reserve_index & self.shared.mask) as usize;
        // SAFETY: the capacity check above confirms this slot was already
        // acknowledged (freed) by the consumer, and the consumer will not
        // touch it again until it re-reads head past this reservation.
        unsafe {
            *self.shared.slots[idx].get() = value; // payload write — not yet visible
        }
        self.reserve_index += 1;
        self.shared.head.store(self.reserve_index, Ordering::Release); // publication
        true
    }
}

// SAFETY: a `Producer` only ever touches its own `reserve_index`/
// `cached_tail` and the shared atomics/slots per the protocol documented
// above, so moving it to another thread (but never sharing it) is sound.
unsafe impl Send for Producer {}

pub struct Consumer {
    shared: Arc<Shared>,
    read_index: u64,
    cached_head: u64,
}

impl Consumer {
    /// Reads and acknowledges the next published value. Returns `None`
    /// (nothing available) rather than reading unpublished slot content.
    pub fn try_consume(&mut self) -> Option<u64> {
        if self.read_index == self.cached_head {
            self.cached_head = self.shared.head.load(Ordering::Acquire);
            if self.read_index == self.cached_head {
                return None; // genuinely empty
            }
        }
        let idx = (self.read_index & self.shared.mask) as usize;
        // SAFETY: the head check above confirms this slot was published by
        // the producer, and the producer will not reuse it until it
        // re-reads tail past this acknowledgement.
        let value = unsafe { *self.shared.slots[idx].get() }; // payload read
        self.read_index += 1;
        self.shared.tail.store(self.read_index, Ordering::Release); // consumption acknowledgement
        Some(value)
    }
}

// SAFETY: see the `Producer` impl above; the same reasoning applies to `Consumer`.
unsafe impl Send for Consumer {}

#[cfg(test)]
mod tests {
    use super::*;
    use std::thread;

    #[test]
    fn try_produce_rejects_when_full() {
        let (mut producer, _consumer) = ring_buffer(2);
        assert!(producer.try_produce(1));
        assert!(producer.try_produce(2));
        assert!(!producer.try_produce(3), "reservation should be rejected once the buffer is full");
    }

    #[test]
    fn try_consume_reports_empty_before_anything_is_published() {
        let (_producer, mut consumer) = ring_buffer(2);
        assert_eq!(consumer.try_consume(), None);
    }

    #[test]
    fn produced_values_are_consumed_in_fifo_order_across_wrap_around() {
        let (mut producer, mut consumer) = ring_buffer(2);
        assert!(producer.try_produce(10));
        assert!(producer.try_produce(20));
        assert_eq!(consumer.try_consume(), Some(10));
        assert_eq!(consumer.try_consume(), Some(20));

        // Buffer is now empty; produce two more, which wrap the slot index.
        assert!(producer.try_produce(30));
        assert!(producer.try_produce(40));
        assert_eq!(consumer.try_consume(), Some(30));
        assert_eq!(consumer.try_consume(), Some(40));
    }

    #[test]
    fn is_correct_across_real_producer_and_consumer_threads() {
        let (mut producer, mut consumer) = ring_buffer(1024);
        let items: u64 = 200_000;

        let producer_handle = thread::spawn(move || {
            for i in 0..items {
                while !producer.try_produce(i) {
                    std::hint::spin_loop();
                }
            }
        });

        let consumer_handle = thread::spawn(move || {
            let mut expected = 0u64;
            let mut matched = 0u64;
            for _ in 0..items {
                loop {
                    if let Some(value) = consumer.try_consume() {
                        if value == expected {
                            matched += 1;
                        }
                        expected += 1;
                        break;
                    }
                    std::hint::spin_loop();
                }
            }
            matched
        });

        producer_handle.join().unwrap();
        let matched = consumer_handle.join().unwrap();
        assert_eq!(matched, items, "every value must be received exactly once, in FIFO order");
    }

    #[test]
    #[should_panic(expected = "power of two")]
    fn ring_buffer_rejects_non_power_of_two_capacity() {
        ring_buffer(3);
    }
}
