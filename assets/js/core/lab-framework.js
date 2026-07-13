// Reusable static lab framework. Contract: docs/lab-framework.md
// Pure helpers (parseUrlState/serializeUrlState) have no DOM dependency and
// are unit-tested directly in scripts/test-reducers.js.

export function createLabDefinition({ metadata, initialState, reducer, render, events = [], scenarios = [], announce } = {}) {
  if (!metadata?.id) throw new Error("createLabDefinition: metadata.id is required");
  if (typeof initialState !== "object" || initialState === null) throw new Error("createLabDefinition: initialState must be a plain object");
  if (typeof reducer !== "function") throw new Error("createLabDefinition: reducer must be a function");
  if (typeof render !== "function") throw new Error("createLabDefinition: render must be a function");
  return { metadata, initialState, reducer, render, events, scenarios, announce };
}

// Only `scenario` and `step` are stable, shareable state — see design.md "URL state".
export function parseUrlState(search, scenarios = []) {
  const params = new URLSearchParams(search);
  const scenario = params.get("scenario");
  const validScenario = scenarios.some(s => s.id === scenario) ? scenario : null;
  const stepRaw = params.get("step");
  const step = stepRaw !== null && /^\d+$/.test(stepRaw) ? Number(stepRaw) : null;
  return { scenario: validScenario, step };
}

export function serializeUrlState(state, currentSearch = "") {
  const params = new URLSearchParams(currentSearch);
  let changed = false;
  if ("scenario" in state && state.scenario != null) {
    params.set("scenario", state.scenario);
    changed = true;
  }
  if ("step" in state && state.step != null) {
    params.set("step", String(state.step));
    changed = true;
  }
  if (!changed) return null;
  const query = params.toString();
  return query ? `?${query}` : "";
}

export function createAnnouncer(root) {
  let el = root.querySelector("[data-lab-announcer]");
  if (!el) {
    el = document.createElement("p");
    el.className = "sr-only";
    el.setAttribute("role", "status");
    el.setAttribute("aria-live", "polite");
    el.setAttribute("data-lab-announcer", "");
    root.prepend(el);
  }
  return text => { if (text) el.textContent = text; };
}

// Mounts a lab definition onto `root`. If `root` is missing, this is a no-op —
// progressive enhancement means the page's static theory/code/sources content
// remains fully readable without JavaScript (spec.md "Progressive enhancement").
export function mountLab(root, definition) {
  if (!root) return null;

  const { initialState, reducer, render, scenarios = [], announce } = definition;
  const urlState = parseUrlState(location.search, scenarios);

  let state = { ...initialState };
  if (urlState.scenario && "scenario" in initialState) state.scenario = urlState.scenario;
  if (urlState.step !== null && "step" in initialState) state.step = urlState.step;

  const announceText = createAnnouncer(root);

  const dispatch = event => {
    const prevState = state;
    const nextState = reducer(state, event);
    if (nextState === prevState) return;
    state = nextState;
    render(state, { dispatch, root });
    const url = serializeUrlState(state, location.search);
    if (url !== null) history.replaceState(null, "", url || location.pathname);
    const message = announce ? announce(state, event, prevState) : null;
    if (message) announceText(message);
  };

  render(state, { dispatch, root });
  return { getState: () => state, dispatch };
}
