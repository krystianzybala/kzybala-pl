//! Publication harness for the native-Linux evidence runner — the Rust
//! counterpart of `AllocationObjectLayoutLinuxEvidenceBenchmark`, with
//! strict methodology parity: identical dataset generation, identical
//! two-operation contract (`construct` builds a fresh representation
//! every call; `sumHot` reads back one already-built, persistent
//! representation), single pinned worker. Dataset generation and the
//! correctness oracle both run once, before any timing — never inside
//! the measured loop. Prints one JSON document; non-zero exit on any
//! correctness/placement violation.

use allocation_object_layout_lab::{
    boxed_object_graph, expected_orders_checksum, expected_tree_checksum,
    expected_tuples_checksum, flat_primitive_arrays, generate_orders, generate_tree_values,
    generate_tuples, packed_off_heap_struct, reused_mutable_holder, OrdersSource, TuplesSource,
    ORDERS_N, TREE_N, TUPLES_N,
};
use std::time::{Duration, Instant};

struct Args {
    variant: String,
    dataset: String,
    op: String,
    cpus: Vec<usize>,
    seconds: u64,
    warmup_seconds: u64,
}

fn parse_args() -> Args {
    let mut args = Args {
        variant: "boxedObjectGraph".into(),
        dataset: "ordersQuotes".into(),
        op: "sumHot".into(),
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
            "--op" => args.op = value,
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
        let observed = current_cpu();
        assert_eq!(observed, cpu as i32, "pinned to {cpu} but running on {observed}");
    }
    pub fn current_cpu() -> i32 {
        unsafe { libc::sched_getcpu() }
    }
}

#[cfg(not(target_os = "linux"))]
mod affinity {
    pub fn pin_current_thread(_cpu: usize) {
        panic!("worker pinning requires Linux — publication evidence comes only from the dedicated native-Linux host");
    }
    pub fn current_cpu() -> i32 {
        -1
    }
}

/// Runs the warmup+measurement loop against a closure that is ALREADY
/// scoped to (variant, op) — the closure itself decides whether to
/// rebuild (`construct`) or read a captured, persistent representation
/// (`sumHot`); this function only owns timing.
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

fn run_orders(args: &Args) -> (u64, u64, u64, Duration) {
    let source: OrdersSource = generate_orders(ORDERS_N);
    let expected = expected_orders_checksum(&source);
    let boxed = boxed_object_graph::build_orders(&source);
    let flat = flat_primitive_arrays::build_orders(&source);
    let packed = packed_off_heap_struct::build_orders(&source);

    let verify = match args.variant.as_str() {
        "boxedObjectGraph" => boxed_object_graph::sum_orders(&boxed),
        "flatPrimitiveArrays" => flat_primitive_arrays::sum_orders(&flat),
        "packedOffHeapStruct" => packed_off_heap_struct::sum_orders(&packed),
        "reusedMutableHolder" => reused_mutable_holder::stream_orders(&source),
        other => panic!("unknown variant: {other}"),
    };
    assert_eq!(verify, expected, "correctness oracle failed for {}/{}", args.variant, args.dataset);

    let (checksum, ops, elapsed) = match (args.op.as_str(), args.variant.as_str()) {
        ("construct", "boxedObjectGraph") => measure(args.warmup_seconds, args.seconds, || {
            boxed_object_graph::sum_orders(&boxed_object_graph::build_orders(&source))
        }),
        ("construct", "flatPrimitiveArrays") => measure(args.warmup_seconds, args.seconds, || {
            flat_primitive_arrays::sum_orders(&flat_primitive_arrays::build_orders(&source))
        }),
        ("construct", "packedOffHeapStruct") => measure(args.warmup_seconds, args.seconds, || {
            packed_off_heap_struct::sum_orders(&packed_off_heap_struct::build_orders(&source))
        }),
        ("construct", "reusedMutableHolder") => {
            measure(args.warmup_seconds, args.seconds, || reused_mutable_holder::stream_orders(&source))
        }
        ("sumHot", "boxedObjectGraph") => {
            measure(args.warmup_seconds, args.seconds, || boxed_object_graph::sum_orders(&boxed))
        }
        ("sumHot", "flatPrimitiveArrays") => {
            measure(args.warmup_seconds, args.seconds, || flat_primitive_arrays::sum_orders(&flat))
        }
        ("sumHot", "packedOffHeapStruct") => {
            measure(args.warmup_seconds, args.seconds, || packed_off_heap_struct::sum_orders(&packed))
        }
        ("sumHot", "reusedMutableHolder") => {
            measure(args.warmup_seconds, args.seconds, || reused_mutable_holder::stream_orders(&source))
        }
        (op, variant) => panic!("unknown op/variant: {op}/{variant}"),
    };
    (checksum, ops, ORDERS_N as u64, elapsed)
}

fn run_tree(args: &Args) -> (u64, u64, u64, Duration) {
    let value = generate_tree_values(TREE_N);
    let expected = expected_tree_checksum(&value);
    let boxed = boxed_object_graph::build_tree(&value);
    let flat = flat_primitive_arrays::build_tree(&value);
    let packed = packed_off_heap_struct::build_tree(&value);

    let verify = match args.variant.as_str() {
        "boxedObjectGraph" => boxed_object_graph::sum_tree(&boxed),
        "flatPrimitiveArrays" => flat_primitive_arrays::sum_tree(&flat),
        "packedOffHeapStruct" => packed_off_heap_struct::sum_tree(&packed),
        "reusedMutableHolder" => reused_mutable_holder::stream_tree(&value),
        other => panic!("unknown variant: {other}"),
    };
    assert_eq!(verify, expected, "correctness oracle failed for {}/{}", args.variant, args.dataset);

    let (checksum, ops, elapsed) = match (args.op.as_str(), args.variant.as_str()) {
        ("construct", "boxedObjectGraph") => measure(args.warmup_seconds, args.seconds, || {
            boxed_object_graph::sum_tree(&boxed_object_graph::build_tree(&value))
        }),
        ("construct", "flatPrimitiveArrays") => measure(args.warmup_seconds, args.seconds, || {
            flat_primitive_arrays::sum_tree(&flat_primitive_arrays::build_tree(&value))
        }),
        ("construct", "packedOffHeapStruct") => measure(args.warmup_seconds, args.seconds, || {
            packed_off_heap_struct::sum_tree(&packed_off_heap_struct::build_tree(&value))
        }),
        ("construct", "reusedMutableHolder") => {
            measure(args.warmup_seconds, args.seconds, || reused_mutable_holder::stream_tree(&value))
        }
        ("sumHot", "boxedObjectGraph") => {
            measure(args.warmup_seconds, args.seconds, || boxed_object_graph::sum_tree(&boxed))
        }
        ("sumHot", "flatPrimitiveArrays") => {
            measure(args.warmup_seconds, args.seconds, || flat_primitive_arrays::sum_tree(&flat))
        }
        ("sumHot", "packedOffHeapStruct") => {
            measure(args.warmup_seconds, args.seconds, || packed_off_heap_struct::sum_tree(&packed))
        }
        ("sumHot", "reusedMutableHolder") => {
            measure(args.warmup_seconds, args.seconds, || reused_mutable_holder::stream_tree(&value))
        }
        (op, variant) => panic!("unknown op/variant: {op}/{variant}"),
    };
    (checksum, ops, TREE_N as u64, elapsed)
}

fn run_tuples(args: &Args) -> (u64, u64, u64, Duration) {
    let source: TuplesSource = generate_tuples(TUPLES_N);
    let expected = expected_tuples_checksum(&source);
    let boxed = boxed_object_graph::build_tuples(&source);
    let flat = flat_primitive_arrays::build_tuples(&source);
    let packed = packed_off_heap_struct::build_tuples(&source);

    let verify = match args.variant.as_str() {
        "boxedObjectGraph" => boxed_object_graph::sum_tuples(&boxed),
        "flatPrimitiveArrays" => flat_primitive_arrays::sum_tuples(&flat),
        "packedOffHeapStruct" => packed_off_heap_struct::sum_tuples(&packed),
        "reusedMutableHolder" => reused_mutable_holder::stream_tuples(&source),
        other => panic!("unknown variant: {other}"),
    };
    assert_eq!(verify, expected, "correctness oracle failed for {}/{}", args.variant, args.dataset);

    let (checksum, ops, elapsed) = match (args.op.as_str(), args.variant.as_str()) {
        ("construct", "boxedObjectGraph") => measure(args.warmup_seconds, args.seconds, || {
            boxed_object_graph::sum_tuples(&boxed_object_graph::build_tuples(&source))
        }),
        ("construct", "flatPrimitiveArrays") => measure(args.warmup_seconds, args.seconds, || {
            flat_primitive_arrays::sum_tuples(&flat_primitive_arrays::build_tuples(&source))
        }),
        ("construct", "packedOffHeapStruct") => measure(args.warmup_seconds, args.seconds, || {
            packed_off_heap_struct::sum_tuples(&packed_off_heap_struct::build_tuples(&source))
        }),
        ("construct", "reusedMutableHolder") => {
            measure(args.warmup_seconds, args.seconds, || reused_mutable_holder::stream_tuples(&source))
        }
        ("sumHot", "boxedObjectGraph") => {
            measure(args.warmup_seconds, args.seconds, || boxed_object_graph::sum_tuples(&boxed))
        }
        ("sumHot", "flatPrimitiveArrays") => {
            measure(args.warmup_seconds, args.seconds, || flat_primitive_arrays::sum_tuples(&flat))
        }
        ("sumHot", "packedOffHeapStruct") => {
            measure(args.warmup_seconds, args.seconds, || packed_off_heap_struct::sum_tuples(&packed))
        }
        ("sumHot", "reusedMutableHolder") => {
            measure(args.warmup_seconds, args.seconds, || reused_mutable_holder::stream_tuples(&source))
        }
        (op, variant) => panic!("unknown op/variant: {op}/{variant}"),
    };
    (checksum, ops, TUPLES_N as u64, elapsed)
}

fn main() {
    let args = parse_args();
    let pinned = if let Some(cpu) = args.cpus.first().copied() {
        affinity::pin_current_thread(cpu);
        true
    } else {
        false
    };

    let (checksum, ops, n, elapsed) = match args.dataset.as_str() {
        "ordersQuotes" => run_orders(&args),
        "treeNodes" => run_tree(&args),
        "smallTuples" => run_tuples(&args),
        other => panic!("unknown dataset: {other}"),
    };

    let ns_per_op = elapsed.as_nanos() as f64 / ops.max(1) as f64;

    println!(
        "{{\n  \"harness\": \"allocation_object_layout_evidence (single pinned worker)\",\n  \"variant\": \"{}\",\n  \"dataset\": \"{}\",\n  \"op\": \"{}\",\n  \"recordsPerOp\": {},\n  \"ops\": {},\n  \"elapsedNs\": {},\n  \"nsPerOp\": {:.1},\n  \"nsPerRecord\": {:.4},\n  \"checksum\": {},\n  \"pinned\": {},\n  \"observedCpuAtEnd\": {}\n}}",
        args.variant, args.dataset, args.op, n, ops, elapsed.as_nanos(),
        ns_per_op, ns_per_op / n as f64, checksum, pinned, affinity::current_cpu()
    );

    if ops == 0 {
        eprintln!("no operations completed");
        std::process::exit(1);
    }
}
