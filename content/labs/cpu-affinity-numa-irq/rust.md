# CPU Affinity, NUMA and IRQ Placement — Rust

## The same `libc::sched_setaffinity`, gated by `cfg(target_os)`

```rust
#[cfg(target_os = "linux")]
mod affinity {
    pub fn try_pin_current_thread(cpu: usize) -> bool {
        unsafe {
            let mut set: libc::cpu_set_t = std::mem::zeroed();
            libc::CPU_SET(cpu, &mut set);
            let rc = libc::sched_setaffinity(0, std::mem::size_of::<libc::cpu_set_t>(), &set);
            if rc != 0 { return false; }
        }
        std::thread::yield_now();
        current_cpu() == Some(cpu)
    }
}

#[cfg(not(target_os = "linux"))]
mod affinity {
    pub fn try_pin_current_thread(_cpu: usize) -> bool { false }
}
```

This is the same mechanism as
`content/labs/thread-per-core/code/rust/src/bin/tpc_evidence.rs`'s
`affinity::pin_current_thread`, using the repository's existing `libc`
dependency (already used by several other labs — no new dependency
added), but returning `bool` instead of asserting: a correctness test
on this repository's macOS host must observe "not pinned" as a normal,
expected outcome, not a failure.

## `unsafe` is isolated to one platform-gated module

The only `unsafe` block in this crate is the two FFI calls inside the
`#[cfg(target_os = "linux")]` `affinity` module — every checksum function,
the topology-parsing code (`parse_id_list`, `read_smt_sibling`,
`read_numa`), and `run_placed` itself are entirely safe Rust. This matches
the lab's own teaching point: placement is a thin, isolated layer around
placement-independent computation, not something that needs to leak
`unsafe` into the workload itself.

## Real topology from `/sys`, parsed defensively

```rust
fn read_numa(isolated: usize) -> (Option<usize>, Option<usize>) {
    let base = Path::new("/sys/devices/system/node");
    let Ok(entries) = fs::read_dir(base) else { return (None, None) };
    // ... reads each nodeN/cpulist, using `?`-free early returns so any
    // missing/malformed file degrades to None rather than panicking
}
```

Every `/sys` read uses `.ok()`/`let ... else` to degrade to `None` on any
I/O error or missing file — a host with fewer NUMA nodes than this lab's
variant matrix expects (including this repository's single-node macOS
development host) never crashes the correctness suite; it simply reports
that placement target as unavailable.

## Criterion benchmark

`benches/cpu_affinity_numa_irq.rs` runs each of the five placement targets
against the dataset its name suggests, on the pinned release profile.

The runnable Cargo/Criterion project (with correctness tests in
`src/lib.rs`) is at
<a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/cpu-affinity-numa-irq/code/rust" rel="noopener"><code>content/labs/cpu-affinity-numa-irq/code/rust/</code></a>
in this site's repository.
