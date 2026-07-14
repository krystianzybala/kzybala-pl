// Canonical numeric value type (plab-003 remediation, audit finding C1/M2).
//
// Native JS `Number` is IEEE-754 double precision: it silently corrupts
// integers above 2^53 (9007199254740993 -> 9007199254740992 at JSON.parse
// time, before any of our code even runs) and it is `typeof "number"` for
// NaN/Infinity/-Infinity, so a naive `typeof x === "number"` schema check
// accepts values that are not a measurement at all. This module is the one
// place a raw measured value is allowed to become canonical storage: every
// importer and the legacy migration must go through `toCanonicalNumber`
// before a value reaches `statistic`, and nothing downstream (schema,
// provenance hashing, regression, rendering) may convert it back to a bare
// `Number` except for display or approximate regression math — and even
// then only from the exact decimal string, never round-tripped through a
// path that already lost precision.
//
// Canonical shape: { value: "<exact decimal string>", numericType: "integer" | "decimal" }
// `value` is the exact source text (normalized: no leading "+", no
// redundant leading zeros, trailing/leading whitespace stripped) — because
// it is a *string*, JSON.stringify/parse round-trips it byte-for-byte, so
// this shape is lossless through the exact serialization path
// (scripts/benchmark-platform/run-id.js's stableStringify) already used for
// canonical hashing. -0 and 0 are DIFFERENT canonical strings ("-0" vs "0")
// and therefore hash differently — audit finding M4's silent collapse
// cannot happen once storage is textual rather than a double.

class NumericError extends Error {}

const NUMERIC_STRING_PATTERN = /^-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?$/;

function normalizeNumericString(raw) {
  const trimmed = String(raw).trim();
  if (trimmed === "") {
    throw new NumericError("canonical numeric value cannot be an empty string");
  }
  if (!NUMERIC_STRING_PATTERN.test(trimmed)) {
    throw new NumericError(`"${raw}" is not a valid canonical numeric string (expected an optionally-signed decimal or scientific-notation integer/decimal)`);
  }
  // Reject "+"-prefixed input up front (the pattern already excludes it) and
  // strip a redundant sign on zero's magnitude ("-0" itself is kept as-is —
  // see the negative-zero contract below — but "-0.0" normalizes to "-0",
  // "-0e5" normalizes to "-0", so every spelling of negative zero collapses
  // to exactly one canonical string instead of silently varying by input
  // format).
  const isNegative = trimmed.startsWith("-");
  const unsigned = isNegative ? trimmed.slice(1) : trimmed;
  const hasExponent = /[eE]/.test(unsigned);
  const hasDecimalPoint = unsigned.includes(".");
  const numericType = hasExponent || hasDecimalPoint ? "decimal" : "integer";

  // Negative-zero contract: any input whose magnitude is exactly zero
  // ("0", "0.0", "0e10", "-0", "-0.0", "-0e3", ...) normalizes to either
  // "0" or "-0" depending on the sign the caller supplied, with no other
  // digits retained (JSON.stringify(-0) === "0" is exactly the JS-Number
  // lossiness this module exists to avoid — a string sign bit does not
  // collapse). Zero is never treated as "decimal": "0.000" and "0" are the
  // same magnitude with no distinguishable trailing precision worth
  // preserving as a numericType distinction.
  const magnitude = numericType === "decimal" ? Number(unsigned) : unsigned;
  const isZeroMagnitude = numericType === "decimal" ? magnitude === 0 : /^0+$/.test(unsigned.split(/[eE]/)[0].replace(".", ""));
  if (isZeroMagnitude) {
    return { value: isNegative ? "-0" : "0", numericType: "integer" };
  }

  return { value: `${isNegative ? "-" : ""}${unsigned}`, numericType };
}

// Accepts a JS number OR a numeric string. Numbers are only safe to pass
// directly when they are known to already be exact (e.g. a small integer a
// test constructs in code) — any value that came from parsing JSON text
// that might exceed Number.MAX_SAFE_INTEGER or need arbitrary decimal
// precision MUST be passed as the original source string instead, so this
// function never re-parses through a lossy Number in between.
function toCanonicalNumber(input) {
  if (typeof input === "number") {
    if (Number.isNaN(input)) {
      throw new NumericError("NaN is not a valid canonical numeric value");
    }
    if (!Number.isFinite(input)) {
      throw new NumericError(`${input > 0 ? "Infinity" : "-Infinity"} is not a valid canonical numeric value`);
    }
    const raw = Object.is(input, -0) ? "-0" : String(input);
    return normalizeNumericString(raw);
  }
  if (typeof input === "string") {
    return normalizeNumericString(input);
  }
  throw new NumericError(`canonical numeric value must be a number or numeric string, got ${typeof input}`);
}

function isCanonicalNumber(value) {
  return (
    value !== null &&
    typeof value === "object" &&
    typeof value.value === "string" &&
    (value.numericType === "integer" || value.numericType === "decimal") &&
    NUMERIC_STRING_PATTERN.test(value.value)
  );
}

function isNegativeZero(canonical) {
  return canonical.value === "-0";
}

function isZero(canonical) {
  return canonical.value === "0" || canonical.value === "-0";
}

function isNegative(canonical) {
  return canonical.value.startsWith("-") && !isZero(canonical);
}

// JS Number is only ever produced for *approximate* math (regression
// percentage deltas, chart positioning) — never written back into
// `statistic`, never hashed, never used to decide validity. Values whose
// exact magnitude cannot round-trip through a double (integers beyond 2^53,
// or decimals with more significant digits than a double carries) will lose
// precision here on purpose — that loss is confined to display/regression
// arithmetic, which already only ever claimed approximate precision.
function toApproximateNumber(canonical) {
  return Number(canonical.value);
}

function safeIntegerOrNull(canonical) {
  if (canonical.numericType !== "integer") return null;
  const approx = toApproximateNumber(canonical);
  return Number.isSafeInteger(approx) ? approx : null;
}

// Formats a canonical value for *display only* — a fixed number of
// significant decimals, matching the precision already used across
// benchmark.md tables. Unlike the old `Number(pointEstimate.toFixed(3))`
// path, this never lets a genuinely non-zero value silently render as
// exactly "0": if rounding would collapse a non-zero canonical value to a
// display magnitude of zero, `underflow: true` is set and `text` shows an
// explicit "<" bound instead of a bare zero (audit finding M3).
function formatForDisplay(canonical, { decimals = 3 } = {}) {
  if (canonical === null) {
    return { text: null, underflow: false };
  }
  if (isZero(canonical)) {
    return { text: canonical.value === "-0" ? "-0" : "0", underflow: false };
  }
  const approx = toApproximateNumber(canonical);
  const rounded = approx.toFixed(decimals);
  const roundedIsZero = Number(rounded) === 0;
  if (roundedIsZero) {
    const bound = (1 / 10 ** decimals).toFixed(decimals);
    return { text: `${isNegative(canonical) ? "-" : ""}<${bound}`, underflow: true };
  }
  return { text: rounded, underflow: false };
}

// Exact-text comparison for equality (used by duplicate/contradiction
// detection) — two canonical numbers are "the same value" only if their
// normalized strings match exactly, so "1.50" and "1.5" (which normalize to
// the same string) compare equal, but no float epsilon fuzziness is
// involved.
function canonicalEquals(a, b) {
  if (a === null || b === null) return a === b;
  return a.value === b.value;
}

export {
  NumericError,
  toCanonicalNumber,
  isCanonicalNumber,
  isZero,
  isNegativeZero,
  isNegative,
  toApproximateNumber,
  safeIntegerOrNull,
  formatForDisplay,
  canonicalEquals,
};
