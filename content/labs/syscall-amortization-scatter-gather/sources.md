# Syscall amortization and scatter/gather I/O — sources

<ul class="sources">
  <li><cite>java.nio.channels.GatheringByteChannel — Java SE documentation</cite> — <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/GatheringByteChannel.html" rel="noopener">docs.oracle.com</a></li>
  <li><cite>std::io::Write::write_vectored — Rust standard library documentation</cite> — <a href="https://doc.rust-lang.org/std/io/trait.Write.html#method.write_vectored" rel="noopener">doc.rust-lang.org</a></li>
  <li><cite>writev(2) — Linux manual page</cite> — <a href="https://man7.org/linux/man-pages/man2/writev.2.html" rel="noopener">man7.org/linux/man-pages/man2/writev.2.html</a></li>
  <li><cite>strace(1) — Linux manual page</cite> — <a href="https://man7.org/linux/man-pages/man1/strace.1.html" rel="noopener">man7.org/linux/man-pages/man1/strace.1.html</a></li>
  <li><cite>TCP_NODELAY and the Nagle algorithm — RFC 896</cite> (background on the "letting Nagle/coalescing differ silently" trap) — <a href="https://www.rfc-editor.org/rfc/rfc896" rel="noopener">rfc-editor.org/rfc/rfc896</a></li>
  <li><cite>JMH: Java Microbenchmark Harness</cite> — <a href="https://openjdk.org/projects/code-tools/jmh/" rel="noopener">openjdk.org/projects/code-tools/jmh</a></li>
  <li><cite>Criterion.rs User Guide</cite> — <a href="https://bheisler.github.io/criterion.rs/book/" rel="noopener">bheisler.github.io/criterion.rs</a></li>
</ul>
