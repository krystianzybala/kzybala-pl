use criterion::{black_box, criterion_group, criterion_main, Criterion};
use shared_memory_ipc_lab::{checksum, payload, run_socket_baseline, SharedRing, PAYLOAD_CAPACITY};
use tempfile::tempdir;

const MESSAGE_COUNT: u64 = 2_000;

fn bench_profile(c: &mut Criterion, name: &str, payload_size: usize) {
    c.bench_function(&format!("socket_baseline_{name}"), |b| {
        b.iter(|| black_box(run_socket_baseline(MESSAGE_COUNT, black_box(payload_size)).unwrap()))
    });

    let dir = tempdir().unwrap();
    let path = dir.path().join(format!("ring-{name}.bin"));
    let mut writer = SharedRing::create_new(&path, 256).unwrap();
    writer.init_writer();
    let mut reader = SharedRing::open_existing(&path).unwrap();
    reader.init_reader();

    c.bench_function(&format!("shared_memory_copy_payload_{name}"), |b| {
        b.iter(|| {
            let mut out = [0u8; PAYLOAD_CAPACITY];
            let mut total = 0u64;
            for i in 0..MESSAGE_COUNT {
                let p = payload(i, payload_size);
                while !writer.try_publish(&p) {
                    std::hint::spin_loop();
                }
                loop {
                    if let Some(len) = reader.try_consume(&mut out) {
                        total = total.wrapping_add(checksum(i, &out[..len]));
                        break;
                    }
                }
            }
            black_box(total)
        })
    });

    c.bench_function(&format!("shared_memory_slot_view_{name}"), |b| {
        b.iter(|| {
            let mut total = 0u64;
            for i in 0..MESSAGE_COUNT {
                let p = payload(i, payload_size);
                while !writer.try_publish(&p) {
                    std::hint::spin_loop();
                }
                loop {
                    if let Some(view) = reader.try_consume_view() {
                        total = total.wrapping_add(checksum(i, view));
                        break;
                    }
                }
            }
            black_box(total)
        })
    });
}

fn bench_shared_memory_ipc(c: &mut Criterion) {
    bench_profile(c, "small32", 32);
    bench_profile(c, "medium128", 128);
    bench_profile(c, "large1kib", 1000);
}

criterion_group!(benches, bench_shared_memory_ipc);
criterion_main!(benches);
