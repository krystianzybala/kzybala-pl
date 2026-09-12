# Java-Rust interop with FFM downcalls and upcalls — theory

## Performance question and hypothesis

**Question:** what is the real cost and safety boundary of calling Rust
from Java and Java callbacks from Rust?

**Hypothesis:** FFM removes JNI ceremony but not ABI, transition, pinning,
lifetime or callback costs; coarse-grained calls can win while chatty FFI
loses.

**What would disprove it:** if a scalar downcall per array element cost
the same as one batched downcall covering the whole array (the "chatty
FFI loses" half would be false — per-call overhead wouldn't actually
matter), or if the batched (copying) and zero-copy downcall variants
performed identically (the copy this lab attributes to the batched
variant would not actually cost anything), or if a Rust-to-Java upcall
cost the same as a Java-to-Rust downcall (the extra direction wouldn't
actually add cost, when in fact crossing into JVM-managed code from
native code carries its own distinct overhead).

## Learning objective

Show that the Foreign Function & Memory API genuinely removes the
boilerplate (`javah`, `.h` headers, `JNIEXPORT`/`JNICALL` and manual
`JNIEnv*` field access) that made JNI painful to write — but does not
remove the underlying physics of crossing between two different runtime
environments: a JVM call still has to transition out of managed code, a
Rust function still expects data at a specific memory address in a
specific layout, and a callback into the JVM still has to cross back
through that same boundary with its own separate cost. This lab measures
each of those costs somewhat separately by holding the actual computation
identical (`x -> x*2.0+1.0`) across six variants that only differ in how
the call is shaped.

## Prerequisites

- The [FFM Memory Segments](/lab/ffm-memory-segments/) lab — this lab's
  `MemorySegment`/`Arena` usage for passing data across the FFI boundary
  is exactly that lab's mechanism, applied to calling external native
  code instead of accessing this process's own memory directly.
- The [Object Serialization vs Fixed Binary Layout](/lab/fixed-binary-serialization/)
  lab — the fixed-record validation dataset here reuses that lab's
  fixed-layout idea for a buffer read directly by Rust.

### Pre-lab diagnostic

A team reports "removing JNI in favor of FFM made our native calls twice
as fast." A colleague is skeptical that the *linkage mechanism itself*
(JNI vs. FFM) is what actually explains a 2x change, rather than
something else that changed at the same time. What is the single most
common confound in a "before/after" native-interop comparison like this?

(Answer at the end of this page.)

## Terminology

| Term | Meaning in this lab |
|---|---|
| Downcall | A call FROM Java INTO native code — Java's `Linker.downcallHandle` builds a `MethodHandle` that, when invoked, transitions out of the JVM and executes the target native function. |
| Upcall | A call FROM native code BACK INTO Java — `Linker.upcallStub` builds a native function pointer that, when the native side calls it, transitions back into the JVM and executes a Java method. |
| ABI (Application Binary Interface) | The low-level contract (calling convention, argument layout, name mangling) two independently-compiled pieces of code must agree on to call each other correctly — `extern "C"` in Rust and FFM's `FunctionDescriptor` in Java are both ways of expressing "use the C ABI" for a specific function signature. |
| Coarse-grained vs. chatty FFI | Coarse-grained: few FFI crossings, each doing substantial work (e.g. one call transforms a whole array). Chatty: many small FFI crossings (e.g. one call per array element) — this lab's hypothesis names this as the trade-off that determines whether FFI helps or hurts. |
| Panic containment | The discipline of never letting a Rust panic unwind across an `extern "C"` boundary (undefined behavior) — catching it and aborting instead, which this lab's Rust code does at every entry point. |
| cdylib | A Rust crate compiled as a C-compatible dynamic library (`.dylib`/`.so`), the artifact Java's `SymbolLookup.libraryLookup` loads. |

## Six variants, one computation

Every variant in this lab's primary dataset performs the identical
transform `x -> x*2.0+1.0` — the only thing that varies is how (or
whether) an FFI boundary is crossed to compute it:

1. **Pure Java baseline** — no FFI at all; the reference point for "what
   would this cost with zero interop overhead."
2. **Pure Rust baseline** — the same computation, entirely in Rust, never
   touching the JVM; measured only in Rust's own Criterion suite (see
   rust.md) — never combined with the Java numbers into one ranking (this
   lab's non-goal: no Java-versus-Rust winner).
3. **Scalar downcall per item** — one Java-to-Rust FFI crossing *per array
   element*: the chatty extreme.
4. **Batched downcall** — one FFI crossing for the *whole* array, but Java
   must copy its heap `double[]` into off-heap memory first (and copy the
   result back), because a Java array is not itself addressable by native
   code.
5. **Zero-copy buffer downcall** — the same one-crossing-per-array shape,
   but Java already holds the data as a `MemorySegment` (never
   materialized as a `double[]` at all), so neither copy happens.
6. **Rust-to-Java upcall** — Rust iterates the array and calls back into a
   Java method once per element: the reverse-direction analogue of the
   scalar downcall, crossing the boundary the other way just as often.

## Why the batched and zero-copy variants call the identical Rust function

Both variants call the exact same `ffm_transform_batch` native function —
the *only* difference is what happens on the Java side before and after
that one call: batched copies a heap array into off-heap memory (and back
out), zero-copy never needed to, because the caller already worked with a
`MemorySegment` from the start. This isolates the copy's cost from the
FFI crossing's cost — see the "silently copying buffers" trap below.

## Panic containment

Every `extern "C"` function in this lab's Rust code wraps its body in
`catch_unwind` and calls `std::process::abort()` if a panic is caught,
rather than letting it propagate across the FFI boundary — unwinding a
Rust panic through code the Rust compiler didn't generate (Java's JVM
runtime) is undefined behavior, not merely "an exception the caller
should handle." This lab's pure functions are written so ordinary
input-shaped problems (a misaligned record buffer) return an error code
instead of ever reaching a `panic!`, so the abort path is reserved for
genuine internal-invariant violations, not routine validation failures.

## The upcall direction has its own cost, not just "the same cost in reverse"

A native function pointer built by `Linker.upcallStub` still has to
locate and re-enter the JVM's own execution machinery on every call —
this is not merely "a downcall running backwards." This lab measures it
directly (`rustToJavaUpcall`) rather than assuming symmetry with the
scalar downcall.

## Visualization 1: ABI boundary diagram (textual fallback)

```
Java (JVM-managed)                    |  Rust (native, C ABI)
                                       |
double[] input (JVM heap)             |
   -> MemorySegment (off-heap, or     |
      direct if already native)       |
      ---- FFI crossing (downcall) -->|  extern "C" fn(ptr, len, out_ptr)
                                       |  reads/writes raw memory directly
      <-- FFI crossing (upcall) ------|  extern "C" fn pointer, calls back
callback method executes              |
```

## Visualization 2: call granularity crossover (textual fallback)

```
                          Illustrative shape (see benchmark.md for real numbers)
Zero-copy (1 crossing):      cheapest — no copy, one crossing for the whole array
Pure Java (0 crossings):     cheap — no FFI at all, but no native-code benefit either
Batched (1 crossing):        one crossing, but pays a copy in AND a copy out
Scalar (N crossings):        N times the per-call downcall overhead — chatty
Upcall (N crossings, reverse): N times the per-call upcall overhead — typically the most expensive direction
```

## Visualization 3: lifetime/ownership map (textual fallback)

```
Arena.ofConfined()  -- owns the off-heap MemorySegment for exactly one
                        thread's use, for exactly this call's duration
   |
   +-- MemorySegment (input)  -- valid only while the Arena is open
   +-- MemorySegment (output) -- valid only while the Arena is open
   +-- upcall stub            -- valid only while the Arena that created it is open;
                                  Rust must not retain the function pointer
                                  past that point (a "passing invalid lifetimes" bug)
```

## Known traps

- **Letting panic unwind across FFI.** See "Panic containment" above —
  every `extern "C"` entry point in this lab catches and aborts.
- **Passing invalid lifetimes.** An upcall stub (or a `MemorySegment`)
  tied to a confined `Arena` becomes invalid the moment that arena is
  closed; native code retaining a pointer past that point is a
  use-after-free waiting to happen. This lab's Rust code never stores a
  callback pointer or buffer pointer beyond the single call it was passed
  into.
- **Benchmarking debug Rust library.** This lab's `Cargo.toml` pins the
  release profile for both the library the JMH benchmark links against
  and the bench harness; a debug build's FFI call overhead is not
  representative and is never used for benchmark numbers.
- **Calling per field and blaming FFM.** The scalar-downcall variant's
  high per-element cost is a property of *chatty* call granularity, not
  of the FFM mechanism itself — the zero-copy variant uses the identical
  linkage machinery and is orders of magnitude cheaper because it makes
  one coarse-grained call instead of many fine-grained ones.
- **Silently copying buffers.** The batched variant's copy is deliberate
  and disclosed, contrasted directly against the zero-copy variant that
  has none — never presented as if "the downcall" alone explains the
  difference between them.

## Assumptions and scope

- The fixed-record-validation and callback-notification dataset profiles
  reuse the same variant infrastructure as the primary numeric-transform
  dataset (batched/zero-copy downcalls for validation, the upcall variant
  for notification) rather than being swept as a fully independent
  benchmark matrix in this pass — documented here rather than silently
  narrowed.
- This lab's native library targets macOS (`.dylib`) and Linux (`.so`)
  only, matching this repository's development and reference hosts; other
  platforms are not supported.
- Correctness (every transform variant agreeing byte-for-byte, record
  validation catching every corrupted record, every upcall element
  delivered in order) is verified by a fixture shared identically by both
  languages' test suites before any timing is trusted.
- This lab does not attempt to measure JNI directly for a side-by-side
  JNI-vs-FFM comparison — the hypothesis is about FFM's own cost
  structure, not a claim about how much faster FFM is than JNI, which
  this lab's own numbers do not establish.

## Pre-lab diagnostic — answer

The most common confound is **call granularity changing at the same
time as the linkage mechanism** — a team migrating from JNI to FFM often
also restructures its calls from many small, chatty invocations (common
under JNI, where the ceremony per call encouraged batching manually) to
fewer, coarser-grained ones, or switches from copying data into
JNI-managed buffers to working with `MemorySegment`s directly. Either
change alone can produce a 2x (or larger) improvement with the linkage
mechanism held constant — exactly what this lab's own scalar-vs-batched-
vs-zero-copy variants demonstrate using FFM alone, without JNI in the
comparison at all. A "JNI vs. FFM" claim that doesn't hold call shape and
copying strategy fixed between the two measurements is comparing two
things at once and attributing the whole effect to one of them.
