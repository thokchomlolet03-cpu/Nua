import test from "node:test";
import assert from "node:assert/strict";
import {
  createSession,
  submitResponse,
  startGuided,
  startTransfer,
  hintRequest,
  score,
  report,
  validateSession,
  DAY,
  nextStep,
} from "../web/core.js";
import { tasks } from "../web/content.js";
const answer = (stage) => ({
  choices: Object.fromEntries(
    tasks[stage].questions.map((q) => [q.id, q.answer]),
  ),
  explanation:
    "The comparison must isolate the changed factor and include repeated trials.",
  confidence: 2,
});
function waiting() {
  const s = createSession(0, "test");
  submitResponse(s, answer("baseline"), 1);
  startGuided(s, 2);
  submitResponse(s, answer("guided"), 3);
  return s;
}
test("complete delayed flow separates evidence and preserves baseline", () => {
  const s = waiting();
  const first = JSON.stringify(s.responses.baseline);
  startTransfer(s, false, 3 + DAY);
  submitResponse(s, answer("transfer"), 4 + DAY);
  assert.equal(s.stage, "report");
  assert.equal(s.transferMode, "delayed");
  assert.equal(JSON.stringify(s.responses.baseline), first);
  assert.equal(s.responses.guided.assistance, "guided");
  assert.equal(s.responses.transfer.assistance, "unassisted");
  assert.equal(
    score("transfer", s.responses.transfer).filter((x) => x.correct).length,
    3,
  );
});
test("transfer cannot start early, demo clearly labeled", () => {
  const s = waiting();
  assert.throws(() => startTransfer(s, false, 4), /not due/);
  startTransfer(s, true, 4);
  assert.equal(s.transferMode, "immediate-demo");
  assert.throws(() => startTransfer(s, true, 5));
});
test("responses lock and do not retain references to mutable inputs", () => {
  const s = createSession(0, "test");
  const a = answer("baseline");
  submitResponse(s, a);
  a.explanation = "edited";
  assert.notEqual(s.responses.baseline.explanation, "edited");
  assert.throws(() => submitResponse(s, a));
});
test("validation rejects missing choices, short reasoning and invalid confidence", () => {
  for (const a of [
    { ...answer("baseline"), choices: {} },
    { ...answer("baseline"), explanation: "short" },
    { ...answer("baseline"), confidence: 0 },
    {
      ...answer("baseline"),
      choices: { evidence: 99, investigation: 0, judgment: 0 },
    },
  ])
    assert.throws(() => submitResponse(createSession(0, "test"), a));
});
test("AI gate restricts hints to guided and three calls", () => {
  const s = createSession(0, "test");
  assert.throws(() => hintRequest(s, "help me"));
  submitResponse(s, answer("baseline"));
  startGuided(s);
  assert.equal(hintRequest(s, "Help compare evidence").task, "guided");
  s.metrics.aiCalls = 3;
  assert.throws(() => hintRequest(s, "help me"), /three/);
});
test("summary omits raw student explanations and event data", () => {
  const s = waiting();
  s.events.push({ type: "ai_hint", text: "sensitive" });
  const r = report(s);
  assert.equal(r.includesRawResponses, false);
  assert.equal(r.attempts[0].explanation, undefined);
  assert.equal(r.events, undefined);
  assert.equal(
    report(s, true).attempts[0].explanation,
    s.responses.baseline.explanation,
  );
});
test("JSON roundtrip restores a session", () => {
  const s = waiting();
  assert.deepEqual(validateSession(JSON.parse(JSON.stringify(s))), s);
  assert.throws(() => validateSession({ schema: "old" }));
});
test("all tasks have three distinct valid evidence keys and bilingual content", () => {
  for (const task of Object.values(tasks)) {
    assert.equal(task.questions.length, 3);
    assert.ok(task.title.en && task.title.hi);
    for (const q of task.questions) {
      assert.ok(q.options[q.answer]);
      assert.ok(q.prompt.en && q.prompt.hi);
    }
  }
});
test("next step is limited and does not claim validated mastery", () => {
  assert.match(nextStep(waiting()), /Correct selections alone/);
});
