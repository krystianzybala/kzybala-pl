# Reduced-motion policy

Every lab MUST support `prefers-reduced-motion: reduce` (spec.md,
"Accessibility baseline").

1. **CSS animation/transition** — covered globally. `assets/css/styles.css`
   collapses all `animation-duration`, `animation-iteration-count`, and
   `transition-duration` to effectively zero under
   `@media (prefers-reduced-motion: reduce)`. A lab's own CSS does not need
   to repeat this; it only needs to avoid `!important` overrides that could
   defeat it.
2. **JavaScript-driven animation is NOT covered by the CSS rule above** and
   MUST check the preference itself before starting anything that loops,
   auto-advances, or animates without user input (`setInterval`,
   `requestAnimationFrame` loops, scroll-triggered replays). Use
   `assets/js/core/keyboard.js#prefersReducedMotion()` and re-check on
   `matchMedia("(prefers-reduced-motion: reduce)").addEventListener("change", …)`
   since the preference can change after load.
3. **User-triggered, single-step transitions** (e.g. clicking "advance
   compilation state") are exempt from the *auto-play* restriction but MUST
   still resolve instantly (no animated motion) under reduced motion — rule
   1 already guarantees this since those transitions are plain CSS.
4. Reduced motion MUST NOT remove information — state must still be
   conveyed via the text/badge in `docs/semantic-states.md`, only the
   animated transition to it is skipped/shortened.
