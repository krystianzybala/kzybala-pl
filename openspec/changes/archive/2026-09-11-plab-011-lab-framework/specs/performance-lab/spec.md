## ADDED Requirements

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
