## ADDED Requirements

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
