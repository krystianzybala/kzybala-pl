# Thread-per-core and shared-nothing sharding — sources

<ul class="sources">
  <li><cite>Seastar: shared-nothing, thread-per-core architecture</cite> — <a href="https://seastar.io/shared-nothing/" rel="noopener">seastar.io/shared-nothing</a></li>
  <li><cite>The Art of Multiprocessor Programming</cite> — Herlihy &amp; Shavit, Morgan Kaufmann, 2020 (partitioning, contention, linearizability).</li>
  <li><cite>Java Concurrency in Practice</cite> — Goetz et al., Addison-Wesley, 2006.</li>
  <li><cite>java.util.concurrent.ConcurrentLinkedQueue — Java SE documentation</cite> — <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/ConcurrentLinkedQueue.html" rel="noopener">docs.oracle.com</a></li>
  <li><cite>std::sync::mpsc — Rust standard library documentation</cite> — <a href="https://doc.rust-lang.org/std/sync/mpsc/" rel="noopener">doc.rust-lang.org/std/sync/mpsc</a></li>
  <li>This site's <a href="/lab/thread-per-core/">Thread-per-Core</a> lab — the general ownership-partitioning idea this lab specializes to sharded key-value counters.</li>
  <li>This site's <a href="/lab/mpsc-contention/">MPSC Queues and Producer Contention</a> lab — the routing-queue vocabulary this lab's single-writer variant reuses.</li>
  <li>This site's <a href="/lab/locks-spin-parking/">Locks, Spin Waiting and Parking</a> lab — the mutex-vs-lock-free trade-offs this lab's mutex-shards variant applies per shard.</li>
  <li><cite>JMH: Java Microbenchmark Harness</cite> — <a href="https://openjdk.org/projects/code-tools/jmh/" rel="noopener">openjdk.org/projects/code-tools/jmh</a></li>
  <li><cite>Criterion.rs User Guide</cite> — <a href="https://bheisler.github.io/criterion.rs/book/" rel="noopener">bheisler.github.io/criterion.rs</a></li>
</ul>
