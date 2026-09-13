// Additive adapter: old saved inquiries keep their original sequence and evidence.
import * as legacy from "./inquiry-core.js";
import {
  validateFeedbackState,
  feedbackReport,
  learnerSignals,
} from "./assessment-feedback.js";
export { addFeedback, addFollowup } from "./assessment-feedback.js";
import {
  expandPlan,
  validateQuestionSet,
  BREADTH_POLICY,
  DELIVERY_POLICY,
  inquirySessions,
} from "./inquiry-questions.js";
export * from "./inquiry-core.js";
export {
  BREADTH_POLICY,
  MIN_TYPES,
  MAX_QUESTIONS,
  questionTypes,
  validateQuestionSet,
  DELIVERY_POLICY,
  inquirySessions,
} from "./inquiry-questions.js";
export const KEY = "nua-mangal-session-v2";
export const templatePlan = (...args) => ({
  ...expandPlan(legacy.templatePlan(...args)),
  rubric:
    "Compare the original explanation, inquiry responses and synthesis against the source. Look for justified claims, recognition of missing evidence and correction of a specific error. Use the question-level criteria to decide the next teaching step. Completion alone does not establish understanding.",
});
export function validatePlan(plan, pages) {
  legacy.validatePlan(plan, pages);
  if (plan.breadthPolicy !== undefined || plan.questions !== undefined)
    validateQuestionSet(plan);
  return plan;
}
export function validatePreparation(p) {
  legacy.validatePreparation(p);
  if (
    p.questionIndex !== undefined &&
    (!Number.isInteger(p.questionIndex) ||
      p.questionIndex < 0 ||
      p.questionIndex >= 40)
  )
    throw Error("Invalid saved question-review position.");
  if (
    p.plan &&
    (p.plan.breadthPolicy !== undefined || p.plan.questions !== undefined)
  )
    validateQuestionSet(p.plan, { draft: true });
  if (
    p.aiTypesTried !== undefined &&
    (!Array.isArray(p.aiTypesTried) ||
      p.aiTypesTried.length > 40 ||
      p.aiTypesTried.some((x) => typeof x !== "string"))
  )
    throw Error("Invalid saved AI attempt budget.");
  return p;
}
export function createInquiry(input, now = Date.now()) {
  validateQuestionSet(input.plan, { reviewed: true });
  const s = legacy.createInquiry(input, now);
  s.breadth = {
    policy: BREADTH_POLICY,
    index: 0,
    answers: [],
    sourceViews: [],
  };
  return s;
}
export function exposeSource(s, now = Date.now()) {
  legacy.exposeSource(s, now);
  if (
    s.breadth &&
    s.phase === "investigate" &&
    !s.breadth.sourceViews.includes(s.breadth.index)
  )
    s.breadth.sourceViews.push(s.breadth.index);
}
export function submit(s, value, now = Date.now()) {
  if (s.paused) throw Error("Resume before submitting a response.");
  if (!s.breadth || s.phase !== "investigate")
    return legacy.submit(s, value, now);
  validateInquiry(s);
  if (
    typeof value !== "string" ||
    value.trim().length < 12 ||
    value.length > 1800
  )
    throw Error(
      "Write 12–1800 characters, including what is missing if you cannot answer yet.",
    );
  const b = s.breadth,
    q = s.plan.questions[b.index];
  b.answers.push({
    id: q.id,
    type: q.type,
    text: value.trim(),
    at: now,
    condition: b.sourceViews.includes(b.index)
      ? "source-assisted"
      : "perspective-prompted",
    kind: q.kind,
    learnerSignal: b.signalDraft || "not-recorded",
  });
  delete b.signalDraft;
  b.index++;
  delete s.drafts.investigate;
  s.updatedAt = now;
  s.events.push({
    type: "breadth_response_locked",
    questionId: q.id,
    at: now,
    seq: s.events.length + 1,
  });
  if (b.index === s.plan.questions.length)
    legacy.submit(
      s,
      `Completed ${b.index} inquiry questions. Individual responses and assistance conditions are preserved in the breadth evidence. Completion is not a mastery score.`,
      now,
    );
}
export function validateInquiry(s) {
  legacy.validateInquiry(s);
  validateFeedbackState(s);
  const hasPlan =
    s.plan.breadthPolicy !== undefined || s.plan.questions !== undefined;
  if (!hasPlan && s.breadth === undefined) return s;
  validateQuestionSet(s.plan, { reviewed: true });
  const b = s.breadth,
    total = s.plan.questions.length;
  if (
    b?.signalDraft !== undefined &&
    (s.phase !== "investigate" || !Object.hasOwn(learnerSignals, b.signalDraft))
  )
    throw Error("Saved learner reflection is damaged.");
  if (
    !b ||
    b.policy !== BREADTH_POLICY ||
    !Number.isInteger(b.index) ||
    b.index < 0 ||
    b.index > total ||
    !Array.isArray(b.answers) ||
    b.answers.length !== b.index ||
    !Array.isArray(b.sourceViews)
  )
    throw Error("Saved inquiry breadth is damaged.");
  if (
    (["prepare", "recall"].includes(s.phase) && b.index !== 0) ||
    (s.phase === "investigate" && b.index >= total) ||
    (["question", "revise", "waiting", "transfer", "complete"].includes(
      s.phase,
    ) &&
      b.index !== total)
  )
    throw Error("The full inquiry set must precede synthesis and revision.");
  if (
    new Set(b.sourceViews).size !== b.sourceViews.length ||
    b.sourceViews.some(
      (i) =>
        !Number.isInteger(i) ||
        i < 0 ||
        i >= total ||
        i > b.index ||
        ["prepare", "recall"].includes(s.phase),
    )
  )
    throw Error("Saved per-question support is damaged.");
  b.answers.forEach((a, i) => {
    const q = s.plan.questions[i];
    if (
      !a ||
      a.id !== q.id ||
      a.type !== q.type ||
      a.kind !== q.kind ||
      (a.learnerSignal !== undefined &&
        !Object.hasOwn(learnerSignals, a.learnerSignal)) ||
      typeof a.text !== "string" ||
      a.text.trim().length < 12 ||
      a.text.length > 1800 ||
      !Number.isSafeInteger(a.at) ||
      a.at < 0 ||
      a.at > 8640000000000000 ||
      a.condition !==
        (b.sourceViews.includes(i) ? "source-assisted" : "perspective-prompted")
    )
      throw Error(
        "Saved question response or assistance condition is damaged.",
      );
  });
  return s;
}
export function inquiryReport(s, full = false) {
  validateInquiry(s);
  const report = legacy.inquiryReport(s, full);
  if (s.feedback) report.feedback = feedbackReport(s, full);
  if (s.breadth)
    report.breadth = {
      policy: BREADTH_POLICY,
      planned: s.plan.questions.length,
      distinctTypes: new Set(s.plan.questions.map((q) => q.type)).size,
      completed: s.breadth.index,
      notice:
        "Breadth completion is not mastery. Investigation questions may require evidence beyond the passage; assess reasoning and evidence plans, not invented answers.",
      responses: s.breadth.answers.map(
        ({ id, type, condition, kind, at, text, learnerSignal }) => ({
          id,
          type,
          condition,
          kind,
          at,
          learnerSignal: learnerSignal || "not-recorded",
          ...(full ? { text } : {}),
        }),
      ),
      origins: s.plan.questions.map(({ id, origin, model }) => ({
        id,
        origin,
        model,
      })),
      delivery: {
        policy: DELIVERY_POLICY,
        sessions: inquirySessions.map(({ id, title, start, end }) => ({
          id,
          title,
          start,
          end: Math.min(end, s.plan.questions.length),
        })),
      },
    };
  return report;
}
