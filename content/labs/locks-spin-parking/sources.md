# Locks, spin waiting and parking — sources

<ul class="sources">
  <li><cite>The Art of Multiprocessor Programming</cite> — Herlihy &amp; Shavit, Morgan Kaufmann, 2020 (spin locks, queue locks, contention).</li>
  <li><cite>Java Concurrency in Practice</cite> — Goetz et al., Addison-Wesley, 2006 (lock contention, `synchronized` vs. `Lock`).</li>
  <li><cite>java.util.concurrent.locks.LockSupport — Java SE documentation</cite> — <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/locks/LockSupport.html" rel="noopener">docs.oracle.com</a></li>
  <li><cite>java.util.concurrent.locks.ReentrantLock — Java SE documentation</cite> — <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/locks/ReentrantLock.html" rel="noopener">docs.oracle.com</a></li>
  <li><cite>std::thread::park — Rust standard library documentation</cite> — <a href="https://doc.rust-lang.org/std/thread/fn.park.html" rel="noopener">doc.rust-lang.org/std/thread/fn.park</a></li>
  <li><cite>std::sync::Mutex — Rust standard library documentation</cite> — <a href="https://doc.rust-lang.org/std/sync/struct.Mutex.html" rel="noopener">doc.rust-lang.org/std/sync/struct.Mutex</a></li>
  <li><cite>Rust Atomics and Locks</cite> — Mara Bos, O'Reilly, 2023 (spinlocks, parking, futex-based locks).</li>
  <li>This site's <a href="/lab/mpsc-contention/">MPSC Queues and Producer Contention</a> lab — the CAS-loop and failed-attempt-accounting vocabulary this lab reuses.</li>
  <li>This site's <a href="/lab/memory-ordering-atomics/">Memory Ordering: VarHandles and Rust Atomics</a> lab — the ordering vocabulary behind this lab's CAS loop and spin-then-park flag.</li>
  <li><cite>JMH: Java Microbenchmark Harness</cite> — <a href="https://openjdk.org/projects/code-tools/jmh/" rel="noopener">openjdk.org/projects/code-tools/jmh</a></li>
  <li><cite>Criterion.rs User Guide</cite> — <a href="https://bheisler.github.io/criterion.rs/book/" rel="noopener">bheisler.github.io/criterion.rs</a></li>
</ul>
