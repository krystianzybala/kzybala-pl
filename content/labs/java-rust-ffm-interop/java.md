# Java-Rust interop with FFM downcalls and upcalls — Java

## Loading the compiled Rust library

```java
Linker linker = Linker.nativeLinker();
SymbolLookup rust = SymbolLookup.libraryLookup(libraryPath, arena); // our OWN compiled cdylib, not libc

MethodHandle transformScalar = linker.downcallHandle(
    rust.find("ffm_transform_scalar").orElseThrow(),
    FunctionDescriptor.of(ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE));
```

This is the same `Linker`/`SymbolLookup`/`FunctionDescriptor` pattern this
repository already uses for libc downcalls (`CpuAffinity`, `NativeSleep`
in other labs) — the only difference is `SymbolLookup.libraryLookup(path,
arena)` instead of `linker.defaultLookup()`, because the target is a
library this lab's own build produces, not the system's libc.
`RustLibrary`'s static initializer shells out to `cargo build --release`
if the compiled `.dylib`/`.so` isn't already present, so `mvn test` works
from a fresh checkout without a separate manual build step.

## Scalar downcall (chatty)

```java
public static double[] scalarDowncall(double[] input) {
    double[] out = new double[input.length];
    for (int i = 0; i < input.length; i++) {
        out[i] = RustLibrary.transformScalar(input[i]); // ONE FFI crossing per element
    }
    return out;
}
```

## Batched downcall (one crossing, two copies)

```java
try (Arena arena = Arena.ofConfined()) {
    MemorySegment inSeg = arena.allocate((long) input.length * Double.BYTES);
    MemorySegment.copy(input, 0, inSeg, ValueLayout.JAVA_DOUBLE, 0, input.length); // copy IN
    MemorySegment outSeg = arena.allocate((long) input.length * Double.BYTES);
    RustLibrary.transformBatch(inSeg, outSeg, input.length); // ONE FFI crossing
    double[] out = new double[input.length];
    MemorySegment.copy(outSeg, ValueLayout.JAVA_DOUBLE, 0, out, 0, input.length); // copy OUT
    return out;
}
```

## Zero-copy buffer downcall (one crossing, zero copies)

```java
public static void zeroCopyDowncall(MemorySegment segment, long count) {
    RustLibrary.transformBatch(segment, segment, count); // same native function, in place, no copy at all
}
```

The caller here already holds the data as a `MemorySegment` from
whatever produced it — this lab's benchmark allocates it once, outside
the timed region, specifically to isolate "does a zero-copy call cost
less than one that copies" from "how expensive is allocating the
segment."

## Rust-to-Java upcall

```java
MethodHandle bound = MethodHandles.lookup()
    .findVirtual(CallbackSink.class, "onValue", MethodType.methodType(void.class, double.class))
    .bindTo(sink);
MemorySegment callbackStub = linker.upcallStub(bound, FunctionDescriptor.ofVoid(ValueLayout.JAVA_DOUBLE), arena);

RustLibrary.transformWithCallback(inSeg, input.length, callbackStub); // Rust calls `sink.onValue(...)` once per element
```

`Linker.upcallStub` turns a Java `MethodHandle` into a native function
pointer Rust can call exactly like any other C function pointer — Rust
has no idea it's calling back into a JVM. The stub (and the `Arena` that
owns it) must stay open for the entire duration of the native call that
receives it; closing the arena early while Rust still holds the pointer
is exactly the "passing invalid lifetimes" trap.

## Fixed-record validation

```java
byte[] records = FixedRecordFormat.buildRecords(count, FixedRecordFormat::expectedValue);
long invalidCount = FfmInteropOps.validateRecords(records); // ONE FFI crossing over the whole buffer
```

## Correctness gate

`FfmInteropOperationsTest` asserts that all four transform variants
(pure Java, scalar, batched, zero-copy) agree byte-for-byte on the same
inputs, that record validation correctly counts deliberately-corrupted
records and rejects a misaligned buffer length, and that the upcall
variant delivers every element in order through two different callback
shapes (a collecting `List` and a `DoubleConsumer`). `mvn test` runs this
suite — including real downcalls and upcalls into the compiled Rust
library — before any benchmark output is trusted.

## JMH benchmark

`FfmInteropBenchmark` runs the five JVM-measurable variants (pure Rust
baseline has no meaning inside a JMH benchmark — see rust.md) over the
primary numeric-transform dataset, with one warm call in `@Setup` so
downcall-stub JIT compilation isn't attributed to the first measured
iteration.

The runnable Maven/JMH project (with correctness tests under
`src/test/java/`) is at
<a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/java-rust-ffm-interop/code/java" rel="noopener"><code>content/labs/java-rust-ffm-interop/code/java/</code></a>
in this site's repository.
