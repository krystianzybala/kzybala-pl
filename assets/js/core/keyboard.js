// Roving-tabindex ARIA tablist helper. Contract: docs/keyboard-rules.md
export function initTablist(nav, { onSelect } = {}) {
  const tabs = [...nav.querySelectorAll('[role="tab"]')];
  if (tabs.length === 0) return;

  const select = tab => {
    tabs.forEach(t => {
      const selected = t === tab;
      t.setAttribute("aria-selected", String(selected));
      t.tabIndex = selected ? 0 : -1;
    });
    onSelect?.(tab);
  };

  tabs.forEach((tab, i) => {
    tab.addEventListener("click", () => select(tab));
    tab.addEventListener("keydown", event => {
      let nextIndex = null;
      if (event.key === "ArrowRight" || event.key === "ArrowDown") nextIndex = (i + 1) % tabs.length;
      else if (event.key === "ArrowLeft" || event.key === "ArrowUp") nextIndex = (i - 1 + tabs.length) % tabs.length;
      else if (event.key === "Home") nextIndex = 0;
      else if (event.key === "End") nextIndex = tabs.length - 1;
      if (nextIndex !== null) {
        event.preventDefault();
        tabs[nextIndex].focus();
        select(tabs[nextIndex]);
      }
    });
  });
}

// True when the user has requested reduced motion. Contract: docs/reduced-motion-policy.md
export function prefersReducedMotion() {
  return matchMedia("(prefers-reduced-motion: reduce)").matches;
}
