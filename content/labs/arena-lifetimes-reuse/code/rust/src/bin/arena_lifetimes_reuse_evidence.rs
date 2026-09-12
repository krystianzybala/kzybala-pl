//! Publication harness for the native-Linux evidence runner — Rust
//! counterpart of `ArenaReuseLinuxEvidenceBenchmark`. Runs the
//! correctness oracle before any timing. Prints one JSON document;
//! non-zero exit on any correctness/placement violation.

use arena_lifetimes_reuse_lab::{message_batches, parse_trees, scratch_buffers, BoundedPool, UnboundedPool};
use std::time::{Duration, Instant};

struct Args {
    variant: String,
    dataset: String,
    cpus: Vec<usize>,
    seconds: u64,
    warmup_seconds: u64,
}

fn parse_args() -> Args {
    let mut args = Args {
        variant: "allocatePerItem".into(),
        dataset: "messageBatches".into(),
        cpus: vec![],
        seconds: 5,
        warmup_seconds: 2,
    };
    let argv: Vec<String> = std::env::args().collect();
    let mut i = 1;
    while i < argv.len() {
        let value = argv
            .get(i + 1)
            .unwrap_or_else(|| panic!("missing value for {}", argv[i]))
            .clone();
        match argv[i].as_str() {
            "--variant" => args.variant = value,
            "--dataset" => args.dataset = value,
            "--cpus" => args.cpus = value.split(',').map(|c| c.parse().expect("--cpus")).collect(),
            "--seconds" => args.seconds = value.parse().expect("--seconds"),
            "--warmup-seconds" => args.warmup_seconds = value.parse().expect("--warmup-seconds"),
            other => panic!("unknown option: {other}"),
        }
        i += 2;
    }
    args
}

#[cfg(target_os = "linux")]
mod affinity {
    pub fn pin_current_thread(cpu: usize) {
        unsafe {
            let mut set: libc::cpu_set_t = std::mem::zeroed();
            libc::CPU_SET(cpu, &mut set);
            let rc = libc::sched_setaffinity(0, std::mem::size_of::<libc::cpu_set_t>(), &set);
            assert_eq!(rc, 0, "sched_setaffinity(cpu={cpu}) failed");
        }
        std::thread::yield_now();
    }
}

#[cfg(not(target_os = "linux"))]
mod affinity {
    pub fn pin_current_thread(_cpu: usize) {
        panic!("worker pinning requires Linux — publication evidence comes only from the dedicated native-Linux host");
    }
}

fn measure(warmup_seconds: u64, seconds: u64, mut op: impl FnMut() -> u64) -> (u64, u64, Duration) {
    let warm_until = Instant::now() + Duration::from_secs(warmup_seconds);
    let mut checksum: u64 = 0;
    while Instant::now() < warm_until {
        checksum = checksum.wrapping_add(op());
    }
    let start = Instant::now();
    let stop_after = Duration::from_secs(seconds);
    let mut ops: u64 = 0;
    while start.elapsed() < stop_after {
        checksum = checksum.wrapping_add(op());
        ops += 1;
    }
    (checksum, ops, start.elapsed())
}

fn main() {
    let args = parse_args();
    if let Some(cpu) = args.cpus.first().copied() {
        affinity::pin_current_thread(cpu);
    }

    let (checksum, ops, elapsed) = match args.dataset.as_str() {
        "messageBatches" => {
            let s = message_batches::generate(message_batches::N);
            let expected = message_batches::expected_checksum(&s);
            let unbounded = UnboundedPool::new(message_batches::MessageScratch::default);
            let bounded = BoundedPool::new(message_batches::POOL_CAPACITY, message_batches::MessageScratch::default);
            let checksum = match args.variant.as_str() {
                "allocatePerItem" => message_batches::allocate_per_item(&s),
                "batchArena" => message_batches::batch_arena(&s, message_batches::BATCH_SIZE),
                "threadLocalReuse" => message_batches::thread_local_reuse(&s),
                "globalPool" => message_batches::global_pool(&s, &unbounded),
                "boundedPool" => message_batches::bounded_pool(&s, &bounded),
                other => panic!("unknown variant: {other}"),
            };
            assert_eq!(checksum, expected, "correctness oracle failed for {}/{}", args.variant, args.dataset);
            measure(args.warmup_seconds, args.seconds, || match args.variant.as_str() {
                "allocatePerItem" => message_batches::allocate_per_item(&s),
                "batchArena" => message_batches::batch_arena(&s, message_batches::BATCH_SIZE),
                "threadLocalReuse" => message_batches::thread_local_reuse(&s),
                "globalPool" => message_batches::global_pool(&s, &unbounded),
                "boundedPool" => message_batches::bounded_pool(&s, &bounded),
                _ => unreachable!(),
            })
        }
        "temporaryParseTrees" => {
            let value = parse_trees::generate(parse_trees::DOC_COUNT, parse_trees::NODES_PER_DOC);
            let expected = parse_trees::expected_checksum(&value);
            let unbounded = UnboundedPool::new(Vec::new);
            let bounded = BoundedPool::new(parse_trees::POOL_CAPACITY, Vec::new);
            let checksum = match args.variant.as_str() {
                "allocatePerItem" => parse_trees::allocate_per_item(&value, parse_trees::NODES_PER_DOC),
                "batchArena" => parse_trees::batch_arena(&value, parse_trees::NODES_PER_DOC, parse_trees::DOCS_PER_BATCH),
                "threadLocalReuse" => parse_trees::thread_local_reuse(&value, parse_trees::NODES_PER_DOC),
                "globalPool" => parse_trees::global_pool(&value, parse_trees::NODES_PER_DOC, &unbounded),
                "boundedPool" => parse_trees::bounded_pool(&value, parse_trees::NODES_PER_DOC, &bounded),
                other => panic!("unknown variant: {other}"),
            };
            assert_eq!(checksum, expected, "correctness oracle failed for {}/{}", args.variant, args.dataset);
            measure(args.warmup_seconds, args.seconds, || match args.variant.as_str() {
                "allocatePerItem" => parse_trees::allocate_per_item(&value, parse_trees::NODES_PER_DOC),
                "batchArena" => parse_trees::batch_arena(&value, parse_trees::NODES_PER_DOC, parse_trees::DOCS_PER_BATCH),
                "threadLocalReuse" => parse_trees::thread_local_reuse(&value, parse_trees::NODES_PER_DOC),
                "globalPool" => parse_trees::global_pool(&value, parse_trees::NODES_PER_DOC, &unbounded),
                "boundedPool" => parse_trees::bounded_pool(&value, parse_trees::NODES_PER_DOC, &bounded),
                _ => unreachable!(),
            })
        }
        "scratchBuffers" => {
            let word = scratch_buffers::generate(scratch_buffers::OP_COUNT, scratch_buffers::WORDS_PER_OP);
            let expected = scratch_buffers::expected_checksum(&word);
            let unbounded = UnboundedPool::new(Vec::new);
            let bounded = BoundedPool::new(scratch_buffers::POOL_CAPACITY, Vec::new);
            let checksum = match args.variant.as_str() {
                "allocatePerItem" => scratch_buffers::allocate_per_item(&word, scratch_buffers::WORDS_PER_OP),
                "batchArena" => {
                    scratch_buffers::batch_arena(&word, scratch_buffers::WORDS_PER_OP, scratch_buffers::OPS_PER_BATCH)
                }
                "threadLocalReuse" => scratch_buffers::thread_local_reuse(&word, scratch_buffers::WORDS_PER_OP),
                "globalPool" => scratch_buffers::global_pool(&word, scratch_buffers::WORDS_PER_OP, &unbounded),
                "boundedPool" => scratch_buffers::bounded_pool(&word, scratch_buffers::WORDS_PER_OP, &bounded),
                other => panic!("unknown variant: {other}"),
            };
            assert_eq!(checksum, expected, "correctness oracle failed for {}/{}", args.variant, args.dataset);
            measure(args.warmup_seconds, args.seconds, || match args.variant.as_str() {
                "allocatePerItem" => scratch_buffers::allocate_per_item(&word, scratch_buffers::WORDS_PER_OP),
                "batchArena" => {
                    scratch_buffers::batch_arena(&word, scratch_buffers::WORDS_PER_OP, scratch_buffers::OPS_PER_BATCH)
                }
                "threadLocalReuse" => scratch_buffers::thread_local_reuse(&word, scratch_buffers::WORDS_PER_OP),
                "globalPool" => scratch_buffers::global_pool(&word, scratch_buffers::WORDS_PER_OP, &unbounded),
                "boundedPool" => scratch_buffers::bounded_pool(&word, scratch_buffers::WORDS_PER_OP, &bounded),
                _ => unreachable!(),
            })
        }
        other => panic!("unknown dataset: {other}"),
    };

    let ns_per_op = elapsed.as_nanos() as f64 / ops.max(1) as f64;
    println!(
        "{{\n  \"harness\": \"arena_lifetimes_reuse_evidence\",\n  \"variant\": \"{}\",\n  \"dataset\": \"{}\",\n  \"ops\": {},\n  \"elapsedNs\": {},\n  \"nsPerOp\": {:.1},\n  \"checksum\": {}\n}}",
        args.variant, args.dataset, ops, elapsed.as_nanos(), ns_per_op, checksum
    );
}
