use criterion::{black_box, criterion_group, criterion_main, Criterion};
use ffm_interop_lab::{transform_batch_pure, transform_scalar_pure};

const N: usize = 10_000;

fn bench_ffm_interop(c: &mut Criterion) {
    // "Pure Rust baseline" — no FFI at all, the reference point this lab
    // never directly compares against Java's own pure-Java baseline (see
    // theory.md: neutral Java/Rust conclusion policy).
    c.bench_function("pure_rust_baseline_scalar", |b| {
        b.iter(|| {
            let mut acc = 0.0;
            for i in 0..N {
                acc += transform_scalar_pure(black_box(i as f64));
            }
            black_box(acc)
        })
    });

    c.bench_function("pure_rust_baseline_batch", |b| {
        let input: Vec<f64> = (0..N).map(|i| i as f64).collect();
        let mut output = vec![0.0; N];
        b.iter(|| {
            transform_batch_pure(black_box(&input), &mut output);
            black_box(&output);
        })
    });
}

criterion_group!(benches, bench_ffm_interop);
criterion_main!(benches);
