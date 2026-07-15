# Lab framework

The reusable static framework every full lab (`lab/<lab-id>/index.html`)
builds on. Implemented in `assets/js/core/lab-framework.js` and
`assets/js/core/components.js`. Not a SPA and not a component library with
its own runtime — it's a small set of functions that hydrate server-rendered
HTML, per spec.md's "Progressive enhancement" requirement.

## Semantic shell

Every full lab page follows this section order and hook set. Sections are
plain, fully-readable HTML; the `data-lab-*` attributes are hydration hooks
only — removing all `<script>` tags must not remove any content, only the
interactivity.

```html
<article data-lab-id="false-sharing">
  <header class="lab-header">
    <p class="eyebrow">Cache coherence</p>
    <h1>False Sharing</h1>
    <p class="lead">…</p>
  </header>

  <section class="prose"><h2>Learning objective</h2>…</section>
  <section class="prose"><h2>Prerequisites</h2><ul>…</ul></section>
  <section class="prose"><h2>Theory</h2>…</section>

  <section data-lab-stage>
    <nav data-lab-scenario-nav aria-label="Scenario"></nav>
    <div data-lab-inspector></div>
    <div class="control-row" data-lab-controls></div>
    <p class="sr-only" role="status" aria-live="polite" data-lab-announcer></p>
  </section>

  <section class="prose" data-lab-code-tabs>
    <div role="tablist" aria-label="Language">
      <button role="tab" id="tab-java" aria-controls="panel-java" aria-selected="true">Java</button>
      <button role="tab" id="tab-rust" aria-controls="panel-rust" aria-selected="false" tabindex="-1">Rust</button>
    </div>
    <div role="tabpanel" id="panel-java" aria-labelledby="tab-java"><pre><code>…</code></pre></div>
    <div role="tabpanel" id="panel-rust" aria-labelledby="tab-rust" hidden><pre><code>…</code></pre></div>
  </section>

  <section class="prose"><h2>Benchmark</h2><!-- .disclosure.measured, docs/components.md --></section>
  <section><h2>Trade-offs</h2><!-- .trade-offs, docs/components.md --></section>
  <section><h2>Quiz</h2><div data-lab-quiz></div></section>
  <section class="prose"><h2>Sources</h2><ul class="sources">…</ul></section>
</article>
```

## Progressive enhancement bootstrap

Each lab has one small bootstrap module (`assets/js/labs/<lab-id>.js`,
loaded as `<script type="module">`) that:

1. Imports its own pure reducer(s) — see `assets/js/labs/false-sharing-reducer.js`
   for the pattern: state/reducer/announce with zero DOM access, so they're
   unit-testable on their own (spec.md "Pure transitions").
2. Calls `mountLab(document.querySelector("[data-lab-stage]"), createLabDefinition({ … }))`.
3. Calls `mountCodeTabs(document.querySelector("[data-lab-code-tabs]"))` and
   `renderQuiz(document.querySelector("[data-lab-quiz]"), { … })` as needed.

`mountLab` and every component in `components.js` no-op safely when their
target hook is missing — a lab with no `[data-lab-stage]` (a theory-only
lab, or a page where the bootstrap script failed to load) still renders its
theory, code, benchmark, trade-offs, and sources in full from static HTML.

## API

### `createLabDefinition({ metadata, initialState, reducer, render, events, scenarios, announce })`

- `metadata.id` — required, matches `lab.json#/id`.
- `initialState` — a plain, JSON-serialisable object.
- `reducer(state, event) -> nextState` — pure. Return the *same* state
  reference for a no-op event so `mountLab` can skip re-rendering.
- `render(state, { dispatch, root })` — writes `state` into the DOM under
  `root`. Never mutates `state`.
- `events` — array of the event-type strings this lab's reducer handles.
  Consumed by reducer tests (`scripts/test-reducers.js`), not by the runtime.
- `scenarios` — `[{ id, label }]`, if the lab has named scenarios. Drives
  `renderScenarioSelector` and the `?scenario=` URL parameter.
- `announce(state, event, prevState) -> string | null` — optional; text for
  the `aria-live` region on each state change (`docs/semantic-states.md`).

### `mountLab(root, definition)`

Restores `scenario`/`step` from the URL if present and valid (invalid
scenario IDs are dropped, not applied — spec.md "Invalid scenario safely
falls back"), renders the initial state, and returns `{ getState, dispatch }`.
Every subsequent `dispatch(event)` re-renders, re-syncs the URL, and
announces the change.

### Components (`assets/js/core/components.js`)

| Function | Renders |
|---|---|
| `renderScenarioSelector(nav, { scenarios, current, onSelect })` | A roving-tabindex tablist of scenarios. |
| `renderStepControls(el, { onPrevious, onNext, onReset, canGoBack, canGoForward })` | Previous/Next/Reset buttons. |
| `renderStateInspector(el, fields)` | A `<dl>` text readout of current state — state is never colour/animation-only. |
| `mountCodeTabs(container)` | Hydrates a static Java/Rust tablist: tab switching + a copy-to-clipboard control per panel. |
| `renderQuiz(el, { question, choices, correctIndex })` | A native-radio quiz graded client-side. |
| `renderDisclosure(el, { kind, title, body })` | The `.disclosure` component (`docs/components.md`) for runtime-computed text. |

### Metadata and registry

- `assets/js/core/metadata.js` — `loadLabsIndex()` / `loadLabMetadata(id)`
  fetch the built `assets/data/labs-index.json` (generated by
  `scripts/generate-labs-index.js` from `content/labs/*/lab.json` — `content/`
  itself is source-only and is never deployed).
- `assets/js/core/registry.js` — `mountRegistry(root)` renders and filters
  the lab list. Expects `root` to contain `[data-lab-registry]` (the list
  container), optional `[data-lab-filter="topic|difficulty|status"]` inputs,
  and optionally `[data-lab-registry-empty]` (shown when a filter matches
  nothing).

## Unified content contract (plab-011-unified-lab-framework)

Enforced for every lab by `scripts/validate-labs.js` (a new lab must ship
these from day one):

- `theory.md` opens with `## Performance question and hypothesis` — the
  question, a falsifiable hypothesis, and what would disprove it, before
  any result is shown.
- `exercises.md` exists with at least one diagnosis exercise and one
  implementation exercise, each stating success criteria, hints/solutions
  collapsed in `<details>` blocks separate from the statement.
- When `lab.json#/benchmark` is true: `benchmark.md` carries a
  `## Raw data and reproduction` section and a fenced block of runnable
  reproduction commands.

## Per-lab verification checklist and maturity badge

Result maturity is **derived, never stored** (`scripts/benchmark-platform/
results/evidence-maturity.js`); rendering surfaces call `deriveMaturity`/
`badgeFor` per record. The states a lab's evidence moves through, and what
each requires, are defined in `docs/measurement-environments.md`
(implemented → awaiting-native-linux-measurement → measured → reproduced →
reviewed → verified → published). The per-lab verification checklist before
any "verified" claim is the review checklist in
`docs/linux-evidence-runner.md`: intact hashes, physical-host environment,
passing correctness gate, worker placement within policy, fork-coherent
JMH distributions, repetition-consistent counters, and profiler evidence
supporting the mechanism claim. Developer-workstation results are labeled
"Illustrative development run" in internal artifacts and never render on
canonical public lab pages (`scripts/check-public-content.js`).
