use criterion::{black_box, criterion_group, criterion_main, Criterion};
use std::net::UdpSocket;
use std::thread;
use std::time::Duration;
use udp_ingest_batching_lab::{
    send_burst, CopyingHandoffPipeline, RawReceiveKernel, ZeroCopyHandoffPipeline,
};

const MESSAGE_COUNT: u64 = 4_000;
const CAPACITY: usize = 4096;

fn free_port() -> u16 {
    let socket = UdpSocket::bind("127.0.0.1:0").unwrap();
    socket.local_addr().unwrap().port()
}

fn bench_profile(c: &mut Criterion, name: &str, datagram_size: usize) {
    c.bench_function(&format!("one_packet_per_receive_{name}"), |b| {
        b.iter(|| {
            let port = free_port();
            let kernel = RawReceiveKernel::bind(port).unwrap();
            let sender = thread::spawn(move || {
                thread::sleep(Duration::from_millis(20));
                send_burst(port, MESSAGE_COUNT, datagram_size).unwrap();
            });
            let validated = kernel
                .receive_allocating_per_packet(MESSAGE_COUNT, datagram_size)
                .unwrap();
            sender.join().unwrap();
            black_box(validated)
        })
    });

    c.bench_function(&format!("reused_direct_buffer_{name}"), |b| {
        b.iter(|| {
            let port = free_port();
            let kernel = RawReceiveKernel::bind(port).unwrap();
            let sender = thread::spawn(move || {
                thread::sleep(Duration::from_millis(20));
                send_burst(port, MESSAGE_COUNT, datagram_size).unwrap();
            });
            let validated = kernel
                .receive_with_reused_buffer(MESSAGE_COUNT, datagram_size)
                .unwrap();
            sender.join().unwrap();
            black_box(validated)
        })
    });

    c.bench_function(&format!("copying_handoff_{name}"), |b| {
        b.iter(|| {
            let port = free_port();
            let pipeline = CopyingHandoffPipeline::bind(port, CAPACITY).unwrap();
            let sender = thread::spawn(move || {
                thread::sleep(Duration::from_millis(20));
                send_burst(port, MESSAGE_COUNT, datagram_size).unwrap();
            });
            let result = pipeline.run(MESSAGE_COUNT, datagram_size).unwrap();
            sender.join().unwrap();
            black_box(result)
        })
    });

    c.bench_function(&format!("zero_copy_view_handoff_{name}"), |b| {
        b.iter(|| {
            let port = free_port();
            let mut pipeline = ZeroCopyHandoffPipeline::bind(port, CAPACITY).unwrap();
            let sender = thread::spawn(move || {
                thread::sleep(Duration::from_millis(20));
                send_burst(port, MESSAGE_COUNT, datagram_size).unwrap();
            });
            let result = pipeline.run(MESSAGE_COUNT, datagram_size).unwrap();
            sender.join().unwrap();
            black_box(result)
        })
    });
}

fn bench_udp_ingest_batching(c: &mut Criterion) {
    bench_profile(c, "small64", 64);
    bench_profile(c, "medium256", 256);
    bench_profile(c, "large1400", 1400);
}

criterion_group!(benches, bench_udp_ingest_batching);
criterion_main!(benches);
