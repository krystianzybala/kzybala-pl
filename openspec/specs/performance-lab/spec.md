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

### Requirement: Cache hierarchy mechanism explained
The cache-hierarchy lab MUST explain the L1/L2/L3/RAM hierarchy, cache lines, spatial and temporal locality, and hardware prefetching as the mechanisms that determine whether an access is served by a fast cache level or by slow main memory.

#### Scenario: Locality distinguished
- **WHEN** a user reads the lab's theory section
- **THEN** it explicitly distinguishes spatial locality (closeness of addresses touched) from temporal locality (how soon a touched address is revisited), with an example of each

#### Scenario: Prefetching explained
- **WHEN** a user reads the lab's theory section
- **THEN** it explains that sequential access lets hardware stride/stream prefetching hide RAM latency, and that random access has no detectable stride for a prefetcher to act on

#### Scenario: No universal cache size or line size claim
- **WHEN** the lab states a cache size, cache-line size, or latency figure
- **THEN** it labels the figure as illustrative/common, not a universal architectural guarantee, and directs readers to query their own hardware

### Requirement: Interactive working-set model
The lab's interactive model MUST let a user step through sequential and random memory access over working sets that fit in L1 and working sets that exceed the simulated cache hierarchy, and observe which cache level served each access as text.

#### Scenario: Four scenarios selectable
- **WHEN** a user opens the interactive model
- **THEN** four scenarios are selectable: sequential access fitting in L1, sequential access exceeding cache, random access fitting in L1, and random access exceeding cache

#### Scenario: Step-by-step access with result shown as text
- **WHEN** a user activates the Next control
- **THEN** the next access in the scenario's pattern is applied, and the resulting cache level (L1/L2/L3 hit or RAM miss) is shown as text, not colour alone

#### Scenario: Hit/miss counts visible
- **WHEN** a user steps through the model
- **THEN** the state inspector shows running L1/L2/L3 hit counts and RAM miss counts for the current run

#### Scenario: Reset exact
- **WHEN** the Reset control is activated
- **THEN** the model returns to the exact same initial state (zero accesses, empty cache) for the current scenario

#### Scenario: Keyboard accessible
- **WHEN** a user operates the model without a pointing device
- **THEN** every control (scenario tabs, step controls) is reachable and operable by keyboard

### Requirement: Cache hierarchy Java example
The cache-hierarchy lab MUST provide a JMH-based Java example demonstrating sequential vs. random memory-access latency over a working set that fits in L1 and one that exceeds the last-level cache.

#### Scenario: Uses JMH
- **WHEN** the Java example is inspected
- **THEN** it is a JMH benchmark reporting average time per batch of pointer-chase accesses

#### Scenario: Defeats the optimizer
- **WHEN** the Java example is inspected
- **THEN** it uses a dependent pointer-chase access chain (not a plain independent-index loop) so the JIT cannot reorder, vectorize, or eliminate the memory-access pattern being measured

#### Scenario: Small and large working sets
- **WHEN** the Java example is inspected
- **THEN** it includes both a working set sized to fit comfortably in L1 and one sized to exceed any consumer last-level cache

### Requirement: Cache hierarchy Rust example
The cache-hierarchy lab MUST provide a Criterion-based Rust example demonstrating the same sequential-vs-random, small-vs-large comparison as the Java example.

#### Scenario: Uses Criterion
- **WHEN** the Rust example is benchmarked
- **THEN** it uses the Criterion benchmarking crate

#### Scenario: Defeats the optimizer
- **WHEN** the Rust example is inspected
- **THEN** it uses `black_box` around the pointer-chase table and result to prevent the compiler from const-folding or eliminating the access chain

#### Scenario: Single-cycle random permutation documented
- **WHEN** the Rust example builds its random access order
- **THEN** it documents why a single-cycle permutation (Sattolo's algorithm) is used instead of a plain shuffle, which can produce short sub-cycles

### Requirement: Cache hierarchy honest results
Benchmark data MUST be clearly disclosed and simulated data MUST be clearly labelled.

#### Scenario: Simulated model labelled
- **WHEN** the interactive model is shown
- **THEN** it carries a `.disclosure.conceptual` block stating it is a simplified model with illustrative cache capacities, not a cycle-accurate simulation

#### Scenario: Measured results include environment
- **WHEN** benchmark results are shown
- **THEN** they carry a `.disclosure.measured` block naming the tool, hardware, OS, runtime, warm-up and measurement configuration

#### Scenario: Charts include units and uncertainty or distribution
- **WHEN** the benchmark tables are shown
- **THEN** they state time units and a confidence interval or min/max spread across repeated runs, not a single bare number

### Requirement: Cache hierarchy educational completion
The cache-hierarchy lab MUST include common mistakes, usage guidance, an investigation task, review questions, and sources.

#### Scenario: Common mistakes
- **WHEN** a user reads the lab
- **THEN** a "Common mistakes" section lists specific, concrete missteps

#### Scenario: When access pattern matters
- **WHEN** a user reads the trade-offs section
- **THEN** it states concrete conditions under which optimizing memory-access pattern has high payoff

#### Scenario: When access pattern matters less
- **WHEN** a user reads the trade-offs section
- **THEN** it states concrete conditions under which optimizing memory-access pattern has low payoff

#### Scenario: Investigation task
- **WHEN** a user reads the lab
- **THEN** an investigation task gives concrete steps to reproduce and measure the effect using the provided code, including adapting it to the reader's own cache sizes

#### Scenario: At least three review questions
- **WHEN** a user reads the lab
- **THEN** it lists at least three review questions for self-study

#### Scenario: Sources
- **WHEN** a user reads the lab
- **THEN** a sources list using the shared `.sources` component cites authoritative references covering hardware, JVM, and Rust claims

### Requirement: Cache coherence mechanism explained
The mesi lab MUST explain cache coherence as a mechanism ensuring a coherent view of a cache line across participating cores, distinct from memory consistency and memory ordering.

#### Scenario: Distinguishes consistency
- **WHEN** a user reads the lab's theory section
- **THEN** it explicitly distinguishes cache coherence (per-location agreement) from memory consistency (cross-location ordering guarantees)

#### Scenario: Distinguishes memory ordering
- **WHEN** a user reads the lab's theory section
- **THEN** it explicitly distinguishes coherence from memory ordering (fences, `volatile`, `Acquire`/`Release`) as the mechanism used to constrain a consistency model

#### Scenario: Ownership, sharing, invalidation, write-back explained
- **WHEN** a user reads the lab's theory section
- **THEN** it explains ownership, sharing, invalidation, and write-back in terms a reader can map onto the interactive model's transitions

#### Scenario: No universal protocol claim
- **WHEN** the lab describes MESI
- **THEN** it avoids claiming MESI is the coherence protocol every processor implements

### Requirement: Interactive MESI state transitions
The mesi lab's interactive model MUST expose explicit four-state (Modified/Exclusive/Shared/Invalid) transitions for two simulated CPUs sharing one conceptual memory line.

#### Scenario: Both CPUs can read, write, and evict
- **WHEN** a user activates a CPU's read, write, or evict control
- **THEN** the corresponding MESI transition is applied and rendered, including ownership transfer where applicable

#### Scenario: State visible in text
- **WHEN** the interactive model's state changes
- **THEN** each CPU's MESI state and cached value are shown as text, not colour alone

#### Scenario: Invalidations and write-backs counted
- **WHEN** a transition invalidates another CPU's copy or writes a dirty value back to memory
- **THEN** the corresponding counter in the state inspector increments

#### Scenario: Reset exact
- **WHEN** the Reset control is activated
- **THEN** the model returns to the exact same initial state (both lines Invalid, zero counters) for the current scenario

#### Scenario: Keyboard accessible
- **WHEN** a user operates the model without a pointing device
- **THEN** every control (scenario tabs, read/write/evict buttons, step controls) is reachable and operable by keyboard

### Requirement: Protocol caveats
The mesi lab MUST explain that MESI is a teaching model, not a universal implementation contract.

#### Scenario: Mentions protocol variants
- **WHEN** a user reads the "caveats" section
- **THEN** it mentions MESIF, MOESI, and directory-based coherence as real-world variants beyond the four-state model shown

#### Scenario: Avoids claiming a specific chip's protocol
- **WHEN** the lab discusses ARM or Apple Silicon
- **THEN** it states that the exact coherence protocol is unpublished/implementation-specific rather than asserting the displayed model matches it

#### Scenario: Labels the visualisation conceptual
- **WHEN** the interactive model is shown
- **THEN** it carries a `.disclosure.conceptual` block stating the model is conceptual, not a cycle-accurate simulation of any specific processor

### Requirement: Java and Rust coherence examples
The mesi lab MUST include buildable examples showing a shared-writer pattern and a single-writer ownership pattern in both languages.

#### Scenario: Java example compiles
- **WHEN** the Java example project is built
- **THEN** it compiles and its tests pass

#### Scenario: Rust example compiles
- **WHEN** the Rust example project is built
- **THEN** it compiles and its tests pass

#### Scenario: Memory-ordering choices documented
- **WHEN** the Rust example is inspected
- **THEN** it documents why `Ordering::Relaxed` is sufficient for the shared-writer counter

#### Scenario: No direct coherence-state control claimed
- **WHEN** either language example is inspected
- **THEN** it states that the example cannot directly observe or force a MESI state from source code

### Requirement: Coherence diagnostic methodology
The mesi lab MUST explain how coherence traffic can be investigated on supported Linux systems, without presenting a synthetic benchmark as universal.

#### Scenario: perf c2c methodology included
- **WHEN** a user reads the "Diagnostic methodology" section
- **THEN** it explains how `perf c2c` identifies cross-core cache-to-cache (HITM) traffic and gives example commands

#### Scenario: Hardware and OS caveats included
- **WHEN** a user reads the "Diagnostic methodology" section
- **THEN** it states that `perf c2c` is Linux-specific, requires PMU access, and that event availability varies by microarchitecture and kernel version

#### Scenario: No universal benchmark claim
- **WHEN** a user reads the "Diagnostic methodology" section
- **THEN** it explicitly avoids presenting a synthetic "MESI benchmark" as a portable performance number

#### Scenario: Investigation task
- **WHEN** a user reads the lab
- **THEN** an investigation task gives concrete steps to reproduce and observe coherence traffic using the provided code and `perf c2c`

### Requirement: MESI educational completion
The mesi lab MUST include common mistakes, usage guidance, an investigation task, review questions, and sources.

#### Scenario: Common mistakes
- **WHEN** a user reads the lab
- **THEN** a "Common mistakes" section lists specific, concrete missteps

#### Scenario: When coherence reasoning matters
- **WHEN** a user reads the trade-offs section
- **THEN** it states concrete conditions under which reasoning about coherence traffic has high payoff

#### Scenario: When it is not the bottleneck
- **WHEN** a user reads the trade-offs section
- **THEN** it states concrete conditions under which coherence is unlikely to be the dominant cost

#### Scenario: At least three review questions
- **WHEN** a user reads the lab
- **THEN** it lists at least three review questions for self-study

#### Scenario: Sources
- **WHEN** a user reads the lab
- **THEN** a sources list using the shared `.sources` component cites authoritative references covering hardware and language-level claims

### Requirement: Memory-ordering mechanism explained
The memory-ordering lab MUST explain why source order does not guarantee cross-thread observation order, distinguishing visibility, atomicity, and ordering as separate guarantees.

#### Scenario: Program order vs. observed order
- **WHEN** a user reads the lab's theory section
- **THEN** it explains that compiler and CPU reordering can legally separate program order from another thread's observed order

#### Scenario: Visibility, atomicity, ordering distinguished
- **WHEN** a user reads the lab's theory section
- **THEN** it explains visibility, atomicity, and ordering as three independent guarantees, with an example operation for each combination

#### Scenario: happens-before and data races explained
- **WHEN** a user reads the lab's theory section
- **THEN** it defines happens-before and defines a data race in terms of it

### Requirement: Interactive litmus-test model
The memory-ordering lab's interactive model MUST expose deterministic, steppable scenarios for message-passing publication and store buffering.

#### Scenario: Broken and fixed publication both steppable
- **WHEN** a user steps through the "Broken publication" and "Release/acquire message passing" scenarios
- **THEN** each thread's buffered writes, the shared memory values, and the observed reads are shown as text at every step

#### Scenario: Store-buffering outcome togglable by ordering
- **WHEN** a user toggles the ordering selector between Relaxed and SeqCst on the "Store buffering" scenario
- **THEN** the scenario re-derives and shows the resulting outcome for the selected ordering, including whether the classic both-see-0 result occurred

#### Scenario: happens-before edges shown
- **WHEN** an acquire (or SeqCst) read observes a value published by a release (or SeqCst) write
- **THEN** a synchronizes-with / happens-before edge is added to a visible happens-before list

#### Scenario: Reset exact
- **WHEN** the Reset control is activated
- **THEN** the model returns to the exact same initial state for the current scenario and ordering

#### Scenario: Keyboard accessible
- **WHEN** a user operates the model without a pointing device
- **THEN** every control (scenario tabs, ordering toggle, step controls) is reachable and operable by keyboard

### Requirement: Java VarHandle coverage
The memory-ordering lab MUST cover plain, opaque, acquire/release, and volatile VarHandle access modes with buildable examples.

#### Scenario: Buildable Java examples
- **WHEN** the Java example project is built
- **THEN** it compiles and its tests pass

#### Scenario: Correct publication example
- **WHEN** the Java example is inspected
- **THEN** it includes both a plain-access publication example showing the broken outcome is possible and a release/acquire example that is reliably correct

#### Scenario: No claim that weaker modes are always faster
- **WHEN** a user reads the trade-offs section
- **THEN** it states concrete conditions favoring weaker vs. stronger orderings, not a blanket performance claim

### Requirement: Rust atomic coverage
The memory-ordering lab MUST cover Relaxed, Acquire, Release, AcqRel, and SeqCst Rust atomic orderings with buildable examples.

#### Scenario: Buildable Rust examples
- **WHEN** the Rust example project is built
- **THEN** it compiles and its tests pass

#### Scenario: Ordering choices documented
- **WHEN** the Rust example is inspected
- **THEN** it documents why each chosen ordering is sufficient for the operation it's applied to

#### Scenario: No undefined-behaviour example presented as valid
- **WHEN** the Rust example is inspected
- **THEN** every example given as correct is free of undefined behaviour under the documented ordering choice

### Requirement: Litmus scenario honesty
The memory-ordering lab MUST label its litmus scenarios as conceptual and avoid claiming to reproduce a specific CPU.

#### Scenario: Scenarios labelled conceptual
- **WHEN** the interactive model is shown
- **THEN** it carries a `.disclosure.conceptual` block stating it fixes one illustrative interleaving per scenario, not a cycle-accurate simulation

#### Scenario: Allowed and forbidden outcomes explained
- **WHEN** a user reads the store-buffering scenario's description
- **THEN** it explains which outcome is allowed under Relaxed and which is forbidden under SeqCst

### Requirement: Memory-ordering myth correction
The memory-ordering lab MUST explicitly reject common myths about acquire/release, volatile, atomics, and SeqCst.

#### Scenario: Cache-flush myth rejected
- **WHEN** a user reads the myths section
- **THEN** it states that acquire/release does not "flush the cache" and explains what it actually guarantees

#### Scenario: Volatile-instantaneous myth rejected
- **WHEN** a user reads the myths section
- **THEN** it states that volatile provides ordering via happens-before, not global instantaneity

#### Scenario: Atomics-imply-correct-compound-algorithm myth rejected
- **WHEN** a user reads the myths section
- **THEN** it states that a compound operation built from separate atomic steps is not itself atomic, with a concrete counter-example

#### Scenario: SeqCst-required-everywhere myth rejected
- **WHEN** a user reads the myths section
- **THEN** it states concrete cases where a weaker ordering is sufficient and cheaper

### Requirement: CAS mechanism explained
The cas-contention lab MUST explain compare-and-set as a conditional atomic update, distinct from a plain store.

#### Scenario: CAS semantics explained
- **WHEN** a user reads the lab's theory section
- **THEN** it explains that CAS atomically replaces a value only if it matches an expected value, and reports success or failure

#### Scenario: Retry loop explained
- **WHEN** a user reads the lab's theory section
- **THEN** it shows the standard read-compute-CAS-or-retry loop shape that every CAS-based algorithm is built around

### Requirement: Contention model
The cas-contention lab's interactive model MUST show how retry loops amplify coherence traffic and latency under contention.

#### Scenario: Contention collapse demonstrated
- **WHEN** a user compares the "Two contenders" and "Many contenders" scenarios
- **THEN** the state inspector shows a higher failure/retry rate for more contenders, and the theory section names this "contention collapse"

#### Scenario: Ownership transfers counted
- **WHEN** a successful CAS changes which contender most recently succeeded
- **THEN** an ownership-transfer counter increments, tying the effect to cache-line ping-pong

#### Scenario: Keyboard accessible
- **WHEN** a user operates the model without a pointing device
- **THEN** every control (scenario tabs, step controls) is reachable and operable by keyboard

### Requirement: Backoff comparison
The cas-contention lab MUST compare no backoff, fixed backoff, exponential backoff, and jitter.

#### Scenario: Fixed and exponential backoff both reduce wasted retries
- **WHEN** a user compares "Fixed backoff" and "Exponential backoff" against "Many contenders" (no backoff)
- **THEN** both backoff scenarios show fewer failed CAS attempts than the no-backoff baseline at the same contender count

#### Scenario: Jitter explained
- **WHEN** a user reads the backoff section
- **THEN** it explains jitter's purpose (preventing contenders who failed together from retrying in lockstep again) using a reproducible, non-random mechanism

### Requirement: Single-writer alternative
The cas-contention lab MUST compare CAS contention with a single-writer ownership model.

#### Scenario: Single-writer scenario has zero contention
- **WHEN** a user selects the "Single-writer comparison" scenario
- **THEN** every attempt succeeds immediately, with zero failures and zero ownership transfers

#### Scenario: Not presented as universally better
- **WHEN** a user reads the single-writer section
- **THEN** it states the trade-off a single-writer design makes, not just its throughput advantage

### Requirement: CAS Java and Rust coverage
The cas-contention lab MUST include buildable Java and Rust examples and comparable benchmark methodology.

#### Scenario: Buildable examples
- **WHEN** the Java and Rust example projects are built
- **THEN** each compiles and its tests pass

#### Scenario: JMH and Criterion benchmarks disclosed
- **WHEN** a user reads the benchmark methodology section
- **THEN** it discloses hardware, OS, runtime/toolchain versions, and measurement configuration for both languages, per the shared `.disclosure.measured` component

#### Scenario: Contention collapse shown as measured data
- **WHEN** a user reads the benchmark table
- **THEN** it shows throughput at multiple contender counts in both languages, with the trend (not a cross-language absolute comparison) as the stated takeaway

### Requirement: CAS caveats
The cas-contention lab MUST explain fairness, starvation risk, ABA at a conceptual level, and workload sensitivity.

#### Scenario: ABA explained conceptually
- **WHEN** a user reads the ABA section
- **THEN** it explains that a successful CAS proves the value currently matches, not that nothing changed in between, without presenting a complete ABA solution

#### Scenario: Fairness and starvation risk stated
- **WHEN** a user reads the fairness section
- **THEN** it states that a CAS retry loop makes no fairness promise and starvation is possible in principle

#### Scenario: No universal backoff claim
- **WHEN** a user reads the trade-offs section
- **THEN** it states concrete conditions under which backoff does not help or actively hurts
