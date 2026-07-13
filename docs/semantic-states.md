# Shared semantic states

Interactive labs render runtime state (cache-line status, buffer occupancy,
compilation tier, etc.) as text plus one of a small, shared set of state
classes — never as colour alone (spec.md, "No colour-only meaning").

Defined in `assets/css/styles.css`:

| Class | Meaning | Example use |
|---|---|---|
| `.state-badge.is-idle` | Nothing has happened yet / at rest. | Cache line not yet touched. |
| `.state-badge.is-active` | Currently in progress / the interesting state. | "Modified" cache line, JIT tier currently compiling. |
| `.state-badge.is-warning` | Valid but suboptimal; worth noticing. | False sharing detected, ring buffer near capacity. |
| `.state-badge.is-danger` | Invalid or failed. | "Invalid" cache line, buffer overflow attempted. |
| `.state-badge.is-success` | Completed / optimal outcome reached. | Padding applied, contention eliminated. |

```html
<span class="state-badge is-active">Modified</span>
```

## Announcing state changes

Every state change a lab's reducer produces MUST also update an
`aria-live="polite"` region so screen-reader users get the same signal
sighted users get from the badge colour/label change:

```html
<p class="sr-only" role="status" aria-live="polite" data-lab-announcer></p>
```

Set its text content (not innerHTML) to a short, complete sentence
describing what changed, e.g. `"CPU 1 cache line invalidated."` — not just
the new state name in isolation. `.sr-only` (also defined in
`assets/css/styles.css`) hides the region visually without removing it from
the accessibility tree.
