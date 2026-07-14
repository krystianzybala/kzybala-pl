# SPSC ring buffer — Rust

## Zero-allocation ring buffer

```rust
use std::cell::UnsafeCell;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;

struct Shared {
    slots: Box<[UnsafeCell<u64>]>,
    mask: u64,
    head: AtomicU64, // published cursor
    tail: AtomicU64, // acknowledged cursor
}
unsafe impl Sync for Shared {}

pub fn ring_buffer(capacity: usize) -> (Producer, Consumer) {
    assert!(capacity.is_power_of_two(), "capacity must be a power of two");
    let slots = (0..capacity).map(|_| UnsafeCell::new(0)).collect();
    let shared = Arc::new(Shared { slots, mask: (capacity - 1) as u64, head: AtomicU64::new(0), tail: AtomicU64::new(0) });
    (
        Producer { shared: Arc::clone(&shared), reserve_index: 0, cached_tail: 0 },
        Consumer { shared, read_index: 0, cached_head: 0 },
    )
}

pub struct Producer { shared: Arc<Shared>, reserve_index: u64, cached_tail: u64 }

impl Producer {
    pub fn try_produce(&mut self, value: u64) -> bool {
        let capacity = self.shared.slots.len() as u64;
        if self.reserve_index - self.cached_tail == capacity {
            self.cached_tail = self.shared.tail.load(Ordering::Acquire);
            if self.reserve_index - self.cached_tail == capacity {
                return false; // genuinely full
            }
        }
        let idx = (self.reserve_index & self.shared.mask) as usize;
        unsafe { *self.shared.slots[idx].get() = value; } // payload write — not yet visible
        self.reserve_index += 1;
        self.shared.head.store(self.reserve_index, Ordering::Release); // publication
        true
    }
}
unsafe impl Send for Producer {}

pub struct Consumer { shared: Arc<Shared>, read_index: u64, cached_head: u64 }

impl Consumer {
    pub fn try_consume(&mut self) -> Option<u64> {
        if self.read_index == self.cached_head {
            self.cached_head = self.shared.head.load(Ordering::Acquire);
            if self.read_index == self.cached_head {
                return None; // genuinely empty
            }
        }
        let idx = (self.read_index & self.shared.mask) as usize;
        let value = unsafe { *self.shared.slots[idx].get() }; // payload read
        self.read_index += 1;
        self.shared.tail.store(self.read_index, Ordering::Release); // consumption acknowledgement
        Some(value)
    }
}
unsafe impl Send for Consumer {}
```

Safe Rust cannot express "two handles that alias the same buffer, but each
only touches disjoint slots at any given time, proven by a runtime
protocol rather than the type system" — that's exactly what a from-scratch
SPSC ring buffer *is*, so the slot storage uses `UnsafeCell<u64>` with
`unsafe` reads/writes, same as real crates in this space (e.g. `ringbuf`)
do internally. The safety argument is the same ownership discipline the
theory section describes: a slot is only written once the capacity check
confirms the consumer already acknowledged it, and only read once the head
check confirms the producer already published it — the two handles are
therefore never touching the same slot at the same time, which is exactly
what `unsafe impl Sync for Shared` asserts and depends on. `Producer` and
`Consumer` are handed out exactly once, as a pair, by `ring_buffer()` —
there is no way to obtain a second `Producer` for the same buffer.

`Ordering::Release`/`Acquire` on `head`/`tail` is the same pairing as the
Java example's `VarHandle.setRelease`/`getAcquire` — see the
[Memory Ordering](/lab/memory-ordering/) lab for why `Relaxed` would not be
sufficient here (the payload write must be ordered before the publish, and
the payload read must happen after observing that publish) and why
`SeqCst` is not required either (there is only one producer and one
consumer — no third thread needs a total order across both cursors).
`cached_tail`/`cached_head` are plain (non-atomic) `u64` fields on each
single-owner handle, exactly mirroring the Java example.

## Criterion benchmark

```rust
use spsc_ring_buffer_lab::ring_buffer;
use criterion::{criterion_group, criterion_main, Criterion};
use std::thread;

const ITEMS: u64 = 200_000;

fn run_pipeline() {
    let (mut producer, mut consumer) = ring_buffer(1024);
    let producer_handle = thread::spawn(move || {
        for i in 0..ITEMS {
            while !producer.try_produce(i) { std::hint::spin_loop(); }
        }
    });
    let consumer_handle = thread::spawn(move || {
        let mut received = 0u64;
        while received < ITEMS {
            if consumer.try_consume().is_some() { received += 1; } else { std::hint::spin_loop(); }
        }
    });
    producer_handle.join().unwrap();
    consumer_handle.join().unwrap();
}

fn bench_spsc_ring_buffer(c: &mut Criterion) {
    c.bench_function("spsc_ring_buffer_capacity_1024", |b| b.iter(run_pipeline));
}

criterion_group!(benches, bench_spsc_ring_buffer);
criterion_main!(benches);
```

**This benchmark's timed region includes spawning and joining both
threads on every iteration** — Criterion's `b.iter(...)` re-runs the whole
closure per sample, and a genuinely fresh producer/consumer pair is created
each time to keep iterations independent. This is a materially different
methodology from the Java benchmark, whose JMH `@Group` keeps both threads
alive for the entire measurement window and only measures steady-state
throughput once they're running — see "Benchmark methodology" below for
why this means the two languages' numbers are not directly comparable
here, unlike (say) the CAS Contention lab's same-shaped benchmarks in both
languages.

The runnable Cargo/Criterion project (with correctness tests in
`src/lib.rs`) is at
<a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/spsc-ring-buffer/code/rust" rel="noopener"><code>content/labs/spsc-ring-buffer/code/rust/</code></a>
in this site's repository.
