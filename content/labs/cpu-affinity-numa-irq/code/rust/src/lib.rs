//! CPU affinity/NUMA/IRQ placement lab: pure, deterministic checksum workloads (independent of
//! thread placement) plus a real, best-effort `sched_setaffinity` pin on Linux via the `libc`
//! crate — the same mechanism `content/labs/thread-per-core/code/rust/src/bin/tpc_evidence.rs`
//! uses, but returning `bool` instead of asserting, since a correctness test on a non-Linux host
//! must degrade to "not pinned" rather than fail.

use std::fs;
use std::path::Path;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PlacementTarget {
    UnpinnedBaseline,
    PinnedIsolatedCore,
    SmtSiblings,
    SameNumaNode,
    RemoteNumaNode,
}

#[derive(Clone, Copy, Debug, Default)]
pub struct Topology {
    pub supported: bool,
    pub isolated_cpu: Option<usize>,
    pub smt_sibling_cpu: Option<usize>,
    pub same_numa_cpu: Option<usize>,
    pub remote_numa_cpu: Option<usize>,
}

impl PlacementTarget {
    pub fn resolve_cpu(&self, topology: &Topology) -> Option<usize> {
        if !topology.supported {
            return None;
        }
        match self {
            PlacementTarget::UnpinnedBaseline => None,
            PlacementTarget::PinnedIsolatedCore => topology.isolated_cpu,
            PlacementTarget::SmtSiblings => topology.smt_sibling_cpu,
            PlacementTarget::SameNumaNode => topology.same_numa_cpu,
            PlacementTarget::RemoteNumaNode => topology.remote_numa_cpu,
        }
    }
}

#[derive(Debug)]
pub struct PlacementResult {
    pub target: PlacementTarget,
    pub pinned: bool,
    pub cpu: Option<usize>,
    pub checksum: u64,
}

pub fn spsc_handoff_checksum(n: u64) -> u64 {
    let mut x: u64 = 0x9E3779B97F4A7C15;
    for i in 0..n {
        x ^= x.wrapping_add(i) << 13;
        x ^= x >> 7;
        x ^= x << 17;
    }
    x
}

pub fn memory_scan_checksum(size: usize, stride: usize) -> u64 {
    let mut data = vec![0u64; size];
    for (i, slot) in data.iter_mut().enumerate() {
        *slot = ((i as u64).wrapping_mul(2654435761)) & 0xFFFF_FFFF;
    }
    let mut sum: u64 = 0;
    let mut i = 0;
    while i < size {
        sum = sum.wrapping_add(data[i]);
        i += stride;
    }
    sum
}

pub fn udp_ingest_checksum(packet_count: u64, packet_size: u64) -> u64 {
    let mut sum: u64 = 0;
    for p in 0..packet_count {
        let packet_seed = p.wrapping_mul(0x2545_F491_4F6C_DD1D).wrapping_add(1);
        for b in 0..packet_size {
            let value = ((packet_seed >> (b % 56)) ^ (p + b)) as u8;
            sum = sum.wrapping_add(value as u64);
        }
    }
    sum
}

pub fn run_placed(
    target: PlacementTarget,
    topology: &Topology,
    workload: impl FnOnce() -> u64 + Send + 'static,
) -> PlacementResult {
    let cpu = target.resolve_cpu(topology);
    let handle = std::thread::spawn(move || {
        let pinned = match cpu {
            Some(c) => affinity::try_pin_current_thread(c),
            None => false,
        };
        (pinned, workload())
    });
    let (pinned, checksum) = handle.join().expect("worker thread panicked");
    PlacementResult {
        target,
        pinned,
        cpu,
        checksum,
    }
}

pub fn detect_topology() -> Topology {
    if !affinity::is_supported() {
        return Topology::default();
    }
    let cpu_count = std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(1);
    let isolated = if cpu_count > 2 { 2 } else { 0 };
    let smt_sibling = read_smt_sibling(isolated);
    let (same_numa, remote_numa) = read_numa(isolated);
    Topology {
        supported: true,
        isolated_cpu: Some(isolated),
        smt_sibling_cpu: smt_sibling,
        same_numa_cpu: same_numa,
        remote_numa_cpu: remote_numa,
    }
}

fn parse_id_list(spec: &str) -> Vec<usize> {
    let mut ids = Vec::new();
    for part in spec.trim().split(',') {
        if part.is_empty() {
            continue;
        }
        if let Some((lo, hi)) = part.split_once('-') {
            if let (Ok(lo), Ok(hi)) = (lo.trim().parse::<usize>(), hi.trim().parse::<usize>()) {
                ids.extend(lo..=hi);
            }
        } else if let Ok(id) = part.trim().parse::<usize>() {
            ids.push(id);
        }
    }
    ids
}

fn read_smt_sibling(cpu: usize) -> Option<usize> {
    let path = format!("/sys/devices/system/cpu/cpu{cpu}/topology/thread_siblings_list");
    let content = fs::read_to_string(path).ok()?;
    parse_id_list(&content).into_iter().find(|&id| id != cpu)
}

fn read_numa(isolated: usize) -> (Option<usize>, Option<usize>) {
    let base = Path::new("/sys/devices/system/node");
    let Ok(entries) = fs::read_dir(base) else {
        return (None, None);
    };
    let mut same = None;
    let mut remote = None;
    for entry in entries.flatten() {
        let name = entry.file_name();
        let name = name.to_string_lossy();
        if !name.starts_with("node") || !name[4..].chars().all(|c| c.is_ascii_digit()) {
            continue;
        }
        let cpulist_path = entry.path().join("cpulist");
        let Ok(content) = fs::read_to_string(cpulist_path) else {
            continue;
        };
        let ids = parse_id_list(&content);
        if ids.contains(&isolated) {
            same = ids.into_iter().find(|&id| id != isolated);
        } else if !ids.is_empty() && remote.is_none() {
            remote = Some(ids[0]);
        }
    }
    (same, remote)
}

#[cfg(target_os = "linux")]
mod affinity {
    pub fn is_supported() -> bool {
        true
    }

    /// Best-effort pin: returns true only if verified via sched_getcpu(). Never panics.
    pub fn try_pin_current_thread(cpu: usize) -> bool {
        unsafe {
            let mut set: libc::cpu_set_t = std::mem::zeroed();
            libc::CPU_SET(cpu, &mut set);
            let rc = libc::sched_setaffinity(0, std::mem::size_of::<libc::cpu_set_t>(), &set);
            if rc != 0 {
                return false;
            }
        }
        std::thread::yield_now();
        current_cpu() == Some(cpu)
    }

    pub fn current_cpu() -> Option<usize> {
        let cpu = unsafe { libc::sched_getcpu() };
        if cpu < 0 {
            None
        } else {
            Some(cpu as usize)
        }
    }
}

#[cfg(not(target_os = "linux"))]
mod affinity {
    pub fn is_supported() -> bool {
        false
    }

    pub fn try_pin_current_thread(_cpu: usize) -> bool {
        false
    }
}

pub mod fixtures {
    pub const SPSC_HANDOFF_COUNT: u64 = 5000;
    pub const MEMORY_SCAN_SIZE: usize = 200_000;
    pub const MEMORY_SCAN_STRIDE: usize = 1;
    pub const UDP_PACKET_COUNT: u64 = 2000;
    pub const UDP_PACKET_SIZE: u64 = 128;

    pub fn expected_spsc_handoff_checksum() -> u64 {
        super::spsc_handoff_checksum(SPSC_HANDOFF_COUNT)
    }

    pub fn expected_memory_scan_checksum() -> u64 {
        super::memory_scan_checksum(MEMORY_SCAN_SIZE, MEMORY_SCAN_STRIDE)
    }

    pub fn expected_udp_ingest_checksum() -> u64 {
        super::udp_ingest_checksum(UDP_PACKET_COUNT, UDP_PACKET_SIZE)
    }
}

#[cfg(test)]
mod tests {
    use super::fixtures::*;
    use super::*;

    const ALL_TARGETS: [PlacementTarget; 5] = [
        PlacementTarget::UnpinnedBaseline,
        PlacementTarget::PinnedIsolatedCore,
        PlacementTarget::SmtSiblings,
        PlacementTarget::SameNumaNode,
        PlacementTarget::RemoteNumaNode,
    ];

    #[test]
    fn checksums_are_pure_and_deterministic() {
        assert_eq!(spsc_handoff_checksum(5000), spsc_handoff_checksum(5000));
        assert_eq!(
            memory_scan_checksum(200_000, 1),
            memory_scan_checksum(200_000, 1)
        );
        assert_eq!(
            udp_ingest_checksum(2000, 128),
            udp_ingest_checksum(2000, 128)
        );
    }

    #[test]
    fn spsc_handoff_checksum_independent_of_placement() {
        let topology = detect_topology();
        for &target in &ALL_TARGETS {
            let result = run_placed(target, &topology, || {
                spsc_handoff_checksum(SPSC_HANDOFF_COUNT)
            });
            assert_eq!(
                result.checksum,
                expected_spsc_handoff_checksum(),
                "{:?}",
                target
            );
        }
    }

    #[test]
    fn memory_scan_checksum_independent_of_placement() {
        let topology = detect_topology();
        for &target in &ALL_TARGETS {
            let result = run_placed(target, &topology, || {
                memory_scan_checksum(MEMORY_SCAN_SIZE, MEMORY_SCAN_STRIDE)
            });
            assert_eq!(
                result.checksum,
                expected_memory_scan_checksum(),
                "{:?}",
                target
            );
        }
    }

    #[test]
    fn udp_ingest_checksum_independent_of_placement() {
        let topology = detect_topology();
        for &target in &ALL_TARGETS {
            let result = run_placed(target, &topology, || {
                udp_ingest_checksum(UDP_PACKET_COUNT, UDP_PACKET_SIZE)
            });
            assert_eq!(
                result.checksum,
                expected_udp_ingest_checksum(),
                "{:?}",
                target
            );
        }
    }

    #[test]
    fn unpinned_baseline_never_attempts_to_pin() {
        let topology = detect_topology();
        let result = run_placed(PlacementTarget::UnpinnedBaseline, &topology, || {
            spsc_handoff_checksum(100)
        });
        assert!(!result.pinned);
        assert!(result.cpu.is_none());
    }
}
