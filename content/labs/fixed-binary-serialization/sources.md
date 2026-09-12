# Object serialization vs fixed binary layout — sources

<ul class="sources">
  <li><cite>Java Object Serialization Specification</cite> — <a href="https://docs.oracle.com/en/java/javase/21/docs/specs/serialization/index.html" rel="noopener">docs.oracle.com</a></li>
  <li><cite>java.nio.ByteBuffer — Java SE documentation</cite> — <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/ByteBuffer.html" rel="noopener">docs.oracle.com</a></li>
  <li><cite>JEP 454: Foreign Function &amp; Memory API</cite> — <a href="https://openjdk.org/jeps/454" rel="noopener">openjdk.org/jeps/454</a></li>
  <li><cite>The Rust Reference — Type Layout</cite> — <a href="https://doc.rust-lang.org/reference/type-layout.html" rel="noopener">doc.rust-lang.org/reference/type-layout</a></li>
  <li><cite>zerocopy crate documentation</cite> — a maintained crate for byte-layout-driven zero-copy parsing, illustrating the same offset-driven view pattern as this lab's borrowed decode — <a href="https://docs.rs/zerocopy/" rel="noopener">docs.rs/zerocopy</a></li>
  <li><cite>Protocol Buffers Encoding Guide</cite> — a contrasting self-describing/varint-driven wire format, useful background for Exercise 2 — <a href="https://protobuf.dev/programming-guides/encoding/" rel="noopener">protobuf.dev/programming-guides/encoding</a></li>
  <li><cite>JMH: Java Microbenchmark Harness</cite> — <a href="https://openjdk.org/projects/code-tools/jmh/" rel="noopener">openjdk.org/projects/code-tools/jmh</a></li>
  <li><cite>Criterion.rs User Guide</cite> — <a href="https://bheisler.github.io/criterion.rs/book/" rel="noopener">bheisler.github.io/criterion.rs</a></li>
  <li><cite>JDK Flight Recorder (JFR) documentation</cite> — <a href="https://docs.oracle.com/en/java/javase/21/docs/specs/man/jfr.html" rel="noopener">docs.oracle.com/.../jfr</a></li>
</ul>
