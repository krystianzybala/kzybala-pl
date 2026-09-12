//! Publication harness for the native-Linux evidence runner — Rust
//! counterpart of `ZeroCopyLinuxEvidenceBenchmark`. Runs the correctness
//! oracle before any timing. Prints one JSON document; non-zero exit on
//! any correctness/placement violation.

use std::time::{Duration, Instant};
use zero_copy_binary_parsing_lab::{fixed_header, nested_repeated, utf8_field};

struct Args {
    variant: String,
    dataset: String,
    cpus: Vec<usize>,
    seconds: u64,
    warmup_seconds: u64,
}

fn parse_args() -> Args {
    let mut args = Args {
        variant: "validatedZeroCopyView".into(),
        dataset: "fixedHeaderPlusVariablePayload".into(),
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

    let (checksum, ops, elapsed, n) = match args.dataset.as_str() {
        "fixedHeaderPlusVariablePayload" => {
            let (buf, offsets) = fixed_header::encode(fixed_header::N);
            let expected = fixed_header::expected_checksum(&buf, &offsets, fixed_header::N);
            let mut mutable = buf.clone();
            let actual = match args.variant.as_str() {
                "copyingDecoder" => fixed_header::copying_decoder(&buf, &offsets, fixed_header::N),
                "objectBuildingDecoder" => fixed_header::object_building_decoder(&buf, &offsets, fixed_header::N),
                "validatedZeroCopyView" => fixed_header::validated_zero_copy_view(&buf, &offsets, fixed_header::N),
                "lazyFieldDecode" => fixed_header::lazy_field_decode(&buf, &offsets, fixed_header::N),
                "mutableInPlaceUpdate" => fixed_header::mutable_in_place_update(&mut mutable, &offsets, fixed_header::N),
                other => panic!("unknown variant: {other}"),
            };
            assert_eq!(actual, expected, "correctness oracle failed for {}/{}", args.variant, args.dataset);
            let (c, ops, e) = measure(args.warmup_seconds, args.seconds, || match args.variant.as_str() {
                "copyingDecoder" => fixed_header::copying_decoder(&buf, &offsets, fixed_header::N),
                "objectBuildingDecoder" => fixed_header::object_building_decoder(&buf, &offsets, fixed_header::N),
                "validatedZeroCopyView" => fixed_header::validated_zero_copy_view(&buf, &offsets, fixed_header::N),
                "lazyFieldDecode" => fixed_header::lazy_field_decode(&buf, &offsets, fixed_header::N),
                "mutableInPlaceUpdate" => fixed_header::mutable_in_place_update(&mut mutable, &offsets, fixed_header::N),
                _ => unreachable!(),
            });
            (c, ops, e, fixed_header::N)
        }
        "nestedRepeatedFields" => {
            let (buf, offsets) = nested_repeated::encode(nested_repeated::N);
            let expected = nested_repeated::expected_checksum(&buf, &offsets, nested_repeated::N);
            let mut mutable = buf.clone();
            let actual = match args.variant.as_str() {
                "copyingDecoder" => nested_repeated::copying_decoder(&buf, &offsets, nested_repeated::N),
                "objectBuildingDecoder" => nested_repeated::object_building_decoder(&buf, &offsets, nested_repeated::N),
                "validatedZeroCopyView" => nested_repeated::validated_zero_copy_view(&buf, &offsets, nested_repeated::N),
                "lazyFieldDecode" => nested_repeated::lazy_field_decode(&buf, &offsets, nested_repeated::N),
                "mutableInPlaceUpdate" => nested_repeated::mutable_in_place_update(&mut mutable, &offsets, nested_repeated::N),
                other => panic!("unknown variant: {other}"),
            };
            assert_eq!(actual, expected, "correctness oracle failed for {}/{}", args.variant, args.dataset);
            let (c, ops, e) = measure(args.warmup_seconds, args.seconds, || match args.variant.as_str() {
                "copyingDecoder" => nested_repeated::copying_decoder(&buf, &offsets, nested_repeated::N),
                "objectBuildingDecoder" => nested_repeated::object_building_decoder(&buf, &offsets, nested_repeated::N),
                "validatedZeroCopyView" => nested_repeated::validated_zero_copy_view(&buf, &offsets, nested_repeated::N),
                "lazyFieldDecode" => nested_repeated::lazy_field_decode(&buf, &offsets, nested_repeated::N),
                "mutableInPlaceUpdate" => nested_repeated::mutable_in_place_update(&mut mutable, &offsets, nested_repeated::N),
                _ => unreachable!(),
            });
            (c, ops, e, nested_repeated::N)
        }
        "utf8Field" => {
            let (buf, offsets) = utf8_field::encode(utf8_field::N);
            let expected = utf8_field::expected_checksum(&buf, &offsets, utf8_field::N);
            let mut mutable = buf.clone();
            let actual = match args.variant.as_str() {
                "copyingDecoder" => utf8_field::copying_decoder(&buf, &offsets, utf8_field::N),
                "objectBuildingDecoder" => utf8_field::object_building_decoder(&buf, &offsets, utf8_field::N),
                "validatedZeroCopyView" => utf8_field::validated_zero_copy_view(&buf, &offsets, utf8_field::N),
                "lazyFieldDecode" => utf8_field::lazy_field_decode(&buf, &offsets, utf8_field::N),
                "mutableInPlaceUpdate" => utf8_field::mutable_in_place_update(&mut mutable, &offsets, utf8_field::N),
                other => panic!("unknown variant: {other}"),
            };
            assert_eq!(actual, expected, "correctness oracle failed for {}/{}", args.variant, args.dataset);
            let (c, ops, e) = measure(args.warmup_seconds, args.seconds, || match args.variant.as_str() {
                "copyingDecoder" => utf8_field::copying_decoder(&buf, &offsets, utf8_field::N),
                "objectBuildingDecoder" => utf8_field::object_building_decoder(&buf, &offsets, utf8_field::N),
                "validatedZeroCopyView" => utf8_field::validated_zero_copy_view(&buf, &offsets, utf8_field::N),
                "lazyFieldDecode" => utf8_field::lazy_field_decode(&buf, &offsets, utf8_field::N),
                "mutableInPlaceUpdate" => utf8_field::mutable_in_place_update(&mut mutable, &offsets, utf8_field::N),
                _ => unreachable!(),
            });
            (c, ops, e, utf8_field::N)
        }
        other => panic!("unknown dataset: {other}"),
    };

    let ns_per_op = elapsed.as_nanos() as f64 / ops.max(1) as f64;
    println!(
        "{{\n  \"harness\": \"zero_copy_binary_parsing_evidence\",\n  \"variant\": \"{}\",\n  \"dataset\": \"{}\",\n  \"messagesPerOp\": {},\n  \"ops\": {},\n  \"elapsedNs\": {},\n  \"nsPerOp\": {:.1},\n  \"nsPerMessage\": {:.4},\n  \"checksum\": {}\n}}",
        args.variant, args.dataset, n, ops, elapsed.as_nanos(), ns_per_op, ns_per_op / n as f64, checksum
    );
}
