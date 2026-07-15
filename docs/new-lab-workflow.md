# Adding a new lab

This is the checklist for turning a lab idea into a merged lab, given the
foundation established in `plab-001-foundation`. It does not cover writing
the interactive framework code — that's `plab-011-lab-framework` — this
covers structure, metadata, and the quality gates every lab must pass.

## 1. Create the content directory

```
cp -r content/labs/_template content/labs/<lab-id>
```

`<lab-id>` MUST be kebab-case and match the `id` field you set in `lab.json`.
Directory layout is documented in `content/labs/README.md`.

## 2. Fill in `lab.json`

Follow `docs/lab-metadata-schema.md`. Required fields: `id`, `title`,
`status`, `level`, `difficulty`, `durationMinutes`, `topics`,
`prerequisites`, `unlocks`, `languages`, `interactive`, `benchmark`,
`conceptualModel`.

If this lab unlocks another, add it to `unlocks` here AND add this lab's
`id` to the target lab's `prerequisites` — the validator enforces that both
sides agree.

## 3. Write content

- `theory.md` — required.
- `java.md` / `rust.md` — required if that language is in `lab.json#/languages`.
- `benchmark.md` — required if `lab.json#/benchmark` is `true`. Use the
  measured-data disclosure component (`docs/components.md`) and name your
  tool, environment, and method.
- `sources.md` — required. Use the sources component (`docs/components.md`).
- `exercises.md` — required (unified framework contract, `docs/lab-framework.md`):
  at least one diagnosis and one implementation exercise with success
  criteria and collapsed hints/solutions. `theory.md` must open with a
  `## Performance question and hypothesis` section stating what would
  disprove the hypothesis.

## 4. Follow the design contracts

- **Colour, radius, type** — tokens only, see `docs/design-tokens.md`. No
  raw hex values in lab CSS.
- **State display** — use the shared `.state-badge` classes and pair every
  state with text, never colour alone (`docs/semantic-states.md`).
- **Keyboard** — native interactive elements, roving-tabindex for grouped
  controls via `assets/js/core/keyboard.js#initTablist`
  (`docs/keyboard-rules.md`).
- **Motion** — CSS animation is covered by the global reduced-motion rule;
  any `setInterval`/`requestAnimationFrame` loop MUST check
  `assets/js/core/keyboard.js#prefersReducedMotion()` itself
  (`docs/reduced-motion-policy.md`).
- **Any conceptual visualisation** gets a `.disclosure.conceptual` block
  (`docs/components.md`).

## 5. Run the checks locally

```
npm run verify
```

This runs, in order: lab-metadata validation (schema, duplicate IDs,
prerequisites, cycle detection), asset-size budgets, HTML/link validation,
and accessibility smoke checks. Fix everything it reports before opening a
PR — CI (`.github/workflows/ci.yml`) runs the same checks and will block
merge on failure.

## 6. Performance budgets

From `design.md`, enforced by `scripts/check-asset-sizes.js`:

| Asset | Budget |
|---|---|
| Per-lab HTML | 80 KB uncompressed |
| Shared CSS (`assets/css/styles.css`) | 100 KB |
| Shared JS (`assets/js/main.js` + `assets/js/core/**`) | 120 KB |
| Per-lab JS (`assets/js/labs/<lab>.js`) | 80 KB |

No blocking remote fonts. No unreviewed third-party runtime dependency —
this repo's own tooling (`scripts/`) has zero npm dependencies by design;
keep it that way unless there's a strong reason not to.

## 7. Deploy

Merging to `main` triggers `.github/workflows/pages.yml`, which deploys to
GitHub Pages and then runs `scripts/check-pages-smoke.js` against the live
URL to confirm the deploy actually served the site.
