# Shared static components

Presentational HTML/CSS patterns available to every lab (defined in
`assets/css/styles.css`). These are markup + CSS contracts only — the
interactive framework that mounts them (`createLabDefinition`, `mountLab`)
is out of scope for this change and lands in `plab-011-lab-framework`.

## Benchmark disclosure

Enforces spec.md's "Honest benchmark disclosure" requirement: conceptual
and measured data must never look the same.

```html
<div class="disclosure conceptual">
  <p class="disclosure-kind">Conceptual model</p>
  <p>This visualisation is a simplified mental model, not a cycle-accurate
  simulation or a measured trace.</p>
</div>

<div class="disclosure measured">
  <p class="disclosure-kind">Measured</p>
  <p>JMH 1.37, JDK 21, Apple M2 Pro, 5 warmup + 10 measurement iterations
  (ops/s, higher is better). Single machine, not a cluster benchmark.</p>
</div>
```

Rules:
- Every interactive visualisation gets a `.disclosure.conceptual` block
  unless it is driven by real measured data.
- Every chart or number sourced from a benchmark gets a `.disclosure.measured`
  block naming the tool, environment, and method — and the chart itself
  must show units and scope, not just a bare number.
- A lab may show both (a conceptual visualisation next to a measured
  benchmark chart) — never blend the two in one block.

## Sources

```html
<ul class="sources">
  <li><cite>Java Concurrency in Practice</cite> — Goetz et al.</li>
  <li><cite>Intel 64 and IA-32 Architectures SDM, Vol. 3A §11</cite> — <a href="https://www.intel.com/sdm" rel="noopener">intel.com/sdm</a></li>
</ul>
```

Every lab that makes a factual claim about hardware/runtime behaviour MUST
list its sources with this component, per spec.md's honesty requirement.

## Trade-offs

```html
<div class="trade-offs">
  <div class="use">
    <h4>Use padding when</h4>
    <ul><li>Counters are hot, independently written, by different threads.</li></ul>
  </div>
  <div class="avoid">
    <h4>Avoid padding when</h4>
    <ul><li>Fields are read-mostly, or already on separate cache lines.</li></ul>
  </div>
</div>
```

Every lab that recommends a technique MUST show both when to use it and when
not to, side by side — never guidance without its limits.

## Interactive framework components

`assets/js/core/lab-framework.js` and `assets/js/core/components.js`
(from `plab-011-lab-framework`) provide the JS-driven counterparts to the
static patterns above — `createLabDefinition`/`mountLab` for state, and
`renderScenarioSelector`, `renderStepControls`, `renderStateInspector`,
`mountCodeTabs`, `renderQuiz`, `renderDisclosure` for the interactive
pieces. See `docs/lab-framework.md` for the full contract and the markup
shape each one expects to hydrate.
