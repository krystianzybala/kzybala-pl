// Versioned unit registry (plab-003 remediation, audit finding M1/robustness
// gap: "no unit whitelist exists anywhere; unknown units is not just
// untested but structurally unenforceable"). Every unit a canonical record
// claims must be a key in this registry — an unrecognized unit string is now
// a rejected record, not silently accepted free text.
const UNIT_REGISTRY_VERSION = 1;

const UNIT_REGISTRY = {
  "ops/ms": { dimension: "throughput" },
  "ops/s": { dimension: "throughput" },
  "ops/us": { dimension: "throughput" },
  "ns/op": { dimension: "latency" },
  "ns": { dimension: "time" },
  "us": { dimension: "time" },
  "µs": { dimension: "time" },
  "ms": { dimension: "time" },
  "s": { dimension: "time" },
  "count": { dimension: "count" },
  "%": { dimension: "percentage" },
  "cycles": { dimension: "count" },
  "instructions": { dimension: "count" },
  "insn per cycle": { dimension: "ratio" },
  "bytes": { dimension: "size" },
  "GHz": { dimension: "frequency" },
};

function isKnownUnit(unit) {
  return typeof unit === "string" && Object.prototype.hasOwnProperty.call(UNIT_REGISTRY, unit);
}

function unitDimension(unit) {
  return UNIT_REGISTRY[unit]?.dimension ?? null;
}

export { UNIT_REGISTRY_VERSION, UNIT_REGISTRY, isKnownUnit, unitDimension };
