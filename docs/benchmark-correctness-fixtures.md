# Correctness before timing

`spec.md`'s "Correctness before timing" requirement: a Java or Rust
implementation's shared correctness suite must pass before its benchmark
numbers are accepted. `scripts/benchmark-platform/correctness-gate.js`
(`runCorrectnessGate(labId)`) is the enforcement mechanism; run it via
`node scripts/benchmark-platform/run-correctness-gate.js <lab-id>`.

## What the gate actually checks

For a given lab id it looks at `content/labs/<id>/code/{java,rust}/` and:

- Java: passes if `src/test/` exists and `mvn test` succeeds.
- Rust: passes if any `#[test]` function exists under `src/` and
  `cargo test` succeeds.
- A language directory that exists but has no tests reports `missing`, not
  a pass — see `docs/benchmark-platform-inventory.md` for which of the 7
  labs currently have this gap (`cache-hierarchy`, `cas-contention`,
  `false-sharing` have no Java-side tests yet).

`overall` is `"blocked"` only on an actual test failure (exit code 1 — CI
and the smoke job treat this as fatal), `"gap"` when a language has no
tests to run, `"pass"` when everything present passed.

Verified against the real repository while writing this document:
`spsc-ring-buffer` (tests on both sides) reports `"pass"`; a deliberately
broken Rust assertion in the same lab was caught and reported `"blocked"`
with the real panic message, then reverted and re-verified passing.

## The shared-fixture contract for new/hardened labs

Where a lab's Java and Rust implementations are meant to be semantically
equivalent (not just "both compile"), the correctness suite on each side
should assert against the **same** canonical fixture rather than two
independently-invented expectations that happen to agree by coincidence.
Concretely: a `content/labs/<id>/code/fixtures/*.json` file (or, for a
purely algorithmic lab, a small set of named input/expected-output cases
inlined identically in both `*Test.java` and the Rust `#[test]` block) that
both languages' tests read or hard-code identically. This is a contract for
labs to follow, not a retrofit of the existing 7 projects' domain code —
per `design.md`'s non-goal ("Implement all laboratory code in this change")
and the boundary with the reference-lab hardening changes
(`plab-010`, `plab-021`–`plab-025`) that own each lab's actual Java/Rust
source.

## Pre-benchmark gate usage

Any script or CI job that runs a `full` or `publication` profile benchmark
(`docs/benchmark-profiles.md`) must call the correctness gate first and
abort on `"blocked"`. `scripts/benchmark-platform/run-correctness-gate.js`
exits `1` for exactly this reason, so `&&`-chaining it in front of a
benchmark command is sufficient:

```sh
node scripts/benchmark-platform/run-correctness-gate.js false-sharing && \
  (cd content/labs/false-sharing/code/java && java --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED -jar target/benchmarks.jar -f 2 -wi 3 -w 1s -i 5 -r 1s)
```
