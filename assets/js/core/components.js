// Shared DOM-enhancer components. Each function hydrates/updates a piece of
// static, server-rendered markup — it never invents content that wasn't
// already readable without JavaScript. Contract: docs/lab-framework.md
import { initTablist } from "./keyboard.js";
import { createAnnouncer } from "./lab-framework.js";

// ScenarioSelector — a tablist of named scenarios (design.md "URL state").
export function renderScenarioSelector(nav, { scenarios, current, onSelect }) {
  if (!nav || scenarios.length === 0) return;
  nav.setAttribute("role", "tablist");
  nav.replaceChildren(...scenarios.map(scenario => {
    const button = document.createElement("button");
    button.id = `scenario-${scenario.id}`;
    button.type = "button";
    button.setAttribute("role", "tab");
    button.dataset.scenario = scenario.id;
    button.textContent = scenario.label;
    button.setAttribute("aria-selected", String(scenario.id === current));
    button.tabIndex = scenario.id === current ? 0 : -1;
    return button;
  }));
  initTablist(nav, { onSelect: tab => onSelect(tab.dataset.scenario) });
}

// StepControls — Previous / Next / Reset. Native buttons; disabled state is
// conveyed both visually (native `disabled`) and to assistive tech.
export function renderStepControls(el, { onPrevious, onNext, onReset, canGoBack = true, canGoForward = true }) {
  if (!el) return;
  el.replaceChildren();
  const make = (label, handler, enabled) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "control";
    button.textContent = label;
    button.disabled = !enabled;
    button.addEventListener("click", handler);
    return button;
  };
  if (onPrevious) el.append(make("Previous", onPrevious, canGoBack));
  if (onNext) el.append(make("Next", onNext, canGoForward));
  if (onReset) el.append(make("Reset", onReset, true));
}

// StateInspector — a plain-text, always-visible readout of current state,
// so the lab's condition is never conveyed by animation/colour alone.
export function renderStateInspector(el, fields) {
  if (!el) return;
  el.replaceChildren(...fields.flatMap(({ label, value }) => {
    const dt = document.createElement("dt");
    dt.textContent = label;
    const dd = document.createElement("dd");
    dd.textContent = String(value);
    return [dt, dd];
  }));
}

// CodeTabs — hydrates a static `role="tablist"` of language tabs whose panels
// already contain the full code in HTML (progressive enhancement: the code
// is readable without JS; JS only adds tab-switching + copy-to-clipboard).
export function mountCodeTabs(container) {
  if (!container) return;
  const nav = container.querySelector('[role="tablist"]');
  if (!nav) return;
  const panels = [...container.querySelectorAll('[role="tabpanel"]')];
  const announce = createAnnouncer(document.body);

  initTablist(nav, {
    onSelect: tab => {
      panels.forEach(panel => { panel.hidden = panel.id !== tab.getAttribute("aria-controls"); });
      announce(`Showing ${tab.textContent.trim()} code.`);
    },
  });

  for (const panel of panels) {
    const pre = panel.querySelector("pre");
    if (!pre) continue;
    const button = document.createElement("button");
    button.type = "button";
    button.className = "control copy-code";
    button.textContent = "Copy";
    button.addEventListener("click", async () => {
      try {
        await navigator.clipboard.writeText(pre.textContent);
        button.textContent = "Copied";
        setTimeout(() => { button.textContent = "Copy"; }, 1500);
      } catch {
        button.textContent = "Copy failed";
      }
    });
    panel.prepend(button);
  }
}

// Quiz — graded client-side, native radio inputs, result stated in text.
export function renderQuiz(el, { question, choices, correctIndex }) {
  if (!el) return;
  const form = document.createElement("form");
  form.className = "quiz";
  const legend = document.createElement("legend");
  legend.textContent = question;
  const fieldset = document.createElement("fieldset");
  fieldset.append(legend);

  choices.forEach((choice, i) => {
    const id = `${el.id || "quiz"}-choice-${i}`;
    const wrapper = document.createElement("div");
    const input = document.createElement("input");
    input.type = "radio";
    input.name = "quiz-choice";
    input.id = id;
    input.value = String(i);
    const label = document.createElement("label");
    label.htmlFor = id;
    label.textContent = choice;
    wrapper.append(input, label);
    fieldset.append(wrapper);
  });

  const submit = document.createElement("button");
  submit.type = "submit";
  submit.className = "control primary";
  submit.textContent = "Check answer";

  const result = document.createElement("p");
  result.className = "sr-only";
  result.setAttribute("role", "status");
  result.setAttribute("aria-live", "polite");

  form.append(fieldset, submit, result);
  form.addEventListener("submit", event => {
    event.preventDefault();
    const picked = form.querySelector('input[name="quiz-choice"]:checked');
    result.className = picked && Number(picked.value) === correctIndex ? "state-badge is-success" : "state-badge is-danger";
    result.textContent = !picked
      ? "Pick an answer first."
      : Number(picked.value) === correctIndex ? "Correct." : "Not quite — review the theory above and try again.";
  });

  el.replaceChildren(form);
}

// Benchmark disclosure — see docs/components.md for the static markup pattern
// this mirrors; use this helper only when the disclosure text is computed at
// runtime rather than authored directly in content/labs/<id>/benchmark.md.
export function renderDisclosure(el, { kind, title, body }) {
  if (!el) return;
  const wrapper = document.createElement("div");
  wrapper.className = `disclosure ${kind}`;
  const heading = document.createElement("p");
  heading.className = "disclosure-kind";
  heading.textContent = title;
  const text = document.createElement("p");
  text.textContent = body;
  wrapper.append(heading, text);
  el.replaceChildren(wrapper);
}
