# Logging, Metrics and Profiling Overhead — sources

<ul class="sources">
  <li><cite>SLF4J FAQ — "Parameterized logging"</cite> — <a href="https://www.slf4j.org/faq.html#logging_performance" rel="noopener">slf4j.org/faq.html</a> (the guard-vs-eager-formatting trade-off, in the ecosystem's own words).</li>
  <li><cite>java.util.logging.Logger — isLoggable and level checks</cite> — <a href="https://docs.oracle.com/en/java/javase/21/docs/api/java.logging/java/util/logging/Logger.html" rel="noopener">docs.oracle.com</a></li>
  <li><cite>The Rust `tracing` crate documentation — "Performance"</cite> — <a href="https://docs.rs/tracing/latest/tracing/#performance" rel="noopener">docs.rs/tracing</a> (background on why the ecosystem's own instrumentation crate cares about this exact guard-placement question, even though this lab does not depend on it).</li>
  <li><cite>std::backtrace — Rust Standard Library documentation</cite> — <a href="https://doc.rust-lang.org/std/backtrace/index.html" rel="noopener">doc.rust-lang.org/std/backtrace</a></li>
  <li><cite>Prometheus documentation — "Cardinality"</cite> — <a href="https://prometheus.io/docs/practices/naming/#labels" rel="noopener">prometheus.io/docs/practices</a></li>
  <li><cite>The Tail at Scale</cite> — Dean &amp; Barroso, Communications of the ACM, 2013 (sampling and tail-latency measurement, revisited from the [Backpressure](/lab/backpressure-bounded-pipelines/) lab's sources).</li>
  <li><cite>JMH: Java Microbenchmark Harness</cite> — <a href="https://openjdk.org/projects/code-tools/jmh/" rel="noopener">openjdk.org/projects/code-tools/jmh</a></li>
  <li><cite>Criterion.rs User Guide</cite> — <a href="https://bheisler.github.io/criterion.rs/book/" rel="noopener">bheisler.github.io/criterion.rs</a></li>
</ul>
