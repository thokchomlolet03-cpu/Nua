import test from "node:test";
import assert from "node:assert/strict";
import {
  normalizePages,
  templatePlan,
  validatePlan,
  createInquiry,
  validateInquiry,
  validatePreparation,
  beginRecall,
  exposeSource,
  submit,
  returnForTransfer,
  parkQuestion,
  saveReflection,
  saveReview,
  inquiryReport,
  DAY,
} from "../web/inquiry-core.js";
import {
  validateDraftRequest,
  inquiryModelRequest,
  parseInquiryDraft,
} from "../inquiry-model.mjs";
const text =
  "A fair test varies one factor while keeping other relevant conditions equal. Repeated measurements reveal variation. If light and fertilizer both differ between plant groups, their separate effects cannot be isolated.";
const objective = "Explain how to isolate a cause using a fair comparison.";
const answer =
  "Synthetic explanation: equal conditions and repeated comparisons provide more useful evidence than a confounded comparison.";
function fresh() {
  const pages = normalizePages([{ text }]);
  return createInquiry(
    {
      title: "Synthetic fair tests",
      pages,
      plan: templatePlan(objective, text, 1),
      mode: "teacher-reviewed",
      id: "test",
    },
    0,
  );
}
function waiting() {
  const s = fresh();
  beginRecall(s, 1);
  submit(s, answer, 2);
  submit(s, answer, 3);
  submit(
    s,
    "What comparison would separate light from fertilizer, and what should stay equal?",
    4,
  );
  saveReflection(s, "added-evidence", 5);
  submit(s, answer, 6);
  return s;
}
test("each phase and partial response survives persistence without unlocking submitted work", () => {
  const s = fresh(),
    check = () =>
      assert.deepEqual(validateInquiry(JSON.parse(JSON.stringify(s))), s);
  check();
  beginRecall(s, 1);
  s.drafts.recall = "unfinished";
  check();
  submit(s, answer, 2);
  check();
  submit(s, answer, 3);
  check();
  submit(s, answer, 4);
  check();
  submit(s, answer, 5);
  check();
  returnForTransfer(s, true, 6);
  check();
  submit(s, answer, 7);
  check();
  assert.equal(s.phase, "complete");
  assert.throws(() => submit(s, answer));
});
test("source support changes the attempt condition, never pretends independent recall", () => {
  const s = fresh();
  beginRecall(s, 1);
  exposeSource(s, 2);
  submit(s, answer, 3);
  assert.equal(s.responses.recall.condition, "source-assisted");
  assert.equal(s.events[1].type, "source_support");
});
test("unaided recall and prompted inquiry have distinct evidence conditions", () => {
  const s = waiting();
  assert.equal(s.responses.recall.condition, "closed-source-self-report");
  assert.equal(s.responses.investigate.condition, "perspective-prompted");
  assert.equal(s.responses.revise.condition, "source-and-rubric-review");
});
test("delayed return gates transfer, immediate demo never becomes delayed evidence", () => {
  const s = waiting();
  assert.throws(() => returnForTransfer(s, false, 7));
  returnForTransfer(s, true, 7);
  assert.equal(s.transferMode, "immediate-demo");
  assert.throws(() => exposeSource(s, 8));
  assert.throws(() => parkQuestion(s, "more questions", 8));
  submit(s, answer, 8);
  assert.equal(s.responses.transfer.condition, "unassisted-self-report");
  assert.equal(inquiryReport(s).transferMode, "immediate-demo");
  const d = waiting();
  returnForTransfer(d, false, 6 + DAY);
  assert.equal(d.transferMode, "delayed-device-clock");
});
test("source quotation must really occur on the cited page", () => {
  const s = fresh();
  assert.throws(() =>
    validatePlan(
      {
        ...s.plan,
        quote:
          "This invented quotation is definitely not in the supplied page.",
      },
      s.pages,
    ),
  );
  assert.throws(() => validatePlan({ ...s.plan, page: 2 }, s.pages));
});
test("provisional self-study exposure is explicit in evidence", () => {
  const s = fresh();
  s.approval.mode = "self-study-provisional";
  assert.equal(inquiryReport(s).approval.mode, "self-study-provisional");
  assert.match(inquiryReport(s).notice, /setup exposes/);
});
test("template adapts to material with transparent origin and one perspective", () => {
  const p = templatePlan(
    "Explain the sequence in the water cycle.",
    "The water cycle is a process with evaporation, condensation and precipitation steps.",
    1,
  );
  assert.equal(p.perspective, "mechanism");
  assert.equal(p.origin, "rule-based-template");
  assert.match(p.recall, /water cycle/);
  assert.match(p.transfer, /not a calibrated/);
});
test("parking curiosity is bounded and is not an endless automatic task queue", () => {
  const s = fresh();
  beginRecall(s, 1);
  for (let i = 0; i < 3; i++) parkQuestion(s, `Later question ${i}`, 2 + i);
  assert.equal(s.phase, "recall");
  assert.throws(() => parkQuestion(s, "Fourth later question", 6));
});
test("summary excludes material, questions, free responses and teacher notes", () => {
  const s = waiting();
  returnForTransfer(s, true, 7);
  submit(s, answer, 8);
  saveReview(s, "needs-follow-up", "Private synthetic note", 9);
  const r = inquiryReport(s);
  assert.equal(r.pages, undefined);
  assert.equal(r.plan, undefined);
  assert.equal(r.title, undefined);
  assert.equal(r.events, undefined);
  assert.equal(r.responses.recall.text, undefined);
  assert.equal(r.review.note, undefined);
  assert.equal(inquiryReport(s, true).review.note, "Private synthetic note");
});
test("sequence, source, coverage and condition corruption fail closed", () => {
  for (const mutate of [
    (s) => (s.phase = "complete"),
    (s) => (s.pages[0].text = "changed"),
    (s) => (s.plan.perspective = "__proto__"),
    (s) => (s.paused = "true"),
    (s) => (s.coverageWarnings = [null]),
    (s) => (s.approval.sourceChecked = false),
    (s) => (s.drafts.transfer = "future"),
    (s) => s.events.push(null),
    (s) => (s.sourceViews.transfer = true),
    (s) => (s.dueAt = 123),
    (s) =>
      (s.responses.recall = { text: answer, at: 2, condition: "invented" }),
  ]) {
    const s = fresh();
    mutate(s);
    assert.throws(() => validateInquiry(s));
  }
});
test("incomplete plan editing restores as a draft but cannot be approved", () => {
  const s = fresh();
  const prep = {
    title: s.title,
    pages: s.pages,
    warnings: [],
    objective,
    excerpt: text,
    page: 1,
    plan: { ...s.plan, recall: "" },
    mode: "teacher-reviewed",
  };
  assert.equal(validatePreparation(prep), prep);
  assert.throws(() => validatePlan(prep.plan, prep.pages));
});
test("no-text scans, overlong documents and oversized extracted text are rejected", () => {
  assert.throws(() => normalizePages([{ text: "" }]));
  assert.throws(() =>
    normalizePages(Array.from({ length: 81 }, () => ({ text }))),
  );
  assert.throws(() => normalizePages([{ text: "x".repeat(180001) }]));
});
test("AI drafting bounds Unicode input, model output and context without silent truncation", () => {
  const p = { objective, excerpt: text, page: 1 },
    r = inquiryModelRequest("synthetic-model", p);
  assert.equal(r.options.num_predict, 1000);
  assert.equal(r.options.num_ctx, 8192);
  assert.equal(r.stream, false);
  assert.match(r.prompt, /UNTRUSTED DATA/);
  assert.throws(() =>
    validateDraftRequest({ ...p, excerpt: "ह".repeat(1501) }),
  );
  assert.throws(() => validateDraftRequest(null));
});
test("AI cannot replace the objective, source quotation, page or provenance", () => {
  const p = { objective, excerpt: text, page: 1 };
  const raw = {
    perspective: "causality",
    reason: answer,
    recall: "What makes a comparison fair according to the source?",
    investigate:
      "Which conditions changed in the example and what remains uncertain?",
    transfer:
      "Design a fair comparison for a new investigation of paper strength.",
    rubric:
      "Look for equal relevant conditions, an isolated factor and repeated measurements.",
    quote: "invented",
    page: 80,
    origin: "teacher-approved",
    objective: "different",
  };
  const plan = parseInquiryDraft(JSON.stringify(raw), p, "test-model");
  assert.equal(plan.quote, text);
  assert.equal(plan.objective, objective);
  assert.equal(plan.page, 1);
  assert.equal(plan.origin, "local-ai-draft");
  validatePlan(plan, normalizePages([{ text }]));
});
test("invalid or incomplete AI responses cannot replace the editable template", () => {
  const p = { objective, excerpt: text, page: 1 };
  for (const raw of [
    "not JSON",
    "null",
    "{}",
    JSON.stringify({ perspective: "__proto__" }),
    JSON.stringify({ perspective: "causality", reason: "short" }),
  ])
    assert.throws(() => parseInquiryDraft(raw, p, "test"));
});
test("duplicated AI questions or rationale-as-rubric are rejected", () => {
  const p = { objective, excerpt: text, page: 1 };
  const raw = {
    perspective: "causality",
    reason: answer,
    recall: "Explain one fair comparison from the passage.",
    investigate: "Which variable was not isolated in the example?",
    transfer: "Design a different comparison involving two paper types.",
    rubric: answer,
  };
  assert.throws(
    () => parseInquiryDraft(JSON.stringify(raw), p, "test"),
    /repeated/,
  );
  raw.rubric =
    "Check whether the explanation identifies the factor being varied.";
  raw.transfer = raw.recall;
  assert.throws(
    () => parseInquiryDraft(JSON.stringify(raw), p, "test"),
    /repeated/,
  );
});
