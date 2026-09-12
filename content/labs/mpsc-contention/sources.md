# MPSC queues and producer contention — sources

<ul class="sources">
  <li><cite>Bounded MPMC queue</cite> — Dmitry Vyukov, 1024cores.net (the multi-producer bounded ring this lab's shared-MPSC and batched-claim variants are built on) — <a href="https://www.1024cores.net/home/lock-free-algorithms/queues/bounded-mpmc-queue" rel="noopener">1024cores.net</a></li>
  <li><cite>The Art of Multiprocessor Programming</cite> — Herlihy &amp; Shavit, Morgan Kaufmann, 2020 (queue linearizability, contention).</li>
  <li><cite>Java Concurrency in Practice</cite> — Goetz et al., Addison-Wesley, 2006.</li>
  <li><cite>java.util.concurrent.ConcurrentLinkedQueue — Java SE documentation</cite> — <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ConcurrentLinkedQueue.html" rel="noopener">docs.oracle.com</a></li>
  <li><cite>std::sync::mpsc — Rust standard library documentation</cite> — <a href="https://doc.rust-lang.org/std/sync/mpsc/" rel="noopener">doc.rust-lang.org/std/sync/mpsc</a></li>
  <li><cite>Rust Atomics and Locks</cite> — Mara Bos, O'Reilly, 2023 (`fetch_add`, CAS loops, channel implementation).</li>
  <li>This site's <a href="/lab/spsc-ring-buffer/">SPSC Ring Buffer</a> lab — the per-producer fan-in variant's underlying mechanism.</li>
  <li>This site's <a href="/lab/memory-ordering-atomics/">Memory Ordering: VarHandles and Rust Atomics</a> lab — the ordering vocabulary this lab's CAS/fetch_add claim points build on.</li>
  <li><cite>JMH: Java Microbenchmark Harness</cite> — <a href="https://openjdk.org/projects/code-tools/jmh/" rel="noopener">openjdk.org/projects/code-tools/jmh</a></li>
  <li><cite>Criterion.rs User Guide</cite> — <a href="https://bheisler.github.io/criterion.rs/book/" rel="noopener">bheisler.github.io/criterion.rs</a></li>
</ul>
