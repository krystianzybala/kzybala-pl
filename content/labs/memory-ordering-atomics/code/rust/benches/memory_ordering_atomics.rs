//! Criterion benchmark for the `memory-ordering-atomics` lab. Each
//! benchmark runs one ordering variant of one dataset per process
//! invocation's group — see `benchmark.md` for the method notes on why
//! these numbers are not directly comparable to the Java JMH results.

use criterion::{criterion_group, criterion_main, BenchmarkId, Criterion};
use memory_ordering_atomics_lab::{counter_update, mailbox, sequence_flag};

fn bench_mailbox(c: &mut Criterion) {
    let mut group = c.benchmark_group("mailbox");
    group.bench_function(BenchmarkId::new("variant", "acquire_release"), |b| {
        b.iter(mailbox::acquire_release_publication)
    });
    group.bench_function(BenchmarkId::new("variant", "volatile_seq_cst"), |b| {
        b.iter(mailbox::volatile_seq_cst_publication)
    });
    group.bench_function(BenchmarkId::new("variant", "cas_loop"), |b| {
        b.iter(mailbox::cas_loop)
    });
    group.bench_function(BenchmarkId::new("variant", "fence_based"), |b| {
        b.iter(mailbox::fence_based)
    });
    group.finish();
}

fn bench_sequence_flag(c: &mut Criterion) {
    let mut group = c.benchmark_group("sequence_flag");
    group.sample_size(20); // each sample performs SEQFLAG_UPDATE_COUNT sequential updates
    group.bench_function(BenchmarkId::new("variant", "acquire_release"), |b| {
        b.iter(sequence_flag::acquire_release_publication)
    });
    group.bench_function(BenchmarkId::new("variant", "volatile_seq_cst"), |b| {
        b.iter(sequence_flag::volatile_seq_cst_publication)
    });
    group.bench_function(BenchmarkId::new("variant", "cas_loop"), |b| {
        b.iter(sequence_flag::cas_loop)
    });
    group.bench_function(BenchmarkId::new("variant", "fence_based"), |b| {
        b.iter(sequence_flag::fence_based)
    });
    group.finish();
}

fn bench_counter_update(c: &mut Criterion) {
    let mut group = c.benchmark_group("counter_update");
    group.sample_size(20); // each sample spawns 4 threads x 50,000 increments
    group.bench_function(BenchmarkId::new("variant", "acquire_release"), |b| {
        b.iter(counter_update::acquire_release_publication)
    });
    group.bench_function(BenchmarkId::new("variant", "cas_loop"), |b| {
        b.iter(counter_update::cas_loop)
    });
    group.bench_function(BenchmarkId::new("variant", "fence_based"), |b| {
        b.iter(counter_update::fence_based)
    });
    group.finish();
}

criterion_group!(
    benches,
    bench_mailbox,
    bench_sequence_flag,
    bench_counter_update
);
criterion_main!(benches);
