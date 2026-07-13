# Performance Lab Product Specification

## Purpose

Performance Lab is an interactive learning platform for runtime engineering, JVM performance, Rust systems programming, CPU architecture, memory, concurrency, Linux, and low-latency system design.

## Product principles

1. Measure before optimising.
2. Explain the mechanism, not only the outcome.
3. Distinguish conceptual visualisations from real measurements.
4. Present Java and Rust side by side where useful.
5. Show trade-offs and failure modes.
6. Remain statically deployable to GitHub Pages.
7. Keep core content usable without JavaScript.
8. Prefer deterministic state over decorative animation.
9. Never present fabricated performance data as measured results.
10. Every laboratory states its limitations.

## Standard laboratory structure

Every complete laboratory MUST include:

1. Learning objective
2. Prerequisites
3. Theory
4. Interactive visualisation
5. Java example
6. Rust example where applicable
7. Benchmark methodology
8. Results or clearly labelled sample data
9. Common mistakes
10. When to use
11. When not to use
12. Quiz or investigation task
13. Accessibility notes
14. Sources and further reading

## Quality gates

- Static export works on GitHub Pages.
- No JavaScript console errors.
- Full keyboard navigation.
- `prefers-reduced-motion` respected.
- Lighthouse targets >= 95 for Performance, Accessibility, Best Practices, SEO.
- No misleading absolute performance claims.
- Code examples compile in declared toolchains.
- Benchmark claims include hardware, runtime, warm-up, measurement and methodology.

## Requirements

### Requirement: Static-first delivery
Performance Lab MUST remain deployable to GitHub Pages with no server-side process.

#### Scenario: No server process
- **WHEN** Performance Lab is deployed
- **THEN** it runs entirely as static files, with no server-side process required

#### Scenario: Root-relative links resolve
- **WHEN** a user follows any link within the site
- **THEN** root-relative links resolve correctly with no build-time base-path rewriting

#### Scenario: Deployment from main succeeds
- **WHEN** a change is pushed to `main`
- **THEN** the GitHub Pages deployment succeeds and the smoke check confirms the live site responds

### Requirement: Shared metadata
Every lab MUST provide valid machine-readable metadata via `lab.json`.

#### Scenario: Required fields exist
- **WHEN** a lab's `lab.json` is validated
- **THEN** it contains all required fields (`id`, `title`, `status`, `level`, `difficulty`, `durationMinutes`, `topics`, `prerequisites`, `unlocks`, `languages`, `interactive`, `benchmark`, `conceptualModel`) with correct types

#### Scenario: Duplicate IDs fail CI
- **WHEN** two labs declare the same `id`
- **THEN** CI fails with a duplicate-ID error

#### Scenario: Invalid prerequisites fail CI
- **WHEN** a lab's `prerequisites` or `unlocks` reference an `id` that does not exist, or the references form a cycle
- **THEN** CI fails with a validation error

### Requirement: Deterministic state
Every interactive lab MUST use explicit, named state transitions.

#### Scenario: Initial state is serialisable
- **WHEN** a lab is mounted
- **THEN** its initial state can be serialised to JSON

#### Scenario: Events are named
- **WHEN** a user interacts with a lab's controls
- **THEN** the interaction dispatches a named event, not an ad-hoc DOM mutation

#### Scenario: Reducers are unit-testable
- **WHEN** a lab defines a reducer
- **THEN** the reducer is a pure function of `(state, event) -> nextState` callable outside the DOM

#### Scenario: Reset is deterministic
- **WHEN** a lab's reset control is activated
- **THEN** the lab returns to the exact same initial state every time

### Requirement: Accessibility baseline
Every lab MUST work without a pointing device.

#### Scenario: Logical focus order
- **WHEN** a user tabs through a lab
- **THEN** focus order follows the visual/reading order, and grouped controls (tabs, scenario selectors) use roving tabindex per `docs/keyboard-rules.md`

#### Scenario: State changes are announced
- **WHEN** a lab's state changes as a result of user interaction
- **THEN** an `aria-live` region announces a textual description of the change per `docs/semantic-states.md`

#### Scenario: Reduced motion is supported
- **WHEN** the user's system requests `prefers-reduced-motion: reduce`
- **THEN** the lab skips or shortens all animation, including JavaScript-driven animation, per `docs/reduced-motion-policy.md`, without losing any information

### Requirement: Honest benchmark disclosure
Measured and simulated data MUST be clearly and separately distinguished.

#### Scenario: Conceptual values are labelled
- **WHEN** a lab shows a conceptual or simulated visualisation
- **THEN** it is marked with the `.disclosure.conceptual` component per `docs/components.md`

#### Scenario: Measured values include environment and method
- **WHEN** a lab shows real benchmark data
- **THEN** it is marked with the `.disclosure.measured` component, naming the tool, environment, and method

#### Scenario: Charts include units and scope
- **WHEN** a lab renders a chart of benchmark results
- **THEN** the chart states its units and the scope of what was measured (e.g. single machine vs. cluster)

### Requirement: Reusable shell
All full labs MUST use one shared shell (`assets/js/core/lab-framework.js`, `assets/js/core/components.js`).

#### Scenario: Shared metadata rendering
- **WHEN** a full lab page is built
- **THEN** its header renders from `lab.json` metadata rather than hand-authored per-lab markup

#### Scenario: Shared ordering
- **WHEN** a full lab page is built
- **THEN** it follows the section order defined in `docs/lab-framework.md` (header, objective, prerequisites, theory, interactive stage, code, benchmark, trade-offs, quiz, sources)

#### Scenario: Shared reset and navigation
- **WHEN** a lab exposes step or scenario controls
- **THEN** it uses `renderStepControls`/`renderScenarioSelector` rather than a one-off implementation

#### Scenario: Shared announcer
- **WHEN** a lab's state changes
- **THEN** the change is announced through `createAnnouncer`'s single `aria-live` region, not a lab-specific one

### Requirement: Pure transitions
State transitions MUST be independent of DOM rendering.

#### Scenario: Reducer returns serialisable state
- **WHEN** a lab's reducer processes an event
- **THEN** the returned state is a plain, JSON-serialisable object with no DOM references

#### Scenario: All events unit-tested
- **WHEN** a lab defines its `events` list
- **THEN** every event type has a corresponding test in `scripts/test-reducers.js`-style coverage

#### Scenario: Reset deterministic
- **WHEN** `RESET` is dispatched from any reachable state
- **THEN** the reducer returns exactly the lab's `initialState`

### Requirement: Progressive enhancement
Core content MUST remain readable without JavaScript.

#### Scenario: Theory, code and sources in HTML
- **WHEN** JavaScript fails to load or is disabled
- **THEN** a lab's theory, code samples, and sources are still present and readable in the raw HTML, verified by `scripts/check-no-js-fallback.js`

#### Scenario: JavaScript adds interaction only
- **WHEN** `mountLab` or any component in `components.js` is called with a missing target hook
- **THEN** it no-ops rather than throwing or hiding static content

### Requirement: Java and Rust code
The framework MUST support accessible Java/Rust code tabs.

#### Scenario: Keyboard accessible
- **WHEN** a user navigates code-language tabs by keyboard
- **THEN** `mountCodeTabs` provides the same roving-tabindex behaviour as any other tablist (`docs/keyboard-rules.md`)

#### Scenario: Language announced
- **WHEN** a user switches the active code-language tab
- **THEN** the change is announced via the shared `aria-live` region

#### Scenario: Copy control available
- **WHEN** a code panel is shown
- **THEN** it has a working "Copy" control that copies that panel's code to the clipboard

### Requirement: Scenario deep links
Labs MUST support stable named-scenario links.

#### Scenario: Invalid scenario safely falls back
- **WHEN** a lab is loaded with `?scenario=` set to an unknown ID
- **THEN** `parseUrlState` discards it and the lab mounts with its default `initialState`, not a crash or blank page

#### Scenario: Refresh preserves selection
- **WHEN** a user reloads a lab page with `?scenario=<id>&step=<n>` in the URL
- **THEN** the lab mounts with that scenario and step restored

### Requirement: Benchmark disclosure
Every benchmark MUST disclose hardware, OS, runtime, warm-up, duration, samples, units, and limitations.

#### Scenario: Runtime-computed disclosure uses the shared component
- **WHEN** a lab renders benchmark results computed at runtime
- **THEN** it uses `renderDisclosure(el, { kind: "measured", ... })` (`docs/components.md`) rather than inline markup

#### Scenario: Authored disclosure follows the same contract
- **WHEN** a lab's `benchmark.md` is authored directly
- **THEN** it uses the `.disclosure.measured` markup pattern and names the tool, environment, and method

### Requirement: False sharing mechanism explained
The false-sharing lab MUST explain false sharing as coherence traffic caused by independent mutable data sharing one coherence unit, distinct from data races and lock contention.

#### Scenario: Distinguishes data races
- **WHEN** a user reads the lab's theory section
- **THEN** it explicitly explains how false sharing differs from a data race in both cause and fix

#### Scenario: Distinguishes lock contention
- **WHEN** a user reads the lab's theory section
- **THEN** it explicitly explains how false sharing differs from lock contention in both cause and observable symptom

#### Scenario: No universal cache-line claim
- **WHEN** the lab states a cache-line size
- **THEN** it labels 64 bytes as a common example, not a universal architectural guarantee

### Requirement: Interactive shared-line scenario
The lab's interactive model MUST let a user trigger writes/reads from either simulated CPU and observe the resulting coherence state as text.

#### Scenario: Both CPUs can write
- **WHEN** a user activates the CPU 0 write or CPU 1 write control
- **THEN** the corresponding cache-line transition is applied and rendered

#### Scenario: State visible in text
- **WHEN** the interactive model's state changes
- **THEN** each CPU's line state and last operation are shown as text, not colour alone

#### Scenario: Invalidation count visible
- **WHEN** a write causes an invalidation
- **THEN** the invalidation counter shown in the state inspector increments

#### Scenario: Reset exact
- **WHEN** the Reset control is activated
- **THEN** the model returns to the exact same initial state for the current scenario

#### Scenario: Keyboard accessible
- **WHEN** a user operates the model without a pointing device
- **THEN** every control (scenario tabs, write/read buttons, step controls) is reachable and operable by keyboard

### Requirement: Padded comparison
The lab MUST let a user compare a shared-line layout against a padded layout and understand the trade-off, without claiming padding always helps.

#### Scenario: Shared and padded layouts selectable
- **WHEN** a user selects the "Padded line" scenario
- **THEN** the model switches to a layout where each CPU's counter has its own coherence unit

#### Scenario: Separate coherence units shown
- **WHEN** the padded scenario is active
- **THEN** writes from one CPU never invalidate the other CPU's line, and the state inspector reflects zero invalidations/transfers regardless of activity

#### Scenario: Memory-footprint trade-off explained
- **WHEN** a user reads the "When to use padding" section
- **THEN** it states the memory-footprint and cache-density cost of padding, not only its benefit

#### Scenario: No claim padding always helps
- **WHEN** a user reads the trade-offs section
- **THEN** it lists specific conditions under which padding does not help or actively hurts

### Requirement: Java example
The lab MUST provide a JMH-based Java example demonstrating shared, padded, and `@Contended` counter layouts.

#### Scenario: Uses JMH
- **WHEN** the Java example is inspected
- **THEN** it is a JMH benchmark using `@Group`/`Scope.Group` to force concurrent execution of the paired writes

#### Scenario: Shared and padded variants
- **WHEN** the Java example is inspected
- **THEN** it includes both a shared-layout counter class and a manually padded counter class

#### Scenario: Prevents elimination
- **WHEN** the Java example is inspected
- **THEN** it documents the risk of dead-field elimination for manual padding and recommends `@Contended` where JVM flags are controlled

#### Scenario: Documents `@Contended` flags
- **WHEN** the Java example uses `@Contended`
- **THEN** it documents the required `--add-exports` compiler/runtime flag and the `-XX:-RestrictContended` JVM flag

#### Scenario: Documents thread assignment
- **WHEN** the JMH benchmark is inspected
- **THEN** it documents how JMH assigns one thread per `@Benchmark` method within a `@Group`

### Requirement: Rust example
The lab MUST provide a Criterion-based Rust example demonstrating adjacent and cache-line-aligned atomic layouts.

#### Scenario: Uses atomics
- **WHEN** the Rust example is inspected
- **THEN** it uses `AtomicU64` counters incremented with `fetch_add`

#### Scenario: Adjacent and aligned variants
- **WHEN** the Rust example is inspected
- **THEN** it includes both a `#[repr(C)]` adjacent-atomics struct and a `#[repr(align(64))]` cache-line-aligned wrapper

#### Scenario: Uses Criterion or equivalent
- **WHEN** the Rust example is benchmarked
- **THEN** it uses the Criterion benchmarking crate with concurrent producer threads

#### Scenario: Documents ordering and alignment assumptions
- **WHEN** the Rust example is inspected
- **THEN** it documents why `Ordering::Relaxed` is sufficient and states the assumed 64-byte cache-line size explicitly as an assumption, not a guarantee

### Requirement: Honest results
Benchmark data MUST be clearly disclosed and simulated data MUST be clearly labelled.

#### Scenario: Simulated counters labelled
- **WHEN** the interactive model is shown
- **THEN** it carries a `.disclosure.conceptual` block stating it is a simplified model, not a cycle-accurate simulation

#### Scenario: Measured results include environment
- **WHEN** benchmark results are shown
- **THEN** they carry a `.disclosure.measured` block naming the tool, hardware, OS, runtime, warm-up and measurement configuration

#### Scenario: Charts include units and uncertainty or distribution
- **WHEN** the benchmark table is shown
- **THEN** it states throughput units and a min–max spread across repeated runs, not a single bare number

### Requirement: Educational completion
The lab MUST include common mistakes, usage guidance, an investigation task, review questions, and sources.

#### Scenario: Common mistakes
- **WHEN** a user reads the lab
- **THEN** a "Common mistakes" section lists specific, concrete missteps

#### Scenario: When to use
- **WHEN** a user reads the trade-offs section
- **THEN** it states concrete conditions under which padding is worth applying

#### Scenario: When not to use
- **WHEN** a user reads the trade-offs section
- **THEN** it states concrete conditions under which padding is not worth applying

#### Scenario: Investigation task
- **WHEN** a user reads the lab
- **THEN** an investigation task gives concrete steps to reproduce and measure the effect using the provided code

#### Scenario: At least three review questions
- **WHEN** a user reads the lab
- **THEN** it lists at least three review questions for self-study

#### Scenario: Sources
- **WHEN** a user reads the lab
- **THEN** a sources list using the shared `.sources` component cites authoritative references covering hardware, JVM, and Rust claims
