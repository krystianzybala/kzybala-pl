# Zero-copy binary parsing and views — exercises

## Exercise 1 (diagnosis): the "zero-copy" decoder that wasn't

A team's message decoder logs a per-field trace for debugging, and one
line reads: `log.debug("field={}", textField.toString())`, where
`textField` is meant to be a zero-copy view type. Allocation profiling
shows real, message-proportional allocation even in production where
the log line is disabled at the configured log level. Using this lab's
mechanism, name the most likely explanation.

**Success criteria:** you identify that a disabled log level does NOT
prevent the ARGUMENTS to `log.debug(...)` from being evaluated in most
logging frameworks unless the call site uses a guard (`if
(log.isDebugEnabled())`) or a lazy/supplier-based logging API —
`textField.toString()` still runs and still materializes a real
`String` from the view every single call, regardless of whether the
resulting log line is ever printed; you connect this to this lab's
"claiming zero-copy while converting strings" trap directly: the
`toString()` call IS the string conversion this lab's own
`validatedZeroCopyView` is built specifically to avoid, and it is
happening unconditionally.

<details>
<summary>Hint</summary>

This lab's own `copyingDecoder` and `validatedZeroCopyView` differ in
exactly one meaningful way for a text field: whether `new String(...)`
(or its Rust equivalent) is ever called. Does calling `.toString()` on a
view object, even for logging, avoid that call?
</details>

## Exercise 2 (implementation): measure the validation-only cost

Using this lab's Java `Utf8FieldDecoders`, add a SIXTH variant,
`unsafeUnvalidatedView` — a copy of `validatedZeroCopyView` with the
`ZeroCopyFixtures.isValidUtf8(...)` call removed — and benchmark it
alongside the real five (dev machine, wiring-only — do NOT publish these
numbers, and delete this variant when done; it exists only to measure a
cost this lab's own methodology otherwise refuses to isolate, per its
"skipping validation" trap). Predict, before measuring, roughly how much
of `validatedZeroCopyView`'s total ns/message the UTF-8 validation step
itself accounts for.

**Success criteria:** you implement the comparison variant correctly and
clearly mark it as a DIAGNOSTIC-ONLY addition, never merged into the
lab's real matrix; you state a specific, falsifiable prediction for the
validation share of total cost; you measure and report the actual gap
between `validatedZeroCopyView` and your unvalidated version; and you
explicitly state why this variant must never be published or left in
the repository afterward — this lab's own "invalid conclusions this
laboratory must never publish" list forbids concluding anything from a
run with validation skipped, which is exactly what this exercise's
temporary variant does.

<details>
<summary>Hint</summary>

`std::str::from_utf8`/`CharsetDecoder.decode` walk every byte of the
text field once. For this dataset's text lengths (8–64 bytes), how large
a fraction of a ~10–27 ns/message total (java.md's own measured range)
would you expect a single linear byte-scan to be?
</details>

<details>
<summary>Solution</summary>

There is no universally correct fraction independent of the measured
host and text length, but the mechanism predicts the gap should scale
with text length (since validation is O(length)) while the rest of the
per-message cost (header reads, bounds checks) stays roughly constant —
if your measurement shows the gap growing across this dataset's
different `textLen` values (8, 16, 32, 64), that is direct, real
confirmation that validation cost is a genuine, measurable, length-
dependent line item, not a rounding error — worth knowing even though
this lab's methodology (correctly) refuses to publish a shortcut that
skips it.
</details>

## Exercise 3 (evidence interpretation): read the allocation curve, not one point

Below is the shape of one run's `-prof gc` output across `nestedRepeatedFields`'
`objectBuildingDecoder`, varying `recordCount` (illustrative structure,
not real captured evidence):

```text
recordCount=2   gc.alloc.rate.norm ≈  180 B/message
recordCount=4   gc.alloc.rate.norm ≈  340 B/message
recordCount=8   gc.alloc.rate.norm ≈  660 B/message
```

Answer from this block alone: (a) is the growth from 2 to 8 records
roughly linear in record count, and what does that imply about WHERE
the allocation is coming from; (b) would you expect
`validatedZeroCopyView`'s allocation to show the same growth pattern
across `recordCount`; (c) name one thing this per-record-count breakdown
tells you that a single aggregate "B/message averaged across the whole
dataset" number would hide.

**Success criteria:** (a) yes, roughly linear (180→340→660 is close to
doubling with each doubling of `recordCount`), implying the allocation
scales with the number of `SubRecord` objects built, not with a fixed
per-message overhead — consistent with this lab's mechanism ("one more
allocation layer PER VALUE", theory.md); (b) no —
`validatedZeroCopyView` never materializes per-record objects, so its
allocation should stay near zero regardless of `recordCount`, a
DIFFERENT (flat) curve shape, not just a lower one; (c) this dataset
mixes `recordCount` values (2, 4, 8) across its messages by design
(fixtures), so a single averaged B/message number would blend
light and heavy messages together and could mask the linear-in-
record-count relationship entirely — reading the breakdown by
`recordCount` (achievable by filtering the dataset or adding a
diagnostic parameter) reveals the actual mechanism an aggregate hides.

<details>
<summary>Hint</summary>

This lab's own `nestedRepeatedFields` dataset deliberately cycles
`recordCount` through {2, 4, 8} rather than using a single fixed value
(fixtures.json) — what would that choice let you investigate that a
single-record-count dataset could not?
</details>
