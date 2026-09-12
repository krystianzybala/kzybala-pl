//! Real, standalone process entry point for the consumer side of the
//! cross-process correctness harness. Usage:
//! `consumer <segmentPath> <messageCount> <payloadSize> [startIndex]`.
//! Opens the existing segment (created by a producer process), consumes
//! exactly `messageCount` messages starting at logical index `startIndex`
//! (0 unless this is a resumed consumer in the restart/recovery scenario),
//! verifies each against the same deterministic fixture the producer
//! used, and prints `CONSUMER_OK <count>` on success or
//! `CONSUMER_FAIL <reason>` (with a non-zero exit) on any mismatch.

use shared_memory_ipc_lab::{payload, SharedRing, PAYLOAD_CAPACITY};
use std::path::PathBuf;
use std::process::exit;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let path = PathBuf::from(&args[1]);
    let message_count: u64 = args[2].parse().unwrap();
    let payload_size: usize = args[3].parse().unwrap();
    let start_index: u64 = if args.len() > 4 {
        args[4].parse().unwrap()
    } else {
        0
    };

    let mut ring = SharedRing::open_existing(&path).expect("open_existing failed");
    ring.init_reader();

    let mut buf = [0u8; PAYLOAD_CAPACITY];
    let mut received: u64 = 0;
    let mut expected_index = start_index;
    while received < message_count {
        let len = match ring.try_consume(&mut buf) {
            Some(l) => l,
            None => {
                std::hint::spin_loop();
                continue;
            }
        };
        let expected = payload(expected_index, payload_size);
        if buf[..payload_size] != expected[..] || len != payload_size {
            println!("CONSUMER_FAIL mismatch-at-index-{expected_index}");
            exit(1);
        }
        expected_index += 1;
        received += 1;
    }
    println!("CONSUMER_OK {received}");
}
