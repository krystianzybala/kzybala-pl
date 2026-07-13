# Lab content directory

Each lab lives in its own directory named after its `id`:

```text
content/labs/<lab-id>/
  lab.json       required — see docs/lab-metadata-schema.md
  theory.md      required
  java.md        required if "java" is in lab.json#/languages
  rust.md        required if "rust" is in lab.json#/languages
  benchmark.md   required if lab.json#/benchmark is true
  sources.md     required
```

Directories starting with `_` (such as `_template`) are ignored by every
validator and by the site build. Copy `_template` when starting a new lab —
see `docs/new-lab-workflow.md`.
