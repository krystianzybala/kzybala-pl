// Minimal DOM stand-in sized exactly to what assets/js/core/keyboard.js#initTablist
// touches (setAttribute, tabIndex, addEventListener, focus). Not a general
// DOM shim — deliberately not jsdom, to keep this repo's tooling dependency-free.
export function createTabStub(id) {
  const listeners = {};
  return {
    id,
    attributes: {},
    tabIndex: undefined,
    focused: false,
    setAttribute(name, value) { this.attributes[name] = value; },
    getAttribute(name) { return this.attributes[name]; },
    addEventListener(type, handler) { (listeners[type] ??= []).push(handler); },
    dispatch(type, event = { preventDefault() {} }) { (listeners[type] || []).forEach(h => h(event)); },
    focus() { this.focused = true; },
  };
}

export function createNavStub(tabs) {
  return {
    querySelectorAll: selector => (selector === '[role="tab"]' ? tabs : []),
  };
}
