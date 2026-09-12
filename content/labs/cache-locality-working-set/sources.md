# Cache locality and working-set size — sources

- Ulrich Drepper, *"What Every Programmer Should Know About Memory"*
  (2007) — cache hierarchy, prefetching and memory-level parallelism, the
  primary reference for this lab's mechanism section:
  https://people.freebsd.org/~lstewart/articles/cpumemory.pdf
- Igor Ostrovsky, *"Gallery of Processor Cache Effects"* — the canonical
  small-benchmark demonstrations of cache-capacity staircases and stride
  effects: https://igoro.com/archive/gallery-of-processor-cache-effects/
- Sattolo, *"Random Sampling of a Permutation"* (1986) — the single-
  cycle permutation algorithm this lab and content/labs/cache-hierarchy
  both use for the pointer-chase variant.
- Ivan Pizhenko / classic loop-blocking literature — matrix traversal
  tiling for cache locality, the technique behind this lab's `blocked`
  variant (matrix-multiply blocking is the most widely taught instance of
  the same idea).
- `perf-stat(1)` — cache-references/cache-misses/LLC counter definitions:
  https://man7.org/linux/man-pages/man1/perf-stat.1.html
- Linux kernel documentation, `Documentation/admin-guide/cputopology.rst`
  and `/sys/devices/system/cpu/cpu0/cache` — the sysfs interface this
  lab's `CacheTopology`/topology detection reads.
- Brendan Gregg, *Systems Performance* (2nd ed.), ch. 6–7 — memory and
  CPU cache measurement methodology, working-set sizing.
- Repository methodology: `docs/measurement-environments.md`,
  `docs/linux-evidence-runner.md`, `docs/benchmark-correctness-fixtures.md`.
  Reference implementation: `content/labs/cache-hierarchy` (the topology-
  detection and pointer-chase conventions this lab reuses).
