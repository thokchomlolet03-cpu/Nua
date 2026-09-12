import test from "node:test";
import assert from "node:assert/strict";
import { resolveGuidance } from "../web/core.js";
test("model prose and invented facts never become new displayed hint text", () => {
  const r = resolveGuidance(
    "Different brands of water explain the measurements.",
  );
  assert.equal(r.guidanceMode, "authored-fallback");
  assert.equal(r.category, "GENERAL");
  assert.ok(!r.text.includes("brands of water"));
});
test("one valid route selects bounded authored guidance in the requested language", () => {
  const r = resolveGuidance(" REPEAT\n", "hi");
  assert.equal(r.category, "REPEAT");
  assert.equal(r.guidanceMode, "ai-selected-authored");
  assert.match(r.text, /[\u0900-\u097f]/);
});
test("multiple routes, empty output and prototype property names fall back", () => {
  for (const output of ["", "REPEAT CONTROL", "toString", "__proto__", null])
    assert.equal(resolveGuidance(output).guidanceMode, "authored-fallback");
});
