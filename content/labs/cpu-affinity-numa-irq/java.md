# CPU Affinity, NUMA and IRQ Placement — Java

## Real topology, probed, never hardcoded

```java
public record Topology(boolean supported, int cpuCount,
        OptionalInt isolatedCpu, OptionalInt smtSiblingCpu,
        OptionalInt sameNumaCpu, OptionalInt remoteNumaCpu) {

    public static Topology detect() {
        if (!CpuAffinity.isSupported()) {
            return new Topology(false, Runtime.getRuntime().availableProcessors(),
                    OptionalInt.empty(), OptionalInt.empty(), OptionalInt.empty(), OptionalInt.empty());
        }
        // ... reads /sys/devices/system/cpu/cpuN/topology/thread_siblings_list
        // ... and /sys/devices/system/node/nodeN/cpulist
    }
}
```

Every field is an `OptionalInt`: on this repository's macOS development
host, `CpuAffinity.isSupported()` is `false` (no Linux `sched_setaffinity`)
and every field comes back empty — never a fabricated CPU id.

## Best-effort pinning, never fabricated success

```java
public static boolean tryPinCurrentThread(int cpu) {
    if (!SUPPORTED) return false;
    // sched_setaffinity via the FFM API, then verify with sched_getcpu()
}
```

Unlike the throwing `pinCurrentThread` used for real publication evidence
in `content/labs/thread-per-core/`, this lab's `tryPinCurrentThread`
returns `false` on any failure — a correctness test on an unsupported
host must degrade gracefully, not fail the build.

## Placement is orthogonal to correctness

```java
public static PlacementResult run(PlacementTarget target, Topology topology, LongSupplier workload) {
    OptionalInt targetCpu = target.resolveCpu(topology);
    Thread worker = new Thread(() -> {
        if (targetCpu.isPresent()) pinned[0] = CpuAffinity.tryPinCurrentThread(targetCpu.getAsInt());
        checksum[0] = workload.getAsLong();
    });
    worker.start();
    worker.join();
    return new PlacementResult(target, pinned[0], targetCpu, checksum[0]);
}
```

`workload` is one of three pure checksum functions (`spscHandoffChecksum`,
`memoryScanChecksum`, `udpIngestChecksum`) — none of them read `pinned` or
`targetCpu`, so the checksum this method returns is provably independent
of whether pinning happened, let alone succeeded. This is what the
correctness test suite asserts for every `(target, dataset)` pair.

## JMH benchmark

`AffinityBenchmark` (`@Fork(1)`, 3 warmup + 5 measurement iterations of 1s
each) runs each placement target against the dataset its variant name
suggests. On a host without real topology, every non-baseline target
degrades to the same unpinned execution as the baseline — the benchmark
still runs and produces a number, but that number is not meaningful
placement evidence until the native-Linux run supplies a real topology
(see benchmark.md).

The runnable Maven/JMH project (with correctness tests under
`src/test/java/`) is at
<a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/cpu-affinity-numa-irq/code/java" rel="noopener"><code>content/labs/cpu-affinity-numa-irq/code/java/</code></a>
in this site's repository.
