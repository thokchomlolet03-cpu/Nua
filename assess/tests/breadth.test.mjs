import test from "node:test";
import assert from "node:assert/strict";
import * as core from "../web/mangal-core.js";
import * as legacy from "../web/inquiry-core.js";
import {
  addFeedback,
  addFollowup,
  saveFeedbackDraft,
} from "../web/assessment-feedback.js";
import {
  questionTypes,
  makeQuestion,
  validateQuestionSet,
} from "../web/inquiry-questions.js";
import {
  validateQuestionRequest,
  questionModelRequest,
  parseQuestionDraft,
} from "../question-model.mjs";
const text =
  "A fair test varies one factor while keeping other relevant conditions equal. Repeated measurements reveal variation. If light and fertilizer both differ between plant groups, their separate effects cannot be isolated.";
const objective = "Explain how to isolate a cause using a fair comparison.";
const response =
  "Synthetic response: compare equal conditions and explain what additional evidence is required.";
function input(extra = false) {
  const plan = core.templatePlan(objective, text, 1);
  if (extra)
    for (const type of Object.keys(questionTypes).slice(20))
      plan.questions.push(makeQuestion(type, plan));
  plan.questions.forEach((q) => {
    q.reviewed = true;
  });
  return {
    title: "Synthetic inquiry",
    pages: [{ page: 1, text }],
    plan,
    mode: "teacher-reviewed",
    id: "synthetic",
  };
}
function round(s) {
  return core.validateInquiry(JSON.parse(JSON.stringify(s)));
}
test("new default has 20 distinct types; teacher can extend to 24 and must review each", () => {
  const plan = core.templatePlan(objective, text, 1);
  assert.equal(plan.questions.length, 20);
  assert.equal(new Set(plan.questions.map((q) => q.type)).size, 20);
  assert.throws(
    () => core.createInquiry({ ...input(), plan }, 0),
    /Review every/,
  );
  assert.equal(core.createInquiry(input(true), 0).plan.questions.length, 24);
});
test("all 24 questions are required, individually locked and restorable across pauses", () => {
  const s = core.createInquiry(input(true), 0);
  round(s);
  core.beginRecall(s, 1);
  core.submit(s, response, 2);
  for (let i = 0; i < 24; i++) {
    assert.equal(s.phase, "investigate");
    s.drafts.investigate = `unfinished draft ${i}`;
    s.paused = true;
    round(s);
    assert.throws(() => core.submit(s, response, 3 + i), /Resume/);
    s.paused = false;
    if (i === 0 || i === 7) core.exposeSource(s, 3 + i);
    core.submit(s, response + i, 3 + i);
    assert.equal(s.breadth.index, i + 1);
    assert.equal(s.drafts.investigate, undefined);
    round(s);
  }
  assert.equal(s.phase, "question");
  assert.equal(s.breadth.answers[0].condition, "source-assisted");
  assert.equal(s.breadth.answers[1].condition, "perspective-prompted");
  assert.equal(s.breadth.answers[7].condition, "source-assisted");
  core.submit(s, response, 40);
  core.submit(s, response, 41);
  assert.equal(s.phase, "waiting");
  assert.throws(() => core.returnForTransfer(s, false, 42));
  core.returnForTransfer(s, true, 42);
  core.submit(s, response, 43);
  round(s);
  assert.equal(s.phase, "complete");
  assert.equal(s.transferMode, "immediate-demo");
});
test("inquiry cannot be shortened, falsely marked complete, or have support conditions forged", () => {
  const s = core.createInquiry(input(), 0);
  core.beginRecall(s, 1);
  core.submit(s, response, 2);
  core.submit(s, response, 3);
  for (const mutate of [
    (s) => {
      s.breadth.index = 20;
    },
    (s) => {
      s.breadth.answers[0].id = "other";
    },
    (s) => {
      s.breadth.answers[0].condition = "source-assisted";
    },
    (s) => {
      s.breadth.sourceViews.push(5);
    },
    (s) => {
      delete s.breadth;
    },
    (s) => {
      s.plan.questions[0].reviewed = false;
    },
    (s) => {
      s.plan.questions[0].anchor =
        "invented quotation longer than twenty characters";
    },
    (s) => {
      s.plan.questions.pop();
    },
  ]) {
    const bad = structuredClone(s);
    mutate(bad);
    assert.throws(() => core.validateInquiry(bad));
  }
});
test("breadth approval rejects duplicate types, repeated questions and unresolved material", () => {
  for (const mutate of [
    (p) => {
      p.questions[1].type = p.questions[0].type;
    },
    (p) => {
      p.questions[1].prompt = p.questions[0].prompt.toUpperCase();
    },
    (p) => {
      p.questions[0].kind = "needs-material";
    },
    (p) => {
      p.questions[0].criterion = "";
    },
  ]) {
    const d = input();
    mutate(d.plan);
    assert.throws(() => core.createInquiry(d, 0));
  }
});
test("draft questions may be incomplete but approval remains blocked", () => {
  const d = input();
  d.plan.questions[0].prompt = "";
  d.plan.questions[0].reviewed = false;
  const prep = { ...d, warnings: [], objective, excerpt: text, page: 1 };
  assert.equal(core.validatePreparation(prep), prep);
  assert.throws(() => core.validatePlan(d.plan, d.pages));
});
test("summary preserves coverage and conditions without leaking questions, anchors or raw responses", () => {
  const s = core.createInquiry(input(), 0);
  core.beginRecall(s, 1);
  core.submit(s, response, 2);
  core.submit(s, response, 3);
  const summary = core.inquiryReport(s),
    full = core.inquiryReport(s, true);
  assert.equal(summary.breadth.planned, 20);
  assert.equal(summary.breadth.completed, 1);
  assert.equal(summary.breadth.responses[0].text, undefined);
  assert.equal(summary.plan, undefined);
  assert.equal(summary.pages, undefined);
  assert.ok(!JSON.stringify(summary).includes(text));
  assert.ok(!JSON.stringify(summary).includes(response));
  assert.equal(full.breadth.responses[0].text, response);
  assert.equal(full.plan.questions.length, 20);
});
test("legacy saved sessions remain usable without retroactively adding unattempted questions", () => {
  const d = input();
  d.plan = legacy.templatePlan(objective, text, 1);
  const s = legacy.createInquiry(d, 0);
  assert.equal(core.validateInquiry(s), s);
  core.beginRecall(s, 1);
  core.submit(s, response, 2);
  core.submit(s, response, 3);
  assert.equal(s.phase, "question");
  assert.equal(s.breadth, undefined);
  assert.throws(() => core.createInquiry(d), /20–40/);
  assert.notEqual(core.KEY, legacy.KEY);
});
const request = {
  objective,
  excerpt: text,
  page: 1,
  types: ["definition", "evidence"],
};
function aiRaw() {
  return {
    questions: request.types.map((type, i) => ({
      type,
      prompt: i
        ? "What evidence isolates fertilizer from changes in light?"
        : "What does a fair test mean in the passage?",
      relevance:
        "Connect this question to fair comparisons and isolating a changing factor.",
      criterion:
        "Look for a distinction between a changing factor and conditions kept equal.",
      anchor: text.slice(0, 80),
      kind: "source",
    })),
  };
}
test("AI uses bounded batches of distinct question types and labels drafts unreviewed", () => {
  const req = questionModelRequest("test-model", request);
  assert.equal(req.options.num_predict, 2400);
  assert.equal(req.options.num_ctx, 8192);
  assert.match(req.prompt, /UNTRUSTED DATA/);
  for (const types of [
    [],
    ["definition", "definition"],
    ["__proto__"],
    Object.keys(questionTypes).slice(0, 5),
  ])
    assert.throws(() => validateQuestionRequest({ ...request, types }));
  const parsed = parseQuestionDraft(
    JSON.stringify(aiRaw()),
    request,
    "test-model",
  );
  assert.equal(parsed[0].reviewed, false);
  assert.equal(parsed[0].page, 1);
  assert.equal(parsed[0].origin, "local-ai");
});
test("AI cannot invent anchors, omit types, duplicate prompts or substitute rationale for criteria", () => {
  for (const mutate of [
    (r) => {
      r.questions.pop();
    },
    (r) => {
      r.questions[0].type = "experiment";
    },
    (r) => {
      r.questions[0].anchor =
        "This is an invented quotation that is not supplied.";
    },
    (r) => {
      r.questions[1].prompt = r.questions[0].prompt;
    },
    (r) => {
      r.questions[0].criterion = r.questions[0].relevance;
    },
    (r) => {
      r.questions[0].kind = "automatically-correct";
    },
  ]) {
    const r = aiRaw();
    mutate(r);
    assert.throws(() => parseQuestionDraft(JSON.stringify(r), request, "test"));
  }
});

function completedInquiry() {
  const s = core.createInquiry(input(), 0);
  core.beginRecall(s, 1);
  core.submit(s, response, 2);
  for (let i = 0; i < 20; i++) {
    if (i === 3) s.breadth.signalDraft = "need-help";
    core.submit(s, response, i + 3);
  }
  core.submit(s, response, 30);
  core.submit(s, response, 31);
  core.returnForTransfer(s, true, 32);
  core.submit(s, response, 33);
  return s;
}
const feedback = {
  questionId: "q4",
  interpretation: "needs-clarification",
  evidence: "compare equal conditions",
  nextStep:
    "Draw two plant groups and label the factor varied and the conditions kept equal.",
  successCriterion:
    "Explain why the comparison can isolate fertilizer only when other relevant conditions are equal.",
};
test("learner uncertainty is optional, stored per question and remains a self-report", () => {
  const s = completedInquiry();
  assert.equal(s.breadth.answers[3].learnerSignal, "need-help");
  assert.equal(s.breadth.answers[4].learnerSignal, "not-recorded");
  assert.equal(
    core.inquiryReport(s).breadth.responses[3].learnerSignal,
    "need-help",
  );
  s.breadth.answers[3].learnerSignal = "diagnosed";
  assert.throws(() => core.validateInquiry(s));
});
test("feedback requires real response evidence and an observable next step after transfer", () => {
  const s = completedInquiry(),
    prior = structuredClone(s.responses);
  addFeedback(s, feedback, 40);
  round(s);
  assert.deepEqual(s.responses, prior);
  for (const override of [
    { evidence: "invented learner statement" },
    { nextStep: "" },
    { questionId: "q99" },
    { interpretation: "mastered" },
  ]) {
    const before = JSON.stringify(s);
    assert.throws(() => addFeedback(s, { ...feedback, ...override }, 41));
    assert.equal(JSON.stringify(s), before);
  }
  const early = core.createInquiry(input(), 0);
  assert.throws(() => addFeedback(early, feedback, 1));
});
test("follow-up is distinct from original application and feedback revisions retain history", () => {
  const s = completedInquiry();
  addFeedback(s, feedback, 40);
  addFollowup(
    s,
    1,
    "I can now describe which factor changes but still need help explaining variation.",
    "still-need-help",
    41,
  );
  assert.throws(() => addFollowup(s, 1, response, "can-explain", 42));
  addFeedback(
    s,
    {
      ...feedback,
      nextStep:
        "Compare two sets of repeated measurements and describe the variation in each.",
    },
    43,
  );
  round(s);
  assert.equal(s.feedback.entries.length, 2);
  assert.equal(s.feedback.followups[0].condition, "after-educator-feedback");
  assert.equal(s.responses.transfer.text, response);
});
test("summary omits feedback quotes, next-step text and learner follow-ups", () => {
  const s = completedInquiry();
  addFeedback(s, feedback, 40);
  addFollowup(
    s,
    1,
    "Private synthetic learner follow-up text.",
    "can-explain",
    41,
  );
  const report = core.inquiryReport(s),
    full = core.inquiryReport(s, true);
  assert.equal(report.feedback.entries[0].evidence, undefined);
  assert.equal(report.feedback.entries[0].nextStep, undefined);
  assert.equal(report.feedback.followups[0].text, undefined);
  assert.equal(full.feedback.entries[0].evidence, feedback.evidence);
  assert.equal(
    full.feedback.followups[0].text,
    "Private synthetic learner follow-up text.",
  );
});
test("partial educator drafts restore and cannot masquerade as submitted feedback", () => {
  const s = completedInquiry();
  saveFeedbackDraft(s, "q4", {
    interpretation: "needs-clarification",
    evidence: "unfinished",
    nextStep: "",
    successCriterion: "",
  });
  round(s);
  assert.equal(core.inquiryReport(s).feedback, undefined);
  assert.throws(() =>
    addFeedback(s, { questionId: "q4", ...s.feedbackDrafts.q4 }, 40),
  );
  addFeedback(s, feedback, 41);
  assert.equal(s.feedbackDrafts.q4, undefined);
});
test("editing an incomplete source anchor remains recoverable but assignment checks the exact source", () => {
  const d = input();
  d.plan.questions[0].anchor = "unfinished change";
  const prep = { ...d, warnings: [], objective, excerpt: text, page: 1 };
  core.validatePreparation(prep);
  assert.throws(() => core.createInquiry(d));
});
