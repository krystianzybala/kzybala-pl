# Keyboard rules

Every lab MUST be fully operable without a pointing device (spec.md,
"Accessibility baseline").

1. **Native interactive elements only.** Use `<button>`, `<a href>`, and
   form controls for anything actionable. Never bind click handlers to
   `<div>`/`<span>`. Native elements get focusability, `Enter`/`Space`
   activation, and correct role for free.
2. **Visible focus.** Never remove the focus outline (`outline: none`)
   without replacing it with an equally visible alternative. The default
   browser focus ring is acceptable.
3. **Logical order.** Focus order MUST follow visual/reading order. Don't
   reorder via `tabindex` values other than `0` and `-1`.
4. **Grouped controls (tabs, toggles, scenario selectors) use roving
   tabindex**, not a flat tab stop per control:
   - The group root gets `role="tablist"`, each control `role="tab"`,
     `aria-selected`, and `aria-controls` pointing at its panel.
   - Only the selected tab has `tabindex="0"`; the rest have `tabindex="-1"`.
   - `ArrowRight`/`ArrowDown` moves to the next tab, `ArrowLeft`/`ArrowUp`
     to the previous, `Home`/`End` to the first/last. Arrow navigation also
     activates the tab (per the WAI-ARIA APG "automatic activation"
     pattern), matching how these labs already switch views instantly.
   - Each panel gets `role="tabpanel"` and `aria-labelledby` pointing back
     at its tab.
   - Use `assets/js/core/keyboard.js#initTablist` rather than
     reimplementing this per lab.
5. **No keyboard traps.** Anything that can be opened (menus, dialogs) MUST
   be closable with `Escape` and never steal focus permanently.
6. **State changes are announced**, not just shown — see
   `docs/semantic-states.md`.
