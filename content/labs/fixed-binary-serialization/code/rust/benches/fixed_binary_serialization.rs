use criterion::{black_box, criterion_group, criterion_main, Criterion};
use fixed_binary_serialization_lab::{decode_owned, encode, Event, EventView, MAX_SAMPLES};

fn medium_event() -> Event {
    Event {
        version: 1,
        id: 1_000_000_007,
        device_id: 314_159,
        opcode: 2,
        flags: 0,
        value: 98.6,
        timestamp: 1_731_000_000_000,
        sample_count: 0,
        samples: [0; MAX_SAMPLES],
        optional_present: false,
        optional_field: 0,
    }
}

fn repeated_fields() -> Event {
    Event {
        version: 1,
        id: 99,
        device_id: 5,
        opcode: 3,
        flags: 0,
        value: 0.0,
        timestamp: 0,
        sample_count: 8,
        samples: [10, -20, 30, -40, 50, -60, 70, -80],
        optional_present: false,
        optional_field: 0,
    }
}

fn bench_fixed_binary_serialization(c: &mut Criterion) {
    for (name, event) in [
        ("medium_event", medium_event()),
        ("repeated_fields", repeated_fields()),
    ] {
        let wire = encode(&event);

        c.bench_function(&format!("encode_{name}"), |b| {
            b.iter(|| black_box(encode(black_box(&event))))
        });

        c.bench_function(&format!("decode_owned_{name}"), |b| {
            b.iter(|| black_box(decode_owned(black_box(&wire))))
        });

        c.bench_function(&format!("decode_borrowed_device_id_only_{name}"), |b| {
            b.iter(|| black_box(EventView::new(black_box(&wire)).device_id()))
        });
    }
}

criterion_group!(benches, bench_fixed_binary_serialization);
criterion_main!(benches);
