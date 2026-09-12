# Arena lifetimes, pools and reuse — sources

- content/labs/escape-analysis-scalar-replacement — this lab's direct
  prerequisite and the mechanism behind its single most important
  finding: a naive object pool can lose to doing nothing at all when the
  pooled object was already scalar-replaced.
- content/labs/ffm-memory-segments — this lab's direct prerequisite;
  `batchArena`'s `Arena.ofConfined()` usage and offset-based access
  technique are reused directly.
- The Java `java.lang.foreign` package documentation — `Arena`,
  `Arena.ofConfined()`:
  https://docs.oracle.com/en/java/javase/22/docs/api/java.base/java/lang/foreign/Arena.html
- `java.util.concurrent.ConcurrentLinkedDeque` and
  `java.util.concurrent.ArrayBlockingQueue` Javadoc — the two pool
  backing structures this lab's `globalPool`/`boundedPool` use, and the
  source of their measured allocation/locking differences:
  https://docs.oracle.com/en/java/javase/22/docs/api/java.base/java/util/concurrent/package-summary.html
- JMH's `SampleTime` mode and percentile reporting — the mechanism this
  lab's `oneBatchLatency` benchmark uses to satisfy the p99 metric
  natively, without a bespoke histogram harness:
  https://github.com/openjdk/jmh
- JMH's `-prof gc` profiler — the allocation-rate evidence
  (`gc.alloc.rate.norm`) this lab's central finding depends on directly.
- The Rust standard library, `std::sync::Mutex` documentation — the
  locking primitive this lab's Rust `UnboundedPool`/`BoundedPool` share,
  and the source of their measured lock-overhead finding:
  https://doc.rust-lang.org/std/sync/struct.Mutex.html
- The Rust Reference, *Drop* — the deterministic, scope-exit reclamation
  this lab's `batch_arena` (`Vec<T>` dropped at batch end) relies on:
  https://doc.rust-lang.org/reference/destructors.html
- Repository methodology: `docs/measurement-environments.md`,
  `docs/linux-evidence-runner.md`, `docs/benchmark-correctness-fixtures.md`.
