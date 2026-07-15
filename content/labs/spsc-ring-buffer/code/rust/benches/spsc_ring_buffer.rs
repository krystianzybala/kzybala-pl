//! Criterion benchmark: steady-state throughput of a real producer thread
//! and a real consumer thread pushed through one 1024-slot SPSC ring
//! buffer. See rust.md for methodology notes and benchmark.md (in the lab
//! content directory) for environment disclosure.

use criterion::{criterion_group, criterion_main, Criterion};
use spsc_ring_buffer_lab::ring_buffer;
use std::thread;

const ITEMS: u64 = 200_000;

fn run_pipeline() {
    let (mut producer, mut consumer) = ring_buffer(1024);

    let producer_handle = thread::spawn(move || {
        for i in 0..ITEMS {
            while !producer.try_produce(i) {
                std::hint::spin_loop();
            }
        }
    });

    let consumer_handle = thread::spawn(move || {
        let mut received = 0u64;
        while received < ITEMS {
            if consumer.try_consume().is_some() {
                received += 1;
            } else {
                std::hint::spin_loop();
            }
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
