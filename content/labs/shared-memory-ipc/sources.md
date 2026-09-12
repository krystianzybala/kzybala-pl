# Shared-memory IPC — sources

<ul class="sources">
  <li><cite>java.nio.channels.FileChannel — Java SE documentation</cite> — <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/FileChannel.html" rel="noopener">docs.oracle.com</a></li>
  <li><cite>java.lang.foreign — Java SE documentation (Foreign Function &amp; Memory API)</cite> — <a href="https://docs.oracle.com/en/java/javase/22/docs/api/java.base/java/lang/foreign/package-summary.html" rel="noopener">docs.oracle.com</a></li>
  <li><cite>memmap2 crate documentation</cite> — <a href="https://docs.rs/memmap2/" rel="noopener">docs.rs/memmap2</a></li>
  <li><cite>shared_memory crate documentation</cite> — a maintained crate using the same raw-pointer-to-AtomicU64 pattern over shared mappings — <a href="https://docs.rs/shared_memory/" rel="noopener">docs.rs/shared_memory</a></li>
  <li><cite>mmap(2) — Linux manual page</cite> (MAP_SHARED semantics) — <a href="https://man7.org/linux/man-pages/man2/mmap.2.html" rel="noopener">man7.org/linux/man-pages/man2/mmap.2.html</a></li>
  <li><cite>shm_overview(7) — Linux manual page</cite> — <a href="https://man7.org/linux/man-pages/man7/shm_overview.7.html" rel="noopener">man7.org/linux/man-pages/man7/shm_overview.7.html</a></li>
  <li><cite>Disruptor: High Performance Alternative to Bounded Queues for Exchanging Data Between Concurrent Threads</cite> — Thompson, Fox, Barker, LMAX Technical Paper (the same cursor protocol this lab applies across processes) — <a href="https://lmax-exchange.github.io/disruptor/files/Disruptor-1.0.pdf" rel="noopener">lmax-exchange.github.io/disruptor</a></li>
  <li><cite>JMH: Java Microbenchmark Harness</cite> — <a href="https://openjdk.org/projects/code-tools/jmh/" rel="noopener">openjdk.org/projects/code-tools/jmh</a></li>
  <li><cite>Criterion.rs User Guide</cite> — <a href="https://bheisler.github.io/criterion.rs/book/" rel="noopener">bheisler.github.io/criterion.rs</a></li>
</ul>
