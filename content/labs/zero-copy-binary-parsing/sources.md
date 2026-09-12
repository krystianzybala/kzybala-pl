# Zero-copy binary parsing and views — sources

- content/labs/ffm-memory-segments — this lab's direct prerequisite;
  the `MemorySegment`/`Arena` mechanics this lab's Java views build on
  directly.
- content/labs/struct-layout-alignment — this lab's direct prerequisite;
  the unaligned-access discovery (`*_UNALIGNED` `ValueLayout` variants)
  this lab's own wire-format encoder needed, empirically, not
  theoretically (java.md).
- The Java `java.lang.foreign` package documentation — `MemorySegment`,
  `ValueLayout.withOrder`, the `*_UNALIGNED` layout variants:
  https://docs.oracle.com/en/java/javase/22/docs/api/java.base/java/lang/foreign/package-summary.html
- `java.nio.charset.CharsetDecoder` — the real UTF-8 validity check this
  lab's `ZeroCopyFixtures.isValidUtf8` uses directly, never approximated:
  https://docs.oracle.com/en/java/javase/22/docs/api/java.base/java/nio/charset/CharsetDecoder.html
- The Rust standard library, `std::str::from_utf8` — the real UTF-8
  validity check this lab's Rust `validated_zero_copy_view` uses
  directly: https://doc.rust-lang.org/std/str/fn.from_utf8.html
- The Rust Reference, *Slices* — the borrowed `&[u8]`/`&mut [u8]`
  mechanics this lab's Rust track relies on for zero-copy access and
  compile-time lifetime safety:
  https://doc.rust-lang.org/reference/types/slice.html
- The `nom` and `bytemuck` crates (repository-supported, referenced by
  design, not required by this lab's own implementation) — established
  Rust ecosystem approaches to zero-copy/borrowed parsing worth knowing
  about even though this lab implements its decoders by hand:
  https://docs.rs/nom/, https://docs.rs/bytemuck/
- RFC 3629, *UTF-8, a transformation format of ISO 10646* — the encoding
  this lab's `utf8Field` dataset and validation logic implement against:
  https://www.rfc-editor.org/rfc/rfc3629
- `perf-stat(1)` — branch-misses/cache-misses counter definitions
  relevant to the access-pattern differences between copying and
  zero-copy decoders:
  https://man7.org/linux/man-pages/man1/perf-stat.1.html
- Repository methodology: `docs/measurement-environments.md`,
  `docs/linux-evidence-runner.md`, `docs/benchmark-correctness-fixtures.md`.
