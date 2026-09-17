// AI-era assessment adapter. Existing nua-mangal/1 records remain readable through
// mangal-core; new sessions add construct, assistance and transfer metadata.
import * as core from "./mangal-core.js";
import { makeQuestion } from "./inquiry-questions.js";
import {
  SCIENCE_PILOT_TYPES,
  assessmentPolicies,
  decorateTask,
  validateAssessmentPolicies,
  validateConstructCoverage,
  validateTaskAssessmentMetadata,
  constructCoverage,
} from "./assessment-constructs.js";
import {
  recordAssistanceEvent,
  validateAssistanceEvents,
  assistanceSummary,
  evidenceCondition,
} from "./assistance.js";

export * from "./mangal-core.js";
export { constructs, SCIENCE_PILOT_TYPES } from "./assessment-constructs.js";
export { assistanceLevels, assistanceKinds, ASSISTANCE_POLICY } from "./assistance.js";
export { TRANSFER_POLICY, transferMetadata, validateTransferPair } from "./transfer.js";

export const AI_ERA_SELECTION_POLICY = "science-construct-coverage/1";

export function templatePlan(...args) {
  const legacyBreadth = core.templatePlan(...args);
  const byType = new Map(legacyBreadth.questions.map((q) => [q.type, q]));
  const questions = SCIENCE_PILOT_TYPES.map((type, index) => {
    const question = structuredClone(byType.get(type) || makeQuestion(type, legacyBreadth, `q${index + 1}`));
    question.id = `q${index + 1}`;
    return decorateTask(question);
  });
  return {
    ...legacyBreadth,
    selectionPolicy: "objective-coverage/1",
    assessmentSelectionPolicy: AI_ERA_SELECTION_POLICY,
    coverageReason:
      "Select distinct probes because together they cover evidence evaluation, investigation design and transfer; do not add filler to reach a fixed count.",
    questions,
  };
}

export function validatePlan(plan, pages) {
  core.validatePlan(plan, pages);
  if (plan.assessmentSelectionPolicy === AI_ERA_SELECTION_POLICY) {
    if (plan.selectionPolicy !== "objective-coverage/1")
      throw Error("AI-era plan must use the construct-coverage compatibility selection policy.");
    plan.questions.forEach((q) => {
      if (!q.assessment) throw Error(`Question ${q.id} is missing assessment metadata.`);
      validateTaskAssessmentMetadata(q.assessment);
    });
    validateConstructCoverage(plan.questions);
  }
  return plan;
}

export function validatePreparation(preparation) {
  core.validatePreparation(preparation);
  if (preparation.plan) validatePlan(preparation.plan, preparation.pages);
  return preparation;
}

export function createInquiry(input, now = Date.now()) {
  validatePlan(input.plan, input.pages);
  const session = core.createInquiry(input, now);
  session.assessment = assessmentPolicies();
  session.assistanceEvents = [];
  session.assessmentSelectionPolicy = input.plan.assessmentSelectionPolicy || null;
  return session;
}

function currentQuestion(session) {
  if (session.phase !== "investigate" || !session.breadth) return null;
  return session.plan.questions[session.breadth.index] || null;
}

export function exposeSource(session, now = Date.now()) {
  const question = currentQuestion(session);
  const phase = session.phase;
  core.exposeSource(session, now);
  if (session.assistanceEvents) {
    recordAssistanceEvent(session, {
      phase,
      questionId: question?.id ?? null,
      constructId: question?.assessment?.constructId ?? null,
      kind: "source_view",
      level: 3,
      provider: "reviewed-source",
      sourceAnchorUsed: true,
      humanHelp: false,
      learnerSignal: session.breadth?.signalDraft || "not-recorded",
      attemptExistedBeforeAssistance: Boolean(session.drafts?.[phase]?.trim()),
      independentResponseLocked: false,
    }, now);
  }
}

export function recordAIHint(session, input, now = Date.now()) {
  const question = input.questionId
    ? session.plan.questions.find((q) => q.id === input.questionId)
    : currentQuestion(session);
  if (!question && session.phase === "investigate") throw Error("No active assessment question for this hint.");
  return recordAssistanceEvent(session, {
    phase: session.phase,
    questionId: question?.id ?? input.questionId ?? null,
    constructId: question?.assessment?.constructId ?? input.constructId ?? null,
    kind: "ai_hint",
    level: input.level,
    provider: input.provider || "android-local",
    model: input.model ?? null,
    hintId: input.hintId ?? null,
    sourceAnchorUsed: input.sourceAnchorUsed === true,
    humanHelp: false,
    learnerSignal: input.learnerSignal || session.breadth?.signalDraft || "not-recorded",
    attemptExistedBeforeAssistance: input.attemptExistedBeforeAssistance === true,
    independentResponseLocked: input.independentResponseLocked === true,
  }, now);
}

export function recordDeclaredHumanHelp(session, input, now = Date.now()) {
  const kind = input.kind === "peer_help" ? "peer_help" : "educator_help";
  return recordAssistanceEvent(session, {
    phase: session.phase,
    questionId: input.questionId ?? currentQuestion(session)?.id ?? null,
    constructId: input.constructId ?? currentQuestion(session)?.assessment?.constructId ?? null,
    kind,
    level: input.level ?? 4,
    provider: kind,
    sourceAnchorUsed: input.sourceAnchorUsed === true,
    humanHelp: true,
    learnerSignal: input.learnerSignal || "need-help",
    attemptExistedBeforeAssistance: input.attemptExistedBeforeAssistance === true,
    independentResponseLocked: input.independentResponseLocked === true,
  }, now);
}

export function submit(session, value, now = Date.now()) {
  const question = currentQuestion(session);
  const assistanceBefore = question && session.assistanceEvents
    ? session.assistanceEvents.filter((event) => event.questionId === question.id).map((event) => event.id)
    : [];
  core.submit(session, value, now);
  if (question && session.breadth?.answers?.length) {
    const answer = session.breadth.answers.at(-1);
    if (answer.id === question.id) answer.assistanceEventIds = assistanceBefore;
  }
}

export function validateInquiry(session) {
  core.validateInquiry(session);
  if (session.assessment !== undefined) validateAssessmentPolicies(session.assessment);
  if (session.assistanceEvents !== undefined) validateAssistanceEvents(session.assistanceEvents);
  if (session.assessmentSelectionPolicy === AI_ERA_SELECTION_POLICY) {
    validateConstructCoverage(session.plan.questions);
    session.plan.questions.forEach((q) => validateTaskAssessmentMetadata(q.assessment));
  }
  if (session.breadth?.answers) {
    const knownIds = new Set((session.assistanceEvents || []).map((e) => e.id));
    for (const answer of session.breadth.answers) {
      if (answer.assistanceEventIds !== undefined) {
        if (!Array.isArray(answer.assistanceEventIds) || answer.assistanceEventIds.some((id) => !knownIds.has(id)))
          throw Error("Saved answer assistance links are damaged.");
      }
    }
  }
  return session;
}

export function inquiryReport(session, full = false) {
  validateInquiry(session);
  const report = core.inquiryReport(session, full);
  if (session.assessment) report.assessment = structuredClone(session.assessment);
  if (session.assessmentSelectionPolicy) {
    report.assessmentSelectionPolicy = session.assessmentSelectionPolicy;
    report.constructCoverage = constructCoverage(session.plan.questions);
  }
  if (session.assistanceEvents) {
    report.assistance = {
      summary: assistanceSummary(session.assistanceEvents),
      ...(full ? { events: structuredClone(session.assistanceEvents) } : {}),
    };
    if (report.breadth?.responses) {
      report.breadth.responses = report.breadth.responses.map((response) => {
        const events = session.assistanceEvents.filter((event) => event.questionId === response.id);
        return { ...response, evidenceCondition: evidenceCondition(events) };
      });
    }
  }
  return report;
}
