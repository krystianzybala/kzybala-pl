use criterion::{black_box, criterion_group, criterion_main, Criterion};
use memory_mapped_files_lab::{
    buffered_read_checksum, deterministic_permutation, flush, map_readonly, map_readwrite,
    mmap_random_checksum, mmap_sequential_checksum, write_all_records, write_records, RECORD_SIZE,
};
use std::path::PathBuf;
use tempfile::tempdir;

const RECORD_COUNT: u64 = 1_048_576;
const FILE_SIZE: u64 = RECORD_COUNT * RECORD_SIZE as u64;

fn bench_memory_mapped_files(c: &mut Criterion) {
    let dir = tempdir().unwrap();
    let read_path: PathBuf = dir.path().join("read.bin");
    write_records(&read_path, RECORD_COUNT).unwrap();
    let order = deterministic_permutation(RECORD_COUNT as u32, 424_242);

    c.bench_function("buffered_read", |b| {
        b.iter(|| black_box(buffered_read_checksum(black_box(&read_path), RECORD_COUNT).unwrap()))
    });

    c.bench_function("warm_mmap_sequential_read", |b| {
        let mapped = map_readonly(&read_path).unwrap();
        // Untimed warm-up pass so this benchmark measures an already-touched mapping.
        mmap_sequential_checksum(&mapped, RECORD_COUNT);
        b.iter(|| black_box(mmap_sequential_checksum(black_box(&mapped), RECORD_COUNT)))
    });

    c.bench_function("cold_mapped_sequential_read", |b| {
        // Maps freshly on every iteration — see lib.rs / theory.md for why this
        // is "fresh mapping," not a true OS-page-cache-cold read on this host.
        b.iter(|| {
            let mapped = map_readonly(black_box(&read_path)).unwrap();
            black_box(mmap_sequential_checksum(&mapped, RECORD_COUNT))
        })
    });

    c.bench_function("random_mapped_access", |b| {
        let mapped = map_readonly(&read_path).unwrap();
        mmap_sequential_checksum(&mapped, RECORD_COUNT);
        b.iter(|| black_box(mmap_random_checksum(black_box(&mapped), black_box(&order))))
    });

    c.bench_function("mapped_write_and_flush", |b| {
        b.iter(|| {
            let write_path = dir.path().join(format!("write-{}.bin", rand_suffix()));
            let mut mapped = map_readwrite(&write_path, FILE_SIZE).unwrap();
            write_all_records(&mut mapped, RECORD_COUNT);
            flush(&mapped).unwrap();
            let _ = std::fs::remove_file(&write_path);
        })
    });
}

fn rand_suffix() -> u64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_nanos() as u64
}

criterion_group!(benches, bench_memory_mapped_files);
criterion_main!(benches);
