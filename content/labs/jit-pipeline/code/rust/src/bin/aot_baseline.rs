//! Ahead-of-time (AOT) baseline harness for the JIT Pipeline lab —
//! steady-state passes of the shared pricing kernel in a natively-compiled
//! binary. A SEPARATE scenario by policy: there is no warm-up trajectory
//! to discard here, and this number is never merged into or compared
//! against JVM warm-up phases — only against the JVM's own steady state,
//! and even then as a labeled design-trade-off illustration, never a
//! winner claim. One pinned worker, fixture-verified correctness before
//! and during timing.

use jit_pipeline_lab::{amounts, total, Pricer};
use std::time::{Duration, Instant};

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
        assert_eq!(current_cpu(), cpu as i32, "worker not on its pinned CPU");
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

fn main() {
    let mut cpus: Vec<usize> = vec![];
    let mut seconds: u64 = 5;
    let mut warmup_seconds: u64 = 2;
    let argv: Vec<String> = std::env::args().collect();
    let mut i = 1;
    while i + 1 < argv.len() {
        match argv[i].as_str() {
            "--cpus" => {
                cpus = argv[i + 1]
                    .split(',')
                    .map(|c| c.parse().expect("--cpus"))
                    .collect()
            }
            "--seconds" => seconds = argv[i + 1].parse().expect("--seconds"),
            "--warmup-seconds" => warmup_seconds = argv[i + 1].parse().expect("--warmup-seconds"),
            other => panic!("unknown option: {other}"),
        }
        i += 2;
    }
    let pinned = if let Some(cpu) = cpus.first().copied() {
        affinity::pin_current_thread(cpu);
        true
    } else {
        false
    };

    let inputs = amounts();
    const EXPECTED: u64 = 50215100; // shared fixture: basic total

    // "warm-up" here only stabilizes frequency/caches — there is no
    // compilation tier to wait for; that asymmetry IS the point.
    let warm_until = Instant::now() + Duration::from_secs(warmup_seconds);
    let mut checksum: u64 = 0;
    while Instant::now() < warm_until {
        checksum = total(Pricer::Basic, &inputs);
        assert_eq!(checksum, EXPECTED);
    }

    let start = Instant::now();
    let window = Duration::from_secs(seconds);
    let mut passes: u64 = 0;
    while start.elapsed() < window {
        checksum = total(Pricer::Basic, &inputs);
        if checksum != EXPECTED {
            eprintln!("kernel total wrong — run is invalid");
            std::process::exit(1);
        }
        passes += 1;
    }
    let elapsed = start.elapsed();

    println!(
        "{{\n  \"harness\": \"aot_baseline (separate scenario — never merged with JVM warm-up)\",\n  \"passes\": {},\n  \"elapsedNs\": {},\n  \"nsPerPass\": {:.1},\n  \"inputs\": {},\n  \"fixtureChecksum\": {},\n  \"pinned\": {},\n  \"observedCpuAtEnd\": {}\n}}",
        passes, elapsed.as_nanos(),
        elapsed.as_nanos() as f64 / passes as f64,
        inputs.len(), checksum, pinned, affinity::current_cpu()
    );
}
