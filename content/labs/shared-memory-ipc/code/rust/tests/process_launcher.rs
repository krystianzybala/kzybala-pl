//! The real cross-process correctness proof this lab's traps list requires
//! ("missing cross-process memory-order proof"): the `producer` and
//! `consumer` binaries are launched as genuinely separate OS processes via
//! `std::process::Command`, each its own process with its own independent
//! memory mapping of the shared backing file — not threads sharing one
//! Rust value.

use std::path::PathBuf;
use std::process::{Command, Output};
use std::time::{Duration, Instant};

fn binary_path(name: &str) -> PathBuf {
    let mut path = std::env::current_exe().unwrap();
    path.pop(); // deps/
    path.pop(); // release/ or debug/
    path.push(name);
    path
}

fn wait_for_file_size(path: &std::path::Path, expected: u64, timeout: Duration) {
    let deadline = Instant::now() + timeout;
    while Instant::now() < deadline {
        if let Ok(meta) = std::fs::metadata(path) {
            if meta.len() >= expected {
                return;
            }
        }
        std::thread::sleep(Duration::from_millis(10));
    }
    panic!("segment file never reached expected size {expected}");
}

fn output_text(output: &Output) -> String {
    format!(
        "{}{}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    )
}

#[test]
fn producer_and_consumer_as_real_separate_processes_deliver_every_message() {
    let dir = tempfile::tempdir().unwrap();
    let segment = dir.path().join("ring.bin");
    let capacity: u32 = 256;
    let message_count: u64 = 5_000;
    let payload_size: usize = 64;

    let mut producer = Command::new(binary_path("producer"))
        .args([
            segment.to_str().unwrap(),
            &capacity.to_string(),
            &message_count.to_string(),
            &payload_size.to_string(),
        ])
        .spawn()
        .expect("failed to spawn producer");

    let expected_size = shared_memory_ipc_lab::segment_size(capacity);
    wait_for_file_size(&segment, expected_size, Duration::from_secs(5));

    let consumer_output = Command::new(binary_path("consumer"))
        .args([
            segment.to_str().unwrap(),
            &message_count.to_string(),
            &payload_size.to_string(),
        ])
        .output()
        .expect("failed to run consumer");

    let producer_status = producer.wait().expect("failed to wait for producer");

    assert!(producer_status.success(), "producer failed");
    assert!(
        consumer_output.status.success(),
        "consumer failed: {}",
        output_text(&consumer_output)
    );
    assert!(
        output_text(&consumer_output).contains(&format!("CONSUMER_OK {message_count}")),
        "unexpected consumer output: {}",
        output_text(&consumer_output)
    );
}

#[test]
fn restarted_consumer_resumes_without_loss_or_duplication() {
    let dir = tempfile::tempdir().unwrap();
    let segment = dir.path().join("ring.bin");
    let capacity: u32 = 256;
    let payload_size: usize = 32;
    let first_batch: u64 = 2_000;
    let second_batch: u64 = 2_000;

    let mut producer = Command::new(binary_path("producer"))
        .args([
            segment.to_str().unwrap(),
            &capacity.to_string(),
            &(first_batch + second_batch).to_string(),
            &payload_size.to_string(),
        ])
        .spawn()
        .expect("failed to spawn producer");

    wait_for_file_size(
        &segment,
        shared_memory_ipc_lab::segment_size(capacity),
        Duration::from_secs(5),
    );

    let first_output = Command::new(binary_path("consumer"))
        .args([
            segment.to_str().unwrap(),
            &first_batch.to_string(),
            &payload_size.to_string(),
            "0",
        ])
        .output()
        .expect("failed to run first consumer");
    assert!(
        first_output.status.success(),
        "first consumer failed: {}",
        output_text(&first_output)
    );
    assert!(output_text(&first_output).contains(&format!("CONSUMER_OK {first_batch}")));

    // A freshly-started consumer resumes from the segment's own recorded
    // reader_seq, not from a value the first consumer instance remembered.
    let second_output = Command::new(binary_path("consumer"))
        .args([
            segment.to_str().unwrap(),
            &second_batch.to_string(),
            &payload_size.to_string(),
            &first_batch.to_string(),
        ])
        .output()
        .expect("failed to run second consumer");
    assert!(
        second_output.status.success(),
        "second consumer failed: {}",
        output_text(&second_output)
    );
    assert!(output_text(&second_output).contains(&format!("CONSUMER_OK {second_batch}")));

    let producer_status = producer.wait().expect("failed to wait for producer");
    assert!(producer_status.success());
}
