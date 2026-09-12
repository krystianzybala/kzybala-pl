use affinity_lab::fixtures::{
    expected_memory_scan_checksum, MEMORY_SCAN_SIZE, MEMORY_SCAN_STRIDE, SPSC_HANDOFF_COUNT,
    UDP_PACKET_COUNT, UDP_PACKET_SIZE,
};
use affinity_lab::{
    detect_topology, memory_scan_checksum, run_placed, spsc_handoff_checksum, udp_ingest_checksum,
    PlacementTarget,
};
use criterion::{black_box, criterion_group, criterion_main, Criterion};

fn bench(c: &mut Criterion) {
    let topology = detect_topology();
    black_box(expected_memory_scan_checksum());

    c.bench_function("unpinned_memory_scan", |b| {
        b.iter(|| {
            run_placed(PlacementTarget::UnpinnedBaseline, &topology, || {
                memory_scan_checksum(MEMORY_SCAN_SIZE, MEMORY_SCAN_STRIDE)
            })
        })
    });
    c.bench_function("pinned_isolated_core_memory_scan", |b| {
        b.iter(|| {
            run_placed(PlacementTarget::PinnedIsolatedCore, &topology, || {
                memory_scan_checksum(MEMORY_SCAN_SIZE, MEMORY_SCAN_STRIDE)
            })
        })
    });
    c.bench_function("smt_siblings_spsc_handoff", |b| {
        b.iter(|| {
            run_placed(PlacementTarget::SmtSiblings, &topology, || {
                spsc_handoff_checksum(SPSC_HANDOFF_COUNT)
            })
        })
    });
    c.bench_function("same_numa_node_udp_ingest", |b| {
        b.iter(|| {
            run_placed(PlacementTarget::SameNumaNode, &topology, || {
                udp_ingest_checksum(UDP_PACKET_COUNT, UDP_PACKET_SIZE)
            })
        })
    });
    c.bench_function("remote_numa_node_udp_ingest", |b| {
        b.iter(|| {
            run_placed(PlacementTarget::RemoteNumaNode, &topology, || {
                udp_ingest_checksum(UDP_PACKET_COUNT, UDP_PACKET_SIZE)
            })
        })
    });
}

criterion_group!(benches, bench);
criterion_main!(benches);
