//! The Rust side of the Java-Rust FFM interop lab: a `cdylib` exporting a
//! narrow, stable C ABI that the Java side calls via
//! `java.lang.foreign.Linker` downcalls, plus one upcall-driven function
//! that calls back into Java. Every plain (non-`extern "C"`) function here
//! is the pure Rust logic, reused both by the FFI wrappers and directly by
//! this crate's own Criterion "pure Rust baseline" benchmark, so the two
//! never drift apart into different implementations.
//!
//! # Panic containment
//! Every `extern "C"` entry point wraps its body in
//! [`std::panic::catch_unwind`] and calls [`std::process::abort`] on a
//! caught panic rather than letting it propagate — unwinding a Rust panic
//! across an `extern "C"` boundary into Java is undefined behavior (the
//! "letting panic unwind across FFI" trap this lab documents). Aborting is
//! the correct, safe response to an internal-invariant violation at this
//! boundary; it is not the same as gracefully returning an error, and this
//! lab's pure functions are written so that user-input-shaped problems
//! (short buffers, invalid values) return an error code instead of ever
//! reaching a `panic!`.

use std::panic::catch_unwind;

pub const RECORD_SIZE: usize = 16;

/// The pure numeric transform every "numeric transform" variant performs:
/// deterministic, side-effect-free, safe to call directly from Rust with
/// no FFI involved at all (the "pure Rust baseline").
pub fn transform_scalar_pure(x: f64) -> f64 {
    x * 2.0 + 1.0
}

pub fn transform_batch_pure(input: &[f64], output: &mut [f64]) {
    for (o, &i) in output.iter_mut().zip(input.iter()) {
        *o = transform_scalar_pure(i);
    }
}

/// Fixed record validation: `id` (8-byte LE) + `value` (8-byte LE) per
/// record, `value` expected to equal `expected_value(id)`. Returns the
/// count of records whose stored value does NOT match.
pub fn expected_value(id: u64) -> u64 {
    id.wrapping_mul(2_654_435_761).wrapping_add(1)
}

pub fn validate_records_pure(buf: &[u8]) -> i64 {
    let record_count = buf.len() / RECORD_SIZE;
    let mut invalid = 0i64;
    for i in 0..record_count {
        let off = i * RECORD_SIZE;
        let id = u64::from_le_bytes(buf[off..off + 8].try_into().unwrap());
        let value = u64::from_le_bytes(buf[off + 8..off + 16].try_into().unwrap());
        if value != expected_value(id) {
            invalid += 1;
        }
    }
    invalid
}

// --- extern "C" ABI surface (downcall targets) ---

/// Variant: scalar downcall per item — one FFI crossing per element.
#[no_mangle]
pub extern "C" fn ffm_transform_scalar(x: f64) -> f64 {
    match catch_unwind(|| transform_scalar_pure(x)) {
        Ok(v) => v,
        Err(_) => std::process::abort(),
    }
}

/// Variants: batched downcall and zero-copy buffer downcall — both call
/// this SAME function; the difference between the two variants is
/// entirely on the Java side (whether Java copies a heap `double[]` into
/// an off-heap segment first, or already holds the data as a
/// `MemorySegment` with no copy at all — see java.md).
///
/// # Safety
/// `ptr` must be valid for reads of `len` `f64`s and `out_ptr` valid for
/// writes of `len` `f64`s; the two ranges must not overlap unless
/// identical (in which case the transform is applied in place).
#[no_mangle]
pub unsafe extern "C" fn ffm_transform_batch(ptr: *const f64, len: usize, out_ptr: *mut f64) {
    let result = catch_unwind(|| {
        let input = std::slice::from_raw_parts(ptr, len);
        if ptr as *const () == out_ptr as *const () {
            // In-place: read each element before overwriting it.
            let out = std::slice::from_raw_parts_mut(out_ptr, len);
            for v in out.iter_mut() {
                *v = transform_scalar_pure(*v);
            }
        } else {
            let output = std::slice::from_raw_parts_mut(out_ptr, len);
            transform_batch_pure(input, output);
        }
    });
    if result.is_err() {
        std::process::abort();
    }
}

/// Variant support: fixed-record validation downcall. Returns the count
/// of invalid records, or `-1` if `len` is not a multiple of
/// [`RECORD_SIZE`] (an input-shaped error, returned rather than panicking).
///
/// # Safety
/// `ptr` must be valid for reads of `len` bytes.
#[no_mangle]
pub unsafe extern "C" fn ffm_validate_records(ptr: *const u8, len: usize) -> i64 {
    let result = catch_unwind(|| {
        if len % RECORD_SIZE != 0 {
            return -1;
        }
        let buf = std::slice::from_raw_parts(ptr, len);
        validate_records_pure(buf)
    });
    result.unwrap_or(-1)
}

/// Variant: Rust-to-Java upcall. Iterates `len` elements, transforms each,
/// and calls `callback` once per element with the transformed value — the
/// "callback notification" dataset profile's mechanism. `callback` is a
/// native function pointer Java constructed via `Linker.upcallStub` over
/// a Java method; if that Java method throws, the JVM's own upcall-stub
/// semantics govern what happens next (see theory.md) — this Rust code
/// only contains ITS OWN panics, not exceptions that occur on the Java
/// side of the callback.
///
/// # Safety
/// `ptr` must be valid for reads of `len` `f64`s; `callback` must be a
/// valid, ABI-compatible function pointer for the lifetime of this call.
#[no_mangle]
pub unsafe extern "C" fn ffm_transform_with_callback(
    ptr: *const f64,
    len: usize,
    callback: extern "C" fn(f64),
) {
    let result = catch_unwind(|| {
        let input = std::slice::from_raw_parts(ptr, len);
        for &x in input {
            let transformed = transform_scalar_pure(x);
            callback(transformed);
        }
    });
    if result.is_err() {
        std::process::abort();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn transform_scalar_matches_formula() {
        for &x in &[0.0, 1.0, -3.5, 100.25] {
            assert_eq!(transform_scalar_pure(x), x * 2.0 + 1.0);
        }
    }

    #[test]
    fn transform_batch_matches_scalar_elementwise() {
        let input: Vec<f64> = (0..100).map(|i| i as f64 * 0.5).collect();
        let mut output = vec![0.0; input.len()];
        transform_batch_pure(&input, &mut output);
        for (i, &x) in input.iter().enumerate() {
            assert_eq!(output[i], transform_scalar_pure(x));
        }
    }

    #[test]
    fn ffm_transform_scalar_matches_pure_function() {
        for &x in &[0.0, 1.0, -3.5, 100.25] {
            assert_eq!(ffm_transform_scalar(x), transform_scalar_pure(x));
        }
    }

    #[test]
    fn ffm_transform_batch_out_of_place_matches_pure_function() {
        let input: Vec<f64> = (0..64).map(|i| i as f64).collect();
        let mut output = vec![0.0; input.len()];
        unsafe {
            ffm_transform_batch(input.as_ptr(), input.len(), output.as_mut_ptr());
        }
        for (i, &x) in input.iter().enumerate() {
            assert_eq!(output[i], transform_scalar_pure(x));
        }
    }

    #[test]
    fn ffm_transform_batch_in_place_matches_pure_function() {
        let mut buf: Vec<f64> = (0..64).map(|i| i as f64).collect();
        let expected: Vec<f64> = buf.iter().map(|&x| transform_scalar_pure(x)).collect();
        unsafe {
            let ptr = buf.as_mut_ptr();
            ffm_transform_batch(ptr, buf.len(), ptr);
        }
        assert_eq!(buf, expected);
    }

    #[test]
    fn validate_records_pure_counts_invalid_records() {
        let mut buf = Vec::new();
        for id in 0..10u64 {
            buf.extend_from_slice(&id.to_le_bytes());
            let value = if id == 3 { 999 } else { expected_value(id) }; // corrupt one record
            buf.extend_from_slice(&value.to_le_bytes());
        }
        assert_eq!(validate_records_pure(&buf), 1);
    }

    #[test]
    fn ffm_validate_records_rejects_misaligned_length() {
        let buf = [0u8; RECORD_SIZE + 1];
        let result = unsafe { ffm_validate_records(buf.as_ptr(), buf.len()) };
        assert_eq!(result, -1);
    }

    #[test]
    fn ffm_validate_records_matches_pure_function() {
        let mut buf = Vec::new();
        for id in 0..20u64 {
            buf.extend_from_slice(&id.to_le_bytes());
            buf.extend_from_slice(&expected_value(id).to_le_bytes());
        }
        let result = unsafe { ffm_validate_records(buf.as_ptr(), buf.len()) };
        assert_eq!(result, 0);
    }

    #[test]
    fn ffm_transform_with_callback_invokes_callback_once_per_element_in_order() {
        use std::sync::Mutex;
        static SEEN: Mutex<Vec<f64>> = Mutex::new(Vec::new());
        extern "C" fn record(x: f64) {
            SEEN.lock().unwrap().push(x);
        }
        SEEN.lock().unwrap().clear();
        let input: Vec<f64> = (0..10).map(|i| i as f64).collect();
        unsafe {
            ffm_transform_with_callback(input.as_ptr(), input.len(), record);
        }
        let seen = SEEN.lock().unwrap();
        assert_eq!(seen.len(), input.len());
        for (i, &x) in input.iter().enumerate() {
            assert_eq!(seen[i], transform_scalar_pure(x));
        }
    }
}
