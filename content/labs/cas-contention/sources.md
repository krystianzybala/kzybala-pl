# CAS contention and backoff — sources

<ul class="sources">
  <li><cite>The Art of Multiprocessor Programming</cite> — Herlihy &amp; Shavit, Morgan Kaufmann, 2020 (compare-and-swap, backoff, contention).</li>
  <li><cite>Java Concurrency in Practice</cite> — Goetz et al., Addison-Wesley, 2006.</li>
  <li><cite>java.util.concurrent.atomic.AtomicLong — Java SE documentation</cite> — <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/atomic/AtomicLong.html" rel="noopener">docs.oracle.com</a></li>
  <li><cite>The Rust Reference — `compare_exchange`/`fetch_update` (`std::sync::atomic`)</cite> — <a href="https://doc.rust-lang.org/std/sync/atomic/struct.AtomicU64.html" rel="noopener">doc.rust-lang.org/std/sync/atomic</a></li>
  <li><cite>Exponential Backoff and Jitter</cite> — Marc Brooker, AWS Architecture Blog — <a href="https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/" rel="noopener">aws.amazon.com/blogs/architecture</a></li>
  <li><cite>ABA problem</cite> — background reading on the hazard this lab describes conceptually, e.g. Herlihy &amp; Shavit above, ch. 10.</li>
  <li><cite>JMH: Java Microbenchmark Harness</cite> — <a href="https://openjdk.org/projects/code-tools/jmh/" rel="noopener">openjdk.org/projects/code-tools/jmh</a></li>
  <li><cite>Criterion.rs User Guide</cite> — <a href="https://bheisler.github.io/criterion.rs/book/" rel="noopener">bheisler.github.io/criterion.rs</a></li>
</ul>
