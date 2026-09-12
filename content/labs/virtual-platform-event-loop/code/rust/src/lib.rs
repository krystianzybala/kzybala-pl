//! Companion crate for the "Virtual Threads vs Platform Threads vs Event Loops" lab.
//! Rust has no lightweight virtual-thread runtime in `std`, and per this repo's policy
//! we do not add an async runtime solely to chase Java's virtual threads (design.md:
//! "avoid adding a runtime solely for the lab without justification"). The
//! `thread_per_request` and `high_fanout_os_threads` variants are both OS-thread-based;
//! `high_fanout_os_threads` is Rust's documented, non-comparable counterpart to Java's
//! "virtual thread per task" scenario (a deliberate design trade-off, not a language
//! speed result — see the equivalence contract in theory.md).

use std::cmp::Reverse;
use std::collections::{BinaryHeap, VecDeque};
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::sync::Mutex;
use std::thread;
use std::time::{Duration, Instant};

#[derive(Clone, Copy)]
pub struct TaskSpec {
    pub id: u32,
    pub io_wait_nanos: u64,
    pub cpu_iterations: u32,
    pub synchronized_wrap: bool,
}

#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub struct RunResult {
    pub total_checksum: u64,
    pub completed_count: usize,
}

/// Pure function of (id, iterations): xorshift64 over wrapping 64-bit arithmetic.
/// Never depends on which thread or scheduling model executed it.
pub fn cpu_checksum(id: u32, iterations: u32) -> u64 {
    let mut x: u64 = (id as u64)
        .wrapping_mul(0x9E37_79B9_7F4A_7C15)
        .wrapping_add(1);
    for _ in 0..iterations {
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
    }
    x
}

pub fn blocking_wait(nanos: u64) {
    if nanos > 0 {
        thread::sleep(Duration::from_nanos(nanos));
    }
}

/// Mirrors Java's single shared PIN_MONITOR: every task using the lock/native-pinning
/// dataset serializes through this one mutex, held across the simulated blocking wait.
static PIN_LOCK: Mutex<()> = Mutex::new(());

fn run_one(t: &TaskSpec) -> u64 {
    if t.synchronized_wrap {
        let _guard = PIN_LOCK.lock().unwrap();
        blocking_wait(t.io_wait_nanos);
        cpu_checksum(t.id, t.cpu_iterations)
    } else {
        blocking_wait(t.io_wait_nanos);
        cpu_checksum(t.id, t.cpu_iterations)
    }
}

/// One new OS thread per task, joined at the end — the thread-per-request baseline.
pub fn thread_per_request(tasks: &[TaskSpec]) -> RunResult {
    let sum = AtomicU64::new(0);
    let completed = AtomicUsize::new(0);
    thread::scope(|scope| {
        for t in tasks {
            let sum = &sum;
            let completed = &completed;
            scope.spawn(move || {
                let v = run_one(t);
                sum.fetch_add(v, Ordering::Relaxed);
                completed.fetch_add(1, Ordering::Relaxed);
            });
        }
    });
    RunResult {
        total_checksum: sum.load(Ordering::Relaxed),
        completed_count: completed.load(Ordering::Relaxed),
    }
}

/// Rust's non-comparable counterpart to Java's virtual-thread-per-task variant: still one
/// OS thread per task (no virtual threads exist), kept as a separate named scenario.
pub fn high_fanout_os_threads(tasks: &[TaskSpec]) -> RunResult {
    thread_per_request(tasks)
}

/// A single loop "thread" driven by a deadline min-heap: the wait component never blocks
/// the loop longer than the gap between successive deadlines, but every CPU stage still
/// executes serially on this one thread — showing a long CPU stage stalling the loop.
pub fn fixed_event_loop(tasks: &[TaskSpec]) -> RunResult {
    let start = Instant::now();
    let mut heap: BinaryHeap<Reverse<(u64, usize)>> = BinaryHeap::with_capacity(tasks.len());
    for (i, t) in tasks.iter().enumerate() {
        heap.push(Reverse((t.io_wait_nanos, i)));
    }
    let mut sum: u64 = 0;
    let mut completed = 0usize;
    while let Some(Reverse((deadline, idx))) = heap.pop() {
        let target = start + Duration::from_nanos(deadline);
        let now = Instant::now();
        if target > now {
            thread::sleep(target - now);
        }
        let t = &tasks[idx];
        sum = sum.wrapping_add(cpu_checksum(t.id, t.cpu_iterations));
        completed += 1;
    }
    RunResult {
        total_checksum: sum,
        completed_count: completed,
    }
}

/// A fixed pool of OS threads sized to the available core count, pulling from one shared queue.
pub fn cpu_bound_pool(tasks: &[TaskSpec]) -> RunResult {
    let pool_size = thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(1);
    let queue: Mutex<VecDeque<usize>> = Mutex::new((0..tasks.len()).collect());
    let sum = AtomicU64::new(0);
    let completed = AtomicUsize::new(0);
    thread::scope(|scope| {
        for _ in 0..pool_size {
            let queue = &queue;
            let sum = &sum;
            let completed = &completed;
            scope.spawn(move || loop {
                let next = queue.lock().unwrap().pop_front();
                match next {
                    Some(i) => {
                        let v = run_one(&tasks[i]);
                        sum.fetch_add(v, Ordering::Relaxed);
                        completed.fetch_add(1, Ordering::Relaxed);
                    }
                    None => break,
                }
            });
        }
    });
    RunResult {
        total_checksum: sum.load(Ordering::Relaxed),
        completed_count: completed.load(Ordering::Relaxed),
    }
}

/// One OS thread per task absorbs the blocking wait, then hands the CPU stage off to a
/// small fixed pool — isolating CPU-bound work from the high-fan-out blocking layer.
pub fn mixed(tasks: &[TaskSpec]) -> RunResult {
    let pool_size = thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(1);
    let (tx, rx) = std::sync::mpsc::channel::<usize>();
    let rx = Mutex::new(rx);
    let sum = AtomicU64::new(0);
    let completed = AtomicUsize::new(0);
    thread::scope(|scope| {
        for _ in 0..pool_size {
            let rx = &rx;
            let sum = &sum;
            let completed = &completed;
            scope.spawn(move || loop {
                let received = rx.lock().unwrap().recv();
                match received {
                    Ok(i) => {
                        let t = &tasks[i];
                        let v = cpu_checksum(t.id, t.cpu_iterations);
                        sum.fetch_add(v, Ordering::Relaxed);
                        completed.fetch_add(1, Ordering::Relaxed);
                    }
                    Err(_) => break,
                }
            });
        }

        let handles: Vec<_> = tasks
            .iter()
            .enumerate()
            .map(|(i, t)| {
                let tx = tx.clone();
                let wait = t.io_wait_nanos;
                thread::spawn(move || {
                    blocking_wait(wait);
                    let _ = tx.send(i);
                })
            })
            .collect();
        for h in handles {
            let _ = h.join();
        }
        drop(tx);
    });
    RunResult {
        total_checksum: sum.load(Ordering::Relaxed),
        completed_count: completed.load(Ordering::Relaxed),
    }
}

pub mod fixtures {
    use super::{cpu_checksum, TaskSpec};

    pub const TASK_COUNT: u32 = 200;

    #[derive(Clone, Copy)]
    pub struct Dataset {
        pub io_wait_nanos: u64,
        pub cpu_iterations: u32,
        pub synchronized_wrap: bool,
    }

    pub const SIMULATED_SOCKET_WAIT: Dataset = Dataset {
        io_wait_nanos: 2_000_000,
        cpu_iterations: 100,
        synchronized_wrap: false,
    };
    pub const SHORT_CPU_STAGE: Dataset = Dataset {
        io_wait_nanos: 0,
        cpu_iterations: 2_000,
        synchronized_wrap: false,
    };
    pub const LONG_CPU_STAGE: Dataset = Dataset {
        io_wait_nanos: 0,
        cpu_iterations: 200_000,
        synchronized_wrap: false,
    };
    pub const LOCK_NATIVE_PINNING_CASE: Dataset = Dataset {
        io_wait_nanos: 1_000_000,
        cpu_iterations: 500,
        synchronized_wrap: true,
    };

    pub fn tasks_for(dataset: Dataset) -> Vec<TaskSpec> {
        (0..TASK_COUNT)
            .map(|id| TaskSpec {
                id,
                io_wait_nanos: dataset.io_wait_nanos,
                cpu_iterations: dataset.cpu_iterations,
                synchronized_wrap: dataset.synchronized_wrap,
            })
            .collect()
    }

    pub fn expected_checksum(dataset: Dataset) -> u64 {
        (0..TASK_COUNT).fold(0u64, |acc, id| {
            acc.wrapping_add(cpu_checksum(id, dataset.cpu_iterations))
        })
    }
}

#[cfg(test)]
mod tests {
    use super::fixtures::*;
    use super::*;

    fn assert_variant(run: impl Fn(&[TaskSpec]) -> RunResult, dataset: Dataset) {
        let tasks = tasks_for(dataset);
        let result = run(&tasks);
        assert_eq!(result.total_checksum, expected_checksum(dataset));
        assert_eq!(result.completed_count, TASK_COUNT as usize);
    }

    #[test]
    fn thread_per_request_matches_short_cpu_stage() {
        assert_variant(thread_per_request, SHORT_CPU_STAGE);
    }

    #[test]
    fn high_fanout_matches_socket_wait() {
        assert_variant(high_fanout_os_threads, SIMULATED_SOCKET_WAIT);
    }

    #[test]
    fn fixed_event_loop_matches_socket_wait() {
        assert_variant(fixed_event_loop, SIMULATED_SOCKET_WAIT);
    }

    #[test]
    fn fixed_event_loop_matches_long_cpu_stage() {
        assert_variant(fixed_event_loop, LONG_CPU_STAGE);
    }

    #[test]
    fn cpu_bound_pool_matches_short_cpu_stage() {
        assert_variant(cpu_bound_pool, SHORT_CPU_STAGE);
    }

    #[test]
    fn cpu_bound_pool_matches_lock_native_pinning_case() {
        assert_variant(cpu_bound_pool, LOCK_NATIVE_PINNING_CASE);
    }

    #[test]
    fn mixed_matches_socket_wait() {
        assert_variant(mixed, SIMULATED_SOCKET_WAIT);
    }

    #[test]
    fn checksum_is_pure_and_deterministic() {
        assert_eq!(cpu_checksum(42, 1000), cpu_checksum(42, 1000));
    }
}
