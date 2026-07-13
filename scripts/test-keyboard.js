#!/usr/bin/env node
// Tests the roving-tabindex tablist contract (docs/keyboard-rules.md) against
// a minimal DOM stub — see scripts/lib/dom-stub.js.
import { test } from "node:test";
import assert from "node:assert/strict";
import { initTablist } from "../assets/js/core/keyboard.js";
import { createTabStub, createNavStub } from "./lib/dom-stub.js";

function setup(n = 3) {
  const tabs = Array.from({ length: n }, (_, i) => createTabStub(`tab-${i}`));
  const nav = createNavStub(tabs);
  const selected = [];
  initTablist(nav, { onSelect: tab => selected.push(tab.id) });
  return { tabs, selected };
}

test("click selects a tab: aria-selected updates on all tabs, onSelect fires", () => {
  const { tabs, selected } = setup();
  tabs[1].dispatch("click");
  assert.equal(tabs[0].attributes["aria-selected"], "false");
  assert.equal(tabs[1].attributes["aria-selected"], "true");
  assert.equal(tabs[2].attributes["aria-selected"], "false");
  assert.deepEqual(selected, ["tab-1"]);
});

test("roving tabindex: only the selected tab has tabIndex 0", () => {
  const { tabs } = setup();
  tabs[2].dispatch("click");
  assert.equal(tabs[0].tabIndex, -1);
  assert.equal(tabs[1].tabIndex, -1);
  assert.equal(tabs[2].tabIndex, 0);
});

test("ArrowRight moves to the next tab and wraps past the end", () => {
  const { tabs, selected } = setup();
  tabs[2].dispatch("keydown", { key: "ArrowRight", preventDefault() {} });
  assert.deepEqual(selected, ["tab-0"]);
  assert.equal(tabs[0].focused, true);
});

test("ArrowLeft moves to the previous tab and wraps past the start", () => {
  const { tabs, selected } = setup();
  tabs[0].dispatch("keydown", { key: "ArrowLeft", preventDefault() {} });
  assert.deepEqual(selected, ["tab-2"]);
});

test("Home jumps to the first tab, End jumps to the last", () => {
  const home = setup();
  home.tabs[1].dispatch("keydown", { key: "Home", preventDefault() {} });
  assert.deepEqual(home.selected, ["tab-0"]);

  const end = setup();
  end.tabs[1].dispatch("keydown", { key: "End", preventDefault() {} });
  assert.deepEqual(end.selected, ["tab-2"]);
});

test("unrelated keys are ignored — no selection change", () => {
  const { tabs, selected } = setup();
  tabs[0].dispatch("keydown", { key: "a", preventDefault() { throw new Error("should not preventDefault for unhandled keys"); } });
  assert.deepEqual(selected, []);
});

test("a single-tab list is a no-op-safe base case", () => {
  const { tabs, selected } = setup(1);
  tabs[0].dispatch("keydown", { key: "ArrowRight", preventDefault() {} });
  assert.deepEqual(selected, ["tab-0"]);
});
