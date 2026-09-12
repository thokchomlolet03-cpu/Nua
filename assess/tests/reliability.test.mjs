import test from "node:test";
import assert from "node:assert/strict";
import {
  createSession,
  event,
  submitResponse,
  startGuided,
  nextStep,
  validateSession,
  recordHintFeedback,
  report,
} from "../web/core.js";
import {
  SESSION_KEY,
  saveSession,
  recoverInterruptedHint,
} from "../web/storage.js";
const memory = () => {
  const values = new Map();
  return {
    getItem: (k) => values.get(k) ?? null,
    setItem: (k, v) => values.set(k, v),
  };
};
test("hint feedback counts the latest rating once and preserves its self-report status", () => {
  const s = createSession(0, "test");
  event(s, "ai_hint", { text: "synthetic hint" }, 1);
  recordHintFeedback(s, 1, false, 2);
  recordHintFeedback(s, 1, true, 3);
  assert.deepEqual(report(s).hintFeedback, {
    ratedHints: 1,
    helpful: 1,
    source: "learner-self-report",
  });
  assert.throws(() => recordHintFeedback(s, 999, true));
});
test("stale tab cannot overwrite an observed newer session", () => {
  const db = memory(),
    s = createSession(0, "first");
  const original = saveSession(db, s, null);
  const newer = saveSession(db, { ...s, updatedAt: 100 }, original);
  assert.throws(
    () => saveSession(db, { ...s, updatedAt: 50 }, original),
    /another window/,
  );
  assert.equal(db.getItem(SESSION_KEY), newer);
});
test("failed storage write does not replace persisted answers", () => {
  const db = memory(),
    s = createSession(0, "test");
  const original = saveSession(db, s, null);
  db.setItem = () => {
    throw Error("Quota exceeded");
  };
  assert.throws(
    () => saveSession(db, { ...s, stage: "lesson" }, original),
    /Quota/,
  );
  assert.equal(db.getItem(SESSION_KEY), original);
});
test("interrupted inference is recovered once without invented model output", () => {
  const s = createSession(0, "test");
  s.metrics.aiCalls = 1;
  event(s, "ai_requested", {}, 1);
  assert.equal(recoverInterruptedHint(s, 10), true);
  assert.equal(s.metrics.aiFailures, 1);
  assert.equal(recoverInterruptedHint(s, 11), false);
  assert.equal(s.events.at(-1).type, "ai_interrupted");
});
test("guidance for comparable towel trials does not invent a confound", () => {
  const s = createSession(0, "test");
  const a = {
    choices: { evidence: 0, investigation: 1, judgment: 2 },
    explanation: "Use the available evidence and repeat the test.",
    confidence: 2,
  };
  submitResponse(s, a, 1);
  startGuided(s, 2);
  submitResponse(s, a, 3);
  assert.match(nextStep(s), /readings within each towel/);
});
test("corrupt nested data is rejected before rendering", () => {
  for (const mutate of [
    (s) => s.events.push(null),
    (s) => (s.metrics.aiCalls = "bad"),
    (s) => (s.drafts.baseline = { explanation: "draft" }),
    (s) =>
      (s.responses.transfer = {
        choices: { evidence: 1, investigation: 2, judgment: 0 },
        explanation: "future response",
      }),
  ]) {
    const s = createSession(0, "test");
    mutate(s);
    assert.throws(() => validateSession(s));
  }
});
