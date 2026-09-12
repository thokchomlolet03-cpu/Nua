import test from "node:test";
import assert from "node:assert/strict";
import { readJSONBody } from "../request-body.mjs";
import {
  createSession,
  submitResponse,
  startGuided,
  startTransfer,
  validateSession,
  event,
  report,
  resolveGuidance,
} from "../web/core.js";
import {
  withSessionEditor,
  deleteSession,
  SESSION_KEY,
} from "../web/storage.js";
import { tasks } from "../web/content.js";
const answer = (stage) => ({
  choices: Object.fromEntries(
    tasks[stage].questions.map((q) => [q.id, q.answer]),
  ),
  explanation:
    "Synthetic reasoning about evidence and a repeatable fair comparison.",
  confidence: 2,
});
function complete() {
  const s = createSession(0, "audit");
  submitResponse(s, answer("baseline"), 1);
  startGuided(s, 2);
  submitResponse(s, answer("guided"), 3);
  startTransfer(s, true, 4);
  submitResponse(s, answer("transfer"), 5);
  return s;
}
test("all legitimate stages and partial drafts survive JSON reload", () => {
  const s = createSession(0, "audit");
  const check = () =>
    assert.deepEqual(validateSession(JSON.parse(JSON.stringify(s))), s);
  check();
  s.drafts.baseline = {
    choices: { evidence: 0 },
    explanation: "",
    confidence: 1,
  };
  check();
  submitResponse(s, answer("baseline"), 1);
  check();
  startGuided(s, 2);
  check();
  submitResponse(s, answer("guided"), 3);
  check();
  startTransfer(s, true, 4);
  check();
  submitResponse(s, answer("transfer"), 5);
  check();
  s.reviews.baseline = {
    status: "teacher-annotated",
    reviewedAt: 6,
    note: "Synthetic rubric review",
    evidence: "partial",
    investigation: "demonstrated",
    reasoning: "unreviewed",
  };
  check();
});
test("damaged dates, confidence, reviews, sequence and transfer labels fail closed", () => {
  for (const mutate of [
    (s) => (s.createdAt = "bad"),
    (s) => (s.id = ""),
    (s) => (s.transferMode = null),
    (s) => s.transferDueAt++,
    (s) => (s.responses.baseline.confidence = 4),
    (s) => (s.responses.guided.explanation = "short"),
    (s) => (s.responses.baseline.assistance = "guided"),
    (s) => (s.reviews.baseline = "not an object"),
    (s) => (s.events[0].seq = 9),
    (s) => (s.drafts.transfer = { choices: {}, explanation: "future draft" }),
    (s) => (s.metrics.aiCalls = 4),
    (s) => (s.metrics.aiFailures = 1),
    (s) => (s.responses.toString = {}),
    (s) =>
      s.events.push({
        seq: s.events.length + 1,
        at: 6,
        type: "hint_feedback",
        hintSeq: 999,
        helpful: true,
      }),
  ]) {
    const s = complete();
    mutate(s);
    assert.throws(() => validateSession(s));
  }
});
test("caller data cannot override event identity or smuggle response fields", () => {
  const s = createSession(0, "audit");
  event(s, "session_started", { seq: 99, type: "ai_hint", at: -1 }, 1);
  assert.deepEqual(s.events[0], { seq: 1, type: "session_started", at: 1 });
  submitResponse(
    s,
    { ...answer("baseline"), privateExtra: "not part of answer" },
    2,
  );
  assert.equal(s.responses.baseline.privateExtra, undefined);
});
test("summary explicitly separates fallback from AI-selected authored guidance", () => {
  const s = complete();
  event(s, "ai_hint", resolveGuidance("REPEAT"), 6);
  event(s, "ai_hint", resolveGuidance("invented prose"), 7);
  event(s, "ai_hint", { text: "historical example" }, 8);
  const r = report(s);
  assert.deepEqual(
    r.attempts.find((a) => a.stage === "guided").guidanceBreakdown,
    { aiSelectedAuthored: 1, authoredFallback: 1, historicalExperimental: 1 },
  );
  assert.match(r.timingBasis, /Unverified/);
  assert.match(r.comparability, /not measured learning gains/);
});
test("summary allowlists review fields instead of exporting unexpected free text", () => {
  const s = complete();
  s.reviews.baseline = {
    status: "teacher-annotated",
    note: "private",
    unexpected: "private",
  };
  assert.deepEqual(report(s).attempts[0].explanationReview, {
    status: "teacher-annotated",
  });
});
test("deletion compares the latest saved value before removing anything", () => {
  let removed = false;
  const storage = {
    getItem: (key) => {
      assert.equal(key, SESSION_KEY);
      return "newer";
    },
    removeItem: () => (removed = true),
  };
  assert.throws(() => deleteSession(storage, "stale"), /another window/);
  assert.equal(removed, false);
  deleteSession(storage, "newer");
  assert.equal(removed, true);
});
test("a second editor is denied while the first holds its lifetime lock", async () => {
  let held = false;
  const locks = {
    request: async (name, options, run) => {
      assert.equal(options.ifAvailable, true);
      if (held) return run(null);
      held = true;
      return run({ name });
    },
  };
  const calls = [];
  void withSessionEditor(locks, false, (granted) => calls.push(granted));
  await withSessionEditor(locks, false, (granted) => calls.push(granted));
  assert.deepEqual(calls, [true, false]);
});
test("missing browser locking fails closed, standalone Android remains supported", async () => {
  const grants = [];
  await withSessionEditor(undefined, false, (x) => grants.push(x));
  await withSessionEditor(undefined, true, (x) => grants.push(x));
  assert.deepEqual(grants, [false, true]);
});
async function* splitBytes(bytes) {
  for (const byte of bytes) yield Buffer.from([byte]);
}
test("Hindi survives every UTF-8 byte split", async () => {
  const body = {
    question: "मापों को दोहराना क्यों ज़रूरी है?",
    language: "hi",
  };
  assert.deepEqual(
    await readJSONBody(splitBytes(Buffer.from(JSON.stringify(body)))),
    body,
  );
});
test("request limit counts bytes and rejects malformed UTF-8", async () => {
  await assert.rejects(
    readJSONBody(splitBytes(Buffer.from(JSON.stringify("ह".repeat(1400))))),
    { status: 413 },
  );
  await assert.rejects(readJSONBody(splitBytes(Buffer.from([34, 255, 34]))), {
    status: 400,
  });
});
