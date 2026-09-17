import test from "node:test";
import assert from "node:assert/strict";
import {
  SCIENCE_PILOT_TYPES,
  assessmentPolicies,
  constructsForOperation,
  decorateTask,
  validateConstructCoverage,
  validateTaskAssessmentMetadata,
  selectSciencePilotTypes,
} from "../web/assessment-constructs.js";
import {
  ASSISTANCE_POLICY,
  assistanceSummary,
  makeAssistanceEvent,
  recordAssistanceEvent,
  validateAssistanceEvent,
} from "../web/assistance.js";
import {
  TRANSFER_POLICY,
  transferMetadata,
  validateTransferPair,
  isDelayedIndependentTransferAllowed,
} from "../web/transfer.js";
import * as aiEra from "../web/ai-era-core.js";

const source = "A fair test changes one factor while keeping other relevant conditions comparable. Repeated measurements show variation. If light and fertilizer both differ between plant groups, their separate effects cannot be isolated.";
const objective = "Explain how evidence from a fair comparison can distinguish competing explanations.";
const answer = "The comparison must isolate the planned difference and keep other relevant conditions comparable before a causal explanation is justified.";

function reviewedInput() {
  const plan = aiEra.templatePlan(objective, source, 1);
  plan.questions.forEach((question) => { question.reviewed = true; });
  return { title: "AI-era synthetic inquiry", pages: [{ page: 1, text: source }], plan, mode: "teacher-reviewed", id: "ai-era-synthetic" };
}

test("science pilot selection is construct-driven and includes experiment operations", () => {
  const selected = selectSciencePilotTypes(["definition", "evidence", "causality", "uncertainty", "counterexample", "measurement", "experiment", "assumptions", "comparison", "application", "prediction", "counterfactual", "synthesis"]);
  assert.equal(selected.length, SCIENCE_PILOT_TYPES.length);
  assert.ok(selected.length < 20);
  assert.ok(selected.includes("measurement"));
  assert.ok(selected.includes("experiment"));
});

test("question operation and assessment construct remain separate", () => {
  assert.ok(constructsForOperation("comparison").includes("evidence_evaluation"));
  assert.ok(constructsForOperation("comparison").includes("investigation_design"));
  const q1 = decorateTask({ type: "evidence", kind: "source" });
  const q2 = decorateTask({ type: "experiment", kind: "investigation" });
  const q3 = decorateTask({ type: "application", kind: "investigation" });
  validateConstructCoverage([q1, q2, q3]);
  validateTaskAssessmentMetadata(q2.assessment);
});

test("assessment policy versions are explicit", () => {
  assert.deepEqual(assessmentPolicies(), {
    constructPolicy: "nua-constructs/1", taskPolicy: "nua-ai-era-task/1", rubricPolicy: "nua-rubric/1", assistancePolicy: ASSISTANCE_POLICY, transferPolicy: TRANSFER_POLICY,
  });
});

test("source and AI assistance remain distinct evidence", () => {
  const session = { assistanceEvents: [], updatedAt: 1 };
  recordAssistanceEvent(session, { id: "source-1", phase: "investigate", questionId: "q1", constructId: "evidence_evaluation", kind: "source_view", level: 3, provider: "reviewed-source", sourceAnchorUsed: true, humanHelp: false, learnerSignal: "unsure", attemptExistedBeforeAssistance: true, independentResponseLocked: true }, 10, () => "source-1");
  recordAssistanceEvent(session, { id: "hint-1", phase: "investigate", questionId: "q1", constructId: "evidence_evaluation", kind: "ai_hint", level: 2, provider: "android-local", model: "LiteRT-LM", hintId: "h1", sourceAnchorUsed: false, humanHelp: false, learnerSignal: "unsure", attemptExistedBeforeAssistance: true, independentResponseLocked: true }, 11, () => "hint-1");
  const summary = assistanceSummary(session.assistanceEvents);
  assert.equal(summary.usedSource, true); assert.equal(summary.usedAI, true); assert.equal(summary.eventCount, 2);
});

test("direct explanation requires prior locked independent evidence", () => {
  assert.throws(() => makeAssistanceEvent({ id: "direct-1", phase: "investigate", kind: "ai_hint", level: 6, sourceAnchorUsed: true, humanHelp: false, learnerSignal: "need-help", attemptExistedBeforeAssistance: false, independentResponseLocked: false }, 10, () => "direct-1"), /prior locked independent attempt/);
  const valid = makeAssistanceEvent({ id: "direct-2", phase: "revise", kind: "ai_hint", level: 6, sourceAnchorUsed: true, humanHelp: false, learnerSignal: "need-help", attemptExistedBeforeAssistance: true, independentResponseLocked: true }, 11, () => "direct-2");
  assert.equal(validateAssistanceEvent(valid).level, 6);
});

test("parallel transfer requires same construct but changed surface context", () => {
  const initial = transferMetadata({ transferPairId: "fair-comparison-1", role: "initial", constructId: "investigation_design", surfaceContext: "plant growth", comparableCriterion: "Identifies the confound and proposes a fair comparison with relevant controls." });
  const parallel = transferMetadata({ transferPairId: "fair-comparison-1", role: "parallel", constructId: "investigation_design", surfaceContext: "cooling rates", comparableCriterion: "Identifies the confound and proposes a fair comparison with relevant controls." });
  assert.equal(validateTransferPair(initial, parallel), true);
  assert.equal(isDelayedIndependentTransferAllowed({ transferMode: "delayed-device-clock", assistanceEvents: [] }), true);
  assert.equal(isDelayedIndependentTransferAllowed({ transferMode: "delayed-device-clock", assistanceEvents: [{ kind: "ai_hint" }] }), false);
});

test("AI-era default uses construct coverage rather than a twenty-question quota", () => {
  const plan = aiEra.templatePlan(objective, source, 1);
  assert.equal(plan.questions.length, SCIENCE_PILOT_TYPES.length);
  assert.ok(plan.questions.length < 20);
  assert.equal(plan.assessmentSelectionPolicy, "science-construct-coverage/1");
  assert.ok(plan.questions.some((q) => q.type === "measurement"));
  assert.ok(plan.questions.some((q) => q.type === "experiment"));
  assert.deepEqual(new Set(plan.questions.map((q) => q.assessment.constructId)), new Set(["evidence_evaluation", "investigation_design", "transfer"]));
});

test("new sessions carry versioned policies and append-only assistance evidence", () => {
  const session = aiEra.createInquiry(reviewedInput(), 0);
  assert.equal(session.assessment.constructPolicy, "nua-constructs/1");
  assert.deepEqual(session.assistanceEvents, []);
  aiEra.beginRecall(session, 1); aiEra.submit(session, answer, 2);
  assert.equal(session.phase, "investigate");
  aiEra.exposeSource(session, 3);
  assert.equal(session.assistanceEvents.length, 1);
  assert.equal(session.assistanceEvents[0].kind, "source_view");
  aiEra.submit(session, answer, 4);
  assert.deepEqual(session.breadth.answers[0].assistanceEventIds, [session.assistanceEvents[0].id]);
  const report = aiEra.inquiryReport(session, true);
  assert.equal(report.assistance.summary.usedSource, true);
  assert.equal(report.breadth.responses[0].evidenceCondition, "assisted:source");
  assert.deepEqual(new Set(report.constructCoverage), new Set(["evidence_evaluation", "investigation_design", "transfer"]));
});

test("AI hints can be recorded without being mistaken for independent evidence", () => {
  const session = aiEra.createInquiry(reviewedInput(), 0);
  aiEra.beginRecall(session, 1); aiEra.submit(session, answer, 2);
  const q = session.plan.questions[0];
  const hint = aiEra.recordAIHint(session, { questionId: q.id, level: 2, provider: "android-local", model: "LiteRT-LM", hintId: "synthetic-hint", learnerSignal: "unsure", attemptExistedBeforeAssistance: true, independentResponseLocked: false }, 3);
  assert.equal(hint.kind, "ai_hint");
  aiEra.submit(session, answer, 4);
  const report = aiEra.inquiryReport(session, true);
  assert.equal(report.assistance.summary.usedAI, true);
  assert.equal(report.breadth.responses[0].evidenceCondition, "assisted:ai");
  assert.equal(session.breadth.answers[0].condition, "perspective-prompted");
});
