//! Real, standalone process entry point for the producer side of the
//! cross-process correctness harness — launched as a genuinely separate OS
//! process, never as a thread. Usage:
//! `producer <segmentPath> <capacity> <messageCount> <payloadSize> [resume] [startIndex]`.
//! By default creates a fresh segment; pass `resume` as the fifth argument
//! (with the message index to resume from as the sixth) to open an
//! existing segment instead — the restart/recovery scenario's producer
//! side.

use shared_memory_ipc_lab::{payload, SharedRing};
use std::path::PathBuf;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let path = PathBuf::from(&args[1]);
    let capacity: u32 = args[2].parse().unwrap();
    let message_count: u64 = args[3].parse().unwrap();
    let payload_size: usize = args[4].parse().unwrap();
    let resume = args.len() > 5 && args[5] == "resume";
    let start_index: u64 = if args.len() > 6 {
        args[6].parse().unwrap()
    } else {
        0
    };

    let mut ring = if resume {
        let mut r = SharedRing::open_existing(&path).expect("open_existing failed");
        r.resume_writer();
        r
    } else {
        let mut r = SharedRing::create_new(&path, capacity).expect("create_new failed");
        r.init_writer();
        r
    };

    for i in start_index..start_index + message_count {
        let p = payload(i, payload_size);
        while !ring.try_publish(&p) {
            std::hint::spin_loop();
        }
    }
    println!("PRODUCER_DONE {message_count}");
}
