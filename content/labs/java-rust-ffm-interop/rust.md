# Java-Rust interop with FFM downcalls and upcalls — Rust

## The narrow, stable C ABI surface

```toml
[lib]
crate-type = ["cdylib", "rlib"] # cdylib for Java's FFM linker; rlib for cargo test/bench
```

Every downcall target is a plain `extern "C" fn` reusing a pure,
plain-Rust function underneath — the FFI wrapper and the logic are never
the same function, so this crate's own tests and Criterion "pure Rust
baseline" bench exercise the exact same code the FFI wrappers call, never
a parallel, potentially-drifted copy.

```rust
pub fn transform_scalar_pure(x: f64) -> f64 { x * 2.0 + 1.0 }

#[no_mangle]
pub extern "C" fn ffm_transform_scalar(x: f64) -> f64 {
    match catch_unwind(|| transform_scalar_pure(x)) {
        Ok(v) => v,
        Err(_) => std::process::abort(), // never unwind across the FFI boundary
    }
}
```

## Panic containment at every entry point

```rust
#[no_mangle]
pub unsafe extern "C" fn ffm_transform_batch(ptr: *const f64, len: usize, out_ptr: *mut f64) {
    let result = catch_unwind(|| { /* ... */ });
    if result.is_err() { std::process::abort(); }
}
```

Every `extern "C"` function in this crate follows this same
catch-and-abort pattern — see theory.md's "letting panic unwind across
FFI" trap. Ordinary input-shaped problems (a misaligned validation
buffer) are returned as an error code (`-1`) rather than ever reaching a
`panic!`, so the abort path is reserved for genuine internal-invariant
violations.

## Batched transform: one function, two Java-side call shapes

```rust
pub unsafe extern "C" fn ffm_transform_batch(ptr: *const f64, len: usize, out_ptr: *mut f64) {
    // in-place if ptr == out_ptr, otherwise reads from ptr and writes to out_ptr
}
```

Both the "batched downcall" and "zero-copy buffer downcall" Java variants
call this identical function — see java.md for how the difference is
entirely on the Java side (copy-in/copy-out vs. already-native data).

## Fixed-record validation

```rust
pub fn validate_records_pure(buf: &[u8]) -> i64 { /* counts records whose stored value doesn't match expected_value(id) */ }

#[no_mangle]
pub unsafe extern "C" fn ffm_validate_records(ptr: *const u8, len: usize) -> i64 {
    let result = catch_unwind(|| {
        if len % RECORD_SIZE != 0 { return -1; } // input-shaped error, not a panic
        validate_records_pure(std::slice::from_raw_parts(ptr, len))
    });
    result.unwrap_or(-1)
}
```

## The upcall target: an ordinary C function pointer

```rust
#[no_mangle]
pub unsafe extern "C" fn ffm_transform_with_callback(
    ptr: *const f64, len: usize, callback: extern "C" fn(f64),
) {
    let result = catch_unwind(|| {
        for &x in std::slice::from_raw_parts(ptr, len) {
            callback(transform_scalar_pure(x)); // Rust has no idea this points into a JVM
        }
    });
    if result.is_err() { std::process::abort(); }
}
```

From Rust's perspective, `callback` is exactly the same kind of value it
would be if it pointed to another Rust or C function — `Linker.upcallStub`
on the Java side is what makes it actually point back into the JVM; this
crate's own tests exercise `ffm_transform_with_callback` with a plain
Rust `extern "C" fn` callback, never a real upcall stub, since testing
the JVM-crossing half of the mechanism belongs to the Java correctness
suite.

## Correctness gate

`cargo test --release` asserts pure-function/FFI-wrapper agreement for
every variant, in-place and out-of-place batch transforms, record
validation (including the misaligned-length error path), and correct,
in-order callback delivery. `cargo clippy --release --all-targets -- -D
warnings` and `cargo fmt --check` both run clean.

## Criterion benchmark: the pure Rust baseline

```rust
c.bench_function("pure_rust_baseline_scalar", |b| { /* transform_scalar_pure in a loop, no FFI */ });
c.bench_function("pure_rust_baseline_batch", |b| { /* transform_batch_pure over a whole array, no FFI */ });
```

This is the "pure Rust baseline" variant — measured only here, in Rust's
own harness, on the pinned release profile. It is never combined with
Java's numbers into a single ranking (this lab's non-goal: no
Java-versus-Rust winner) — it exists so a reader can see the ceiling this
computation's cost has with no interop involved at all, in each language
separately.

The runnable Cargo project (with correctness tests in `src/lib.rs` and
the `cdylib` Java links against) is at
<a href="https://github.com/krystianzybala/kzybala-pl/tree/main/content/labs/java-rust-ffm-interop/code/rust" rel="noopener"><code>content/labs/java-rust-ffm-interop/code/rust/</code></a>
in this site's repository.
