use criterion::{black_box, criterion_group, criterion_main, Criterion};
use std::thread;
use syscall_amortization_scatter_gather_lab::{
    coalesced_buffer, receive, scatter_gather_write, single_small_write, size_bounded_batch,
    LoopbackConnection,
};

fn bench_profile(c: &mut Criterion, name: &str, message_count: u64, payload_size: usize) {
    c.bench_function(&format!("single_small_write_{name}"), |b| {
        b.iter(|| {
            let conn = LoopbackConnection::open().unwrap();
            let mut server = conn.server;
            let mut client = conn.client;
            let receiver = thread::spawn(move || receive(&mut server, message_count, 0).unwrap());
            let result = single_small_write(&mut client, message_count, payload_size).unwrap();
            receiver.join().unwrap();
            black_box(result)
        })
    });

    c.bench_function(&format!("coalesced_buffer_{name}"), |b| {
        b.iter(|| {
            let conn = LoopbackConnection::open().unwrap();
            let mut server = conn.server;
            let mut client = conn.client;
            let receiver = thread::spawn(move || receive(&mut server, message_count, 0).unwrap());
            let result = coalesced_buffer(&mut client, message_count, payload_size).unwrap();
            receiver.join().unwrap();
            black_box(result)
        })
    });

    c.bench_function(&format!("scatter_gather_write_{name}"), |b| {
        b.iter(|| {
            let conn = LoopbackConnection::open().unwrap();
            let mut server = conn.server;
            let mut client = conn.client;
            let receiver = thread::spawn(move || receive(&mut server, message_count, 0).unwrap());
            let result = scatter_gather_write(&mut client, message_count, payload_size).unwrap();
            receiver.join().unwrap();
            black_box(result)
        })
    });

    c.bench_function(&format!("size_bounded_batch_{name}"), |b| {
        b.iter(|| {
            let conn = LoopbackConnection::open().unwrap();
            let mut server = conn.server;
            let mut client = conn.client;
            let receiver = thread::spawn(move || receive(&mut server, message_count, 0).unwrap());
            let result = size_bounded_batch(&mut client, message_count, payload_size, 20).unwrap();
            receiver.join().unwrap();
            black_box(result)
        })
    });
}

fn bench_syscall_amortization(c: &mut Criterion) {
    bench_profile(c, "header_plus_payload", 2000, 256);
    bench_profile(c, "small_messages", 5000, 8);
}

criterion_group!(benches, bench_syscall_amortization);
criterion_main!(benches);
