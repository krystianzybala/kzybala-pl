# UDP ingest, batching and packet loss — sources

<ul class="sources">
  <li><cite>java.nio.channels.DatagramChannel — Java SE documentation</cite> — <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/DatagramChannel.html" rel="noopener">docs.oracle.com</a></li>
  <li><cite>std::net::UdpSocket — Rust standard library documentation</cite> — <a href="https://doc.rust-lang.org/std/net/struct.UdpSocket.html" rel="noopener">doc.rust-lang.org/std/net/struct.UdpSocket.html</a></li>
  <li><cite>recvmmsg(2) — Linux manual page</cite> (the batched-receive mechanism this lab documents as capability-unavailable) — <a href="https://man7.org/linux/man-pages/man2/recvmmsg.2.html" rel="noopener">man7.org/linux/man-pages/man2/recvmmsg.2.html</a></li>
  <li><cite>udp(7) — Linux manual page</cite> (socket receive buffer and kernel drop counters) — <a href="https://man7.org/linux/man-pages/man7/udp.7.html" rel="noopener">man7.org/linux/man-pages/man7/udp.7.html</a></li>
  <li><cite>ss(8) — Linux manual page</cite> — <a href="https://man7.org/linux/man-pages/man8/ss.8.html" rel="noopener">man7.org/linux/man-pages/man8/ss.8.html</a></li>
  <li><cite>Coordinated Omission (background on the measurement traps this lab's percentile reporting avoids)</cite> — Gil Tene, various talks/writeups — <a href="https://www.azul.com/blog/the-observer-effect-and-coordinated-omission/" rel="noopener">azul.com — the observer effect and coordinated omission</a></li>
  <li><cite>JMH: Java Microbenchmark Harness</cite> — <a href="https://openjdk.org/projects/code-tools/jmh/" rel="noopener">openjdk.org/projects/code-tools/jmh</a></li>
  <li><cite>Criterion.rs User Guide</cite> — <a href="https://bheisler.github.io/criterion.rs/book/" rel="noopener">bheisler.github.io/criterion.rs</a></li>
</ul>
