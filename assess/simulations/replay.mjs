// Agent-written synthetic responses, replayed through production assessment functions.
// This exercises domain logic, not browser UI, model inference or human learning.
import fs from "node:fs";
import { fileURLToPath } from "node:url";
import assert from "node:assert/strict";
import * as core from "../web/mangal-core.js";
import { addFeedback, addFollowup } from "../web/assessment-feedback.js";

const read = (name) =>
  JSON.parse(fs.readFileSync(new URL(name, import.meta.url), "utf8"));
export function replay(lesson, actor, caseId, feedback = [], followups = []) {
  assert.equal(actor.synthetic, true);
  const plan = structuredClone(lesson.plan);
  // Test-fixture approval only: this is not a human teacher endorsement.
  plan.questions.forEach((q) => {
    q.reviewed = true;
  });
  assert.equal(actor.inquiry.length, plan.questions.length);
  const state = core.createInquiry(
    {
      title: "SYNTHETIC " + caseId,
      pages: lesson.pages,
      plan,
      mode: "self-study-provisional",
      id: "synthetic-" + caseId,
    },
    0,
  );
  let time = 1;
  const checks = [];
  const restored = () => {
    core.validateInquiry(JSON.parse(JSON.stringify(state)));
  };
  const blocked = (name, operation) => {
    const before = JSON.stringify(state);
    assert.throws(operation);
    assert.equal(JSON.stringify(state), before);
    checks.push(name);
  };
  core.beginRecall(state, time++);
  core.submit(state, actor.recall, time++);
  blocked("too-short response rejected without changing state", () =>
    core.submit(state, "why?", time++),
  );
  blocked("educator feedback before application rejected", () =>
    addFeedback(
      state,
      {
        questionId: plan.questions[0].id,
        interpretation: "needs-clarification",
        evidence: "not a real answer",
        nextStep: "Compare two different cases.",
        successCriterion: "Explain the important difference.",
      },
      time++,
    ),
  );
  for (const [i, answer] of actor.inquiry.entries()) {
    assert.equal(answer.id, plan.questions[i].id);
    assert.equal(state.phase, "investigate");
    state.drafts.investigate = answer.text;
    state.breadth.signalDraft = answer.learnerSignal;
    state.paused = true;
    restored();
    blocked("paused response " + answer.id + " cannot submit", () =>
      core.submit(state, answer.text, time++),
    );
    state.paused = false;
    if (answer.requestSource) core.exposeSource(state, time++);
    core.submit(state, answer.text, time++);
    restored();
    assert.equal(
      state.breadth.answers[i].condition,
      answer.requestSource ? "source-assisted" : "perspective-prompted",
    );
  }
  assert.equal(state.phase, "question");
  core.submit(state, actor.learnerQuestion, time++);
  core.submit(state, actor.revision, time++);
  blocked("early delayed application rejected", () =>
    core.returnForTransfer(state, false, time++),
  );
  core.returnForTransfer(state, true, time++);
  core.submit(state, actor.transfer, time++);
  assert.equal(state.phase, "complete");
  assert.equal(state.transferMode, "immediate-demo");
  const original = JSON.stringify({
    responses: state.responses,
    answers: state.breadth.answers,
  });
  for (const entry of feedback) addFeedback(state, entry, time++);
  for (const entry of followups)
    addFollowup(state, entry.feedbackId, entry.text, entry.signal, time++);
  assert.equal(
    JSON.stringify({
      responses: state.responses,
      answers: state.breadth.answers,
    }),
    original,
  );
  restored();
  const summary = core.inquiryReport(state),
    full = core.inquiryReport(state, true);
  assert.equal(summary.breadth.completed, plan.questions.length);
  for (const a of summary.breadth.responses) assert.equal(a.text, undefined);
  assert.equal(summary.pages, undefined);
  assert.equal(summary.plan, undefined);
  for (const f of summary.feedback?.entries || []) {
    assert.equal(f.evidence, undefined);
    assert.equal(f.nextStep, undefined);
    assert.equal(f.successCriterion, undefined);
  }
  for (const f of summary.feedback?.followups || [])
    assert.equal(f.text, undefined);
  return {
    caseId,
    synthetic: true,
    executedThrough: "production domain functions; no browser interaction",
    virtualTimestamps: true,
    checks,
    summary,
    full,
  };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const lesson = read("lesson.json");
  const teacher = fs.existsSync(
    new URL("teacher-feedback.json", import.meta.url),
  )
    ? read("teacher-feedback.json")
    : { cases: [] };
  const cases = [
    ["A", "concise"],
    ["B", "misconception"],
    ["C", "prerequisite"],
  ].map(([caseId, name]) => {
    const feedback =
      teacher.cases.find((c) => c.caseId === caseId)?.feedback || [];
    const followupPath = new URL(name + "-followup.json", import.meta.url);
    const followups = fs.existsSync(followupPath)
      ? read(name + "-followup.json").followups
      : [];
    return replay(lesson, read(name + ".json"), caseId, feedback, followups);
  });
  console.log(
    JSON.stringify(
      {
        schema: "nua-agent-simulation/1",
        synthetic: true,
        notice:
          "Agent-generated scenarios, not real learners, human educator validation, observed retention or learning gains. Shared model family and authored personas constrain results.",
        cases,
      },
      null,
      2,
    ),
  );
}
