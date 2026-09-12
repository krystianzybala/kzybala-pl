# Deterministic Low-Latency Pipeline Capstone — sources

<ul class="sources">
  <li><cite>LMAX Disruptor — High Performance Alternative to Bounded Queues</cite> — Thompson, Fox, Barker, LMAX Technical Paper — <a href="https://lmax-exchange.github.io/disruptor/files/Disruptor-1.0.pdf" rel="noopener">lmax-exchange.github.io/disruptor</a> (the reference architecture this capstone's shape is modeled on: fixed events, single-writer ownership, bounded rings).</li>
  <li><cite>Release It! Design and Deploy Production-Ready Software</cite> — Michael T. Nygard, Pragmatic Bookshelf, 2018 (bulkheads and isolation, revisited from the [Backpressure](/lab/backpressure-bounded-pipelines/) lab's sources).</li>
  <li><cite>Mechanical Sympathy — Martin Thompson's blog</cite> — <a href="https://mechanical-sympathy.blogspot.com/" rel="noopener">mechanical-sympathy.blogspot.com</a> (background on composing low-level mechanisms into a coherent low-latency system).</li>
  <li><cite>java.nio.ByteBuffer / java.lang.foreign.MemorySegment — Java SE documentation</cite> — <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/foreign/MemorySegment.html" rel="noopener">docs.oracle.com</a></li>
  <li><cite>The Rust Standard Library — `std::collections::VecDeque`</cite> — <a href="https://doc.rust-lang.org/std/collections/struct.VecDeque.html" rel="noopener">doc.rust-lang.org/std/collections/VecDeque</a></li>
  <li><cite>JMH: Java Microbenchmark Harness</cite> — <a href="https://openjdk.org/projects/code-tools/jmh/" rel="noopener">openjdk.org/projects/code-tools/jmh</a></li>
  <li><cite>Criterion.rs User Guide</cite> — <a href="https://bheisler.github.io/criterion.rs/book/" rel="noopener">bheisler.github.io/criterion.rs</a></li>
</ul>
