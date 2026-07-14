// Legacy reference-lab data (plab-003 task 9).
// Transcribed verbatim from the "Measured data" tables already committed in
// each lab's benchmark.md (docs/benchmark-results-inventory.md is the full
// inventory this was built from) — no number here was invented, rounded, or
// re-measured. Every record is stamped evidenceMaturity: "legacy-unprovenanced"
// with provenance.rawArtifactPath: null, because the original JMH/Criterion
// raw output behind these prose tables was never captured or committed —
// see docs/benchmark-results-migration.md for why these can only ever be
// *replaced* by a real re-run through the plab-002/plab-003 pipeline, never
// silently promoted to "verified" in place.
//
// scripts/test-benchmark-results.js cross-checks every pointEstimate below
// still appears verbatim in its source benchmark.md, so this file cannot
// silently drift from the prose it was transcribed from.
import { SCHEMA_VERSION } from "./schema.js";

const COMMON_DISCLOSURE = {
  host: "Apple M1 Max",
  os: "macOS",
  arch: "arm64",
  note: "Single developer machine, not a dedicated thermally-stable rig, no CPU affinity pinning — see the lab's own benchmark.md disclosure for the exact caveats.",
};

function legacyRecord({ labId, variant, language, harness, unit, direction, statisticName, pointEstimate, error, errorKind, notes = [] }) {
  return {
    schemaVersion: SCHEMA_VERSION,
    labId,
    variant,
    language,
    harness,
    metricKind: "scalar",
    unit,
    direction,
    statistic: { pointEstimate, error, errorKind, statisticName, sampleCount: null },
    provenance: {
      rawArtifactPath: null,
      sourceRevision: "unversioned",
      command: null,
      environmentRef: null,
      importerVersion: "legacy-migration@1",
      capturedAt: null,
    },
    comparability: {
      buildMode: "release",
      datasetId: null,
      semanticsFixtureHash: null,
      architecture: COMMON_DISCLOSURE.arch,
    },
    evidenceMaturity: "legacy-unprovenanced",
    notes: [...notes, `Host: ${COMMON_DISCLOSURE.host}, ${COMMON_DISCLOSURE.os}, ${COMMON_DISCLOSURE.arch}. ${COMMON_DISCLOSURE.note}`],
  };
}

const LEGACY_RESULTS = {
  "cache-hierarchy": [
    legacyRecord({ labId: "cache-hierarchy", variant: "sequentialSmall", language: "java", harness: "jmh", unit: "µs", direction: "lowerIsBetter", statisticName: "mean", pointEstimate: 2197.141, error: 5.12, errorKind: "ci99.9" }),
    legacyRecord({ labId: "cache-hierarchy", variant: "randomSmall", language: "java", harness: "jmh", unit: "µs", direction: "lowerIsBetter", statisticName: "mean", pointEstimate: 2201.602, error: 18.088, errorKind: "ci99.9" }),
    legacyRecord({ labId: "cache-hierarchy", variant: "sequentialLarge", language: "java", harness: "jmh", unit: "µs", direction: "lowerIsBetter", statisticName: "mean", pointEstimate: 2447.468, error: 365.86, errorKind: "ci99.9" }),
    legacyRecord({ labId: "cache-hierarchy", variant: "randomLarge", language: "java", harness: "jmh", unit: "µs", direction: "lowerIsBetter", statisticName: "mean", pointEstimate: 118539.85, error: 3185.489, errorKind: "ci99.9" }),
    legacyRecord({ labId: "cache-hierarchy", variant: "sequential_small", language: "rust", harness: "criterion", unit: "ms", direction: "lowerIsBetter", statisticName: "median", pointEstimate: 1.2578, error: (1.2626 - 1.2557) / 2, errorKind: "ci95" }),
    legacyRecord({ labId: "cache-hierarchy", variant: "random_small", language: "rust", harness: "criterion", unit: "ms", direction: "lowerIsBetter", statisticName: "median", pointEstimate: 1.2739, error: (1.289 - 1.2626) / 2, errorKind: "ci95" }),
    legacyRecord({ labId: "cache-hierarchy", variant: "sequential_large", language: "rust", harness: "criterion", unit: "ms", direction: "lowerIsBetter", statisticName: "median", pointEstimate: 1.282, error: (1.2841 - 1.2809) / 2, errorKind: "ci95" }),
    legacyRecord({ labId: "cache-hierarchy", variant: "random_large", language: "rust", harness: "criterion", unit: "ms", direction: "lowerIsBetter", statisticName: "median", pointEstimate: 110.9753, error: (111.7004 - 110.4471) / 2, errorKind: "ci95" }),
  ],
  "cas-contention": [
    legacyRecord({ labId: "cas-contention", variant: "1-cas", language: "java", harness: "jmh", unit: "ops/ms", direction: "higherIsBetter", statisticName: "mean", pointEstimate: 139812.901, error: 511.706, errorKind: "ci99.9" }),
    legacyRecord({ labId: "cas-contention", variant: "2-cas", language: "java", harness: "jmh", unit: "ops/ms", direction: "higherIsBetter", statisticName: "mean", pointEstimate: 29390.379, error: 600.692, errorKind: "ci99.9" }),
    legacyRecord({ labId: "cas-contention", variant: "4-cas", language: "java", harness: "jmh", unit: "ops/ms", direction: "higherIsBetter", statisticName: "mean", pointEstimate: 14219.198, error: 520.758, errorKind: "ci99.9" }),
    legacyRecord({ labId: "cas-contention", variant: "8-cas", language: "java", harness: "jmh", unit: "ops/ms", direction: "higherIsBetter", statisticName: "mean", pointEstimate: 4520.314, error: 114.867, errorKind: "ci99.9" }),
    legacyRecord({ labId: "cas-contention", variant: "single-writer-1", language: "java", harness: "jmh", unit: "ops/ms", direction: "higherIsBetter", statisticName: "mean", pointEstimate: 483984.812, error: 1782.614, errorKind: "ci99.9" }),
    legacyRecord({ labId: "cas-contention", variant: "1-cas", language: "rust", harness: "criterion", unit: "ops/ms", direction: "higherIsBetter", statisticName: "median", pointEstimate: 119200, error: null, errorKind: null, notes: ["Derived from median batch time (20,000 ops / 167.76 µs); batch sizes differ by thread count so raw batch times aren't directly comparable across rows — derived ops/ms is."] }),
    legacyRecord({ labId: "cas-contention", variant: "2-cas", language: "rust", harness: "criterion", unit: "ops/ms", direction: "higherIsBetter", statisticName: "median", pointEstimate: 34767, error: null, errorKind: null, notes: ["Derived from median batch time (40,000 ops / 1.1505 ms)."] }),
    legacyRecord({ labId: "cas-contention", variant: "4-cas", language: "rust", harness: "criterion", unit: "ops/ms", direction: "higherIsBetter", statisticName: "median", pointEstimate: 19505, error: null, errorKind: null, notes: ["Derived from median batch time (80,000 ops / 4.1018 ms)."] }),
    legacyRecord({ labId: "cas-contention", variant: "8-cas", language: "rust", harness: "criterion", unit: "ops/ms", direction: "higherIsBetter", statisticName: "median", pointEstimate: 5945, error: null, errorKind: null, notes: ["Derived from median batch time (160,000 ops / 26.912 ms)."] }),
    legacyRecord({ labId: "cas-contention", variant: "single-writer-1", language: "rust", harness: "criterion", unit: "ops/ms", direction: "higherIsBetter", statisticName: "median", pointEstimate: 3177654, error: null, errorKind: null, notes: ["Derived from median batch time (160,000 ops / 50.356 µs)."] }),
  ],
  "false-sharing": [
    legacyRecord({ labId: "false-sharing", variant: "shared", language: "java", harness: "jmh", unit: "ops/ms", direction: "higherIsBetter", statisticName: "mean", pointEstimate: 68869, error: 4506, errorKind: "ci99.9" }),
    legacyRecord({ labId: "false-sharing", variant: "padded-manual", language: "java", harness: "jmh", unit: "ops/ms", direction: "higherIsBetter", statisticName: "mean", pointEstimate: 255796, error: 32251, errorKind: "ci99.9" }),
    legacyRecord({ labId: "false-sharing", variant: "contended", language: "java", harness: "jmh", unit: "ops/ms", direction: "higherIsBetter", statisticName: "mean", pointEstimate: 325893, error: 7616, errorKind: "ci99.9" }),
    legacyRecord({ labId: "false-sharing", variant: "shared", language: "rust", harness: "criterion", unit: "ms", direction: "lowerIsBetter", statisticName: "median", pointEstimate: 1.0017, error: (1.0228 - 0.9848) / 2, errorKind: "minmax" }),
    legacyRecord({ labId: "false-sharing", variant: "padded-align64", language: "rust", harness: "criterion", unit: "µs", direction: "lowerIsBetter", statisticName: "median", pointEstimate: 263.44, error: (265.07 - 262.21) / 2, errorKind: "minmax" }),
  ],
  "spsc-ring-buffer": [
    legacyRecord({ labId: "spsc-ring-buffer", variant: "spsc-combined", language: "java", harness: "jmh", unit: "ops/ms", direction: "higherIsBetter", statisticName: "mean", pointEstimate: 24850.079, error: 412.206, errorKind: "ci99.9" }),
    legacyRecord({ labId: "spsc-ring-buffer", variant: "spsc-produce", language: "java", harness: "jmh", unit: "ops/ms", direction: "higherIsBetter", statisticName: "mean", pointEstimate: 12424.844, error: 206.229, errorKind: "ci99.9" }),
    legacyRecord({ labId: "spsc-ring-buffer", variant: "spsc-consume", language: "java", harness: "jmh", unit: "ops/ms", direction: "higherIsBetter", statisticName: "mean", pointEstimate: 12425.235, error: 205.978, errorKind: "ci99.9" }),
    legacyRecord({ labId: "spsc-ring-buffer", variant: "pipeline", language: "rust", harness: "criterion", unit: "µs", direction: "lowerIsBetter", statisticName: "median", pointEstimate: 907.47, error: (955.78 - 864.16) / 2, errorKind: "minmax", notes: ["Includes thread spawn/join overhead per 100-sample batch of 200,000 items — not directly comparable to the Java @Group number (see benchmark.md Method)."] }),
  ],
  "thread-per-core": [
    legacyRecord({ labId: "thread-per-core", variant: "sharedPoolIncrement", language: "java", harness: "jmh", unit: "ops/ms", direction: "higherIsBetter", statisticName: "mean", pointEstimate: 48783.53, error: 3934.041, errorKind: "ci99.9" }),
    legacyRecord({ labId: "thread-per-core", variant: "ownedPartitionIncrement", language: "java", harness: "jmh", unit: "ops/ms", direction: "higherIsBetter", statisticName: "mean", pointEstimate: 1861211.873, error: 9726.822, errorKind: "ci99.9" }),
    legacyRecord({ labId: "thread-per-core", variant: "shared_pool_increment_4_threads", language: "rust", harness: "criterion", unit: "ms", direction: "lowerIsBetter", statisticName: "median", pointEstimate: 2.3671, error: null, errorKind: null, notes: ["Derived ops/ms ≈33,798 (80,000 increments / 2.3671 ms)."] }),
    legacyRecord({ labId: "thread-per-core", variant: "owned_partition_increment_4_threads", language: "rust", harness: "criterion", unit: "µs", direction: "lowerIsBetter", statisticName: "median", pointEstimate: 65.375, error: null, errorKind: null, notes: ["Derived ops/ms ≈1,223,982 (80,000 increments / 65.375 µs)."] }),
  ],
};

// Labs with no benchmark.md at all today (docs/benchmark-platform-inventory.md):
// memory-ordering, mesi. Absent from LEGACY_RESULTS on purpose — there is no
// data to migrate, and inventing a placeholder record for them would itself
// be the fabrication this migration exists to avoid.
function legacyResultsFor(labId) {
  return LEGACY_RESULTS[labId] ?? [];
}

const LEGACY_LAB_IDS = Object.keys(LEGACY_RESULTS);

export { LEGACY_RESULTS, LEGACY_LAB_IDS, legacyResultsFor };
