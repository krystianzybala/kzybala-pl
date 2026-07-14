//! Criterion benchmark: aggregate compare-and-set throughput on one shared
//! counter at 1, 2, 4, and 8 contending threads, contrasted against a
//! single-writer counter with no contention at all. See rust.md for
//! methodology notes and benchmark.md (in the lab content directory) for
//! environment disclosure.

use cas_contention_lab::{CasCounter, SingleWriterCounter};
use criterion::{black_box, criterion_group, criterion_main, Criterion};
use std::sync::Arc;
use std::thread;

const INCREMENTS_PER_THREAD: u64 = 20_000;

fn run_contended(thread_count: usize) {
    let counter = Arc::new(CasCounter::new());
    let handles: Vec<_> = (0..thread_count)
        .map(|_| {
            let c = Arc::clone(&counter);
            thread::spawn(move || {
                for _ in 0..INCREMENTS_PER_THREAD {
                    c.increment_manually();
                }
            })
        })
        .collect();
    for h in handles {
        h.join().unwrap();
    }
}

fn bench_cas_1_thread(c: &mut Criterion) {
    c.bench_function("cas_increment_1_thread", |b| b.iter(|| run_contended(1)));
}

fn bench_cas_2_threads(c: &mut Criterion) {
    c.bench_function("cas_increment_2_threads", |b| b.iter(|| run_contended(2)));
}

fn bench_cas_4_threads(c: &mut Criterion) {
    c.bench_function("cas_increment_4_threads", |b| b.iter(|| run_contended(4)));
}

fn bench_cas_8_threads(c: &mut Criterion) {
    c.bench_function("cas_increment_8_threads", |b| b.iter(|| run_contended(8)));
}

fn bench_single_writer(c: &mut Criterion) {
    c.bench_function("single_writer_increment", |b| {
        b.iter(|| {
            let mut counter = SingleWriterCounter::new();
            for _ in 0..(INCREMENTS_PER_THREAD * 8) {
                black_box(counter.increment());
            }
        })
    });
}

criterion_group!(
    benches,
    bench_cas_1_thread,
    bench_cas_2_threads,
    bench_cas_4_threads,
    bench_cas_8_threads,
    bench_single_writer
);
criterion_main!(benches);
