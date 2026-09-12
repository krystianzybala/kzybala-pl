# Memory Ordering: VarHandles and Rust Atomics — sources

<ul class="sources">
  <li><cite>JSR 133 (Java Memory Model) FAQ</cite> — Jeremy Manson &amp; Brian Goetz — <a href="https://www.cs.umd.edu/~pugh/java/memoryModel/jsr-133-faq.html" rel="noopener">cs.umd.edu/~pugh/java/memoryModel</a></li>
  <li><cite>java.lang.invoke.VarHandle — Java SE documentation</cite> (access modes: plain, opaque, acquire/release, volatile) — <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/invoke/VarHandle.html" rel="noopener">docs.oracle.com</a></li>
  <li><cite>JEP 193: Variable Handles</cite> — <a href="https://openjdk.org/jeps/193" rel="noopener">openjdk.org/jeps/193</a></li>
  <li><cite>The Art of Multiprocessor Programming</cite> — Herlihy &amp; Shavit, Morgan Kaufmann, 2020 (linearizability, consensus, memory models).</li>
  <li><cite>Java Concurrency in Practice</cite> — Goetz et al., Addison-Wesley, 2006.</li>
  <li><cite>Rust Atomics and Locks</cite> — Mara Bos, O'Reilly, 2023 (`Ordering`, fences, seqlocks) — <a href="https://marabos.nl/atomics/" rel="noopener">marabos.nl/atomics</a></li>
  <li><cite>The Rust Reference — `std::sync::atomic`</cite> — <a href="https://doc.rust-lang.org/std/sync/atomic/" rel="noopener">doc.rust-lang.org/std/sync/atomic</a></li>
  <li><cite>The Rustonomicon — Working with Unsafe</cite> — <a href="https://doc.rust-lang.org/nomicon/" rel="noopener">doc.rust-lang.org/nomicon</a></li>
  <li><cite>C++ Standard, `std::atomic_thread_fence` and memory_order</cite> — the formal basis (via `std::sync::atomic::fence`/JEP 193) for the release/acquire/seq-cst/fence terminology used across both languages here — <a href="https://en.cppreference.com/w/cpp/atomic/memory_order" rel="noopener">en.cppreference.com/w/cpp/atomic/memory_order</a></li>
  <li><cite>JMH: Java Microbenchmark Harness</cite> — <a href="https://openjdk.org/projects/code-tools/jmh/" rel="noopener">openjdk.org/projects/code-tools/jmh</a></li>
  <li><cite>Criterion.rs User Guide</cite> — <a href="https://bheisler.github.io/criterion.rs/book/" rel="noopener">bheisler.github.io/criterion.rs</a></li>
  <li>This site's <a href="/lab/memory-ordering/">Memory Ordering in Java and Rust</a> lab — the prerequisite this lab's happens-before vocabulary builds on.</li>
</ul>
