export const FEEDBACK_POLICY = "evidence-to-action/1";
export const interpretations = {
  "needs-clarification": "Needs clarification",
  "partly-supported": "Partly supported by this response",
  supported: "Supported by this response",
};
export const learnerSignals = {
  "not-recorded": "Choose if useful",
  "ready-to-explain": "I can explain my reasoning",
  unsure: "I am unsure about part of this",
  "need-help": "I need help to continue understanding",
};
const validText = (v, min, max) =>
  typeof v === "string" && v.trim().length >= min && v.length <= max;
const validTime = (v) =>
  Number.isSafeInteger(v) && v >= 0 && v <= 8640000000000000;
function requireThat(ok, message) {
  if (!ok) throw Error(message);
}
export function validateFeedbackState(s) {
  if (s.feedbackDrafts !== undefined) {
    requireThat(
      s.phase === "complete" &&
        s.breadth &&
        s.feedbackDrafts &&
        !Array.isArray(s.feedbackDrafts) &&
        typeof s.feedbackDrafts === "object",
      "Invalid feedback draft.",
    );
    for (const [id, d] of Object.entries(s.feedbackDrafts))
      requireThat(
        s.breadth.answers.some((a) => a.id === id) &&
          d &&
          Object.hasOwn(interpretations, d.interpretation) &&
          validText(d.evidence, 0, 500) &&
          validText(d.nextStep, 0, 1000) &&
          validText(d.successCriterion, 0, 1000),
        "Invalid feedback draft fields.",
      );
  }
  if (s.feedback === undefined) return;
  const f = s.feedback;
  requireThat(
    s.phase === "complete" &&
      s.breadth &&
      f &&
      f.policy === FEEDBACK_POLICY &&
      Array.isArray(f.entries) &&
      f.entries.length <= 100 &&
      Array.isArray(f.followups) &&
      f.followups.length <= f.entries.length,
    "Saved feedback record is damaged.",
  );
  f.entries.forEach((e, i) => {
    const answer = s.breadth.answers.find((a) => a.id === e?.questionId);
    requireThat(
      e &&
        e.id === i + 1 &&
        answer &&
        Object.hasOwn(interpretations, e.interpretation) &&
        validText(e.evidence, 12, 500) &&
        answer.text.includes(e.evidence) &&
        validText(e.nextStep, 12, 1000) &&
        validText(e.successCriterion, 12, 1000) &&
        validTime(e.at) &&
        e.identityVerified === false,
      "Feedback must cite an exact passage from an existing response and include an actionable next step and check.",
    );
  });
  requireThat(
    new Set(f.followups.map((r) => r?.feedbackId)).size === f.followups.length,
    "Duplicate feedback follow-up.",
  );
  f.followups.forEach((r) =>
    requireThat(
      r &&
        f.entries.some((e) => e.id === r.feedbackId) &&
        validText(r.text, 12, 1800) &&
        ["can-explain", "still-need-help"].includes(r.signal) &&
        validTime(r.at) &&
        r.condition === "after-educator-feedback",
      "Saved learner follow-up is damaged.",
    ),
  );
}
export function addFeedback(s, input, now = Date.now()) {
  validateFeedbackState(s);
  const current = s.feedback || {
    policy: FEEDBACK_POLICY,
    entries: [],
    followups: [],
  };
  const entry = {
    id: current.entries.length + 1,
    questionId: input.questionId,
    interpretation: input.interpretation,
    evidence: input.evidence?.trim(),
    nextStep: input.nextStep?.trim(),
    successCriterion: input.successCriterion?.trim(),
    at: now,
    identityVerified: false,
  };
  const candidate = { ...current, entries: [...current.entries, entry] };
  validateFeedbackState({ ...s, feedback: candidate });
  s.feedback = candidate;
  if (s.feedbackDrafts) delete s.feedbackDrafts[entry.questionId];
  s.updatedAt = now;
}
export function saveFeedbackDraft(s, questionId, d) {
  const drafts = {
    ...(s.feedbackDrafts || {}),
    [questionId]: {
      interpretation: d.interpretation,
      evidence: d.evidence,
      nextStep: d.nextStep,
      successCriterion: d.successCriterion,
    },
  };
  validateFeedbackState({ ...s, feedbackDrafts: drafts });
  s.feedbackDrafts = drafts;
}
export function addFollowup(s, feedbackId, text, signal, now = Date.now()) {
  validateFeedbackState(s);
  requireThat(
    s.feedback &&
      !s.feedback.followups.some((r) => r.feedbackId === feedbackId),
    "This follow-up is unavailable or already recorded.",
  );
  const candidate = {
    ...s.feedback,
    followups: [
      ...s.feedback.followups,
      {
        feedbackId,
        text: typeof text === "string" ? text.trim() : text,
        signal,
        at: now,
        condition: "after-educator-feedback",
      },
    ],
  };
  validateFeedbackState({ ...s, feedback: candidate });
  s.feedback = candidate;
  s.updatedAt = now;
}
export function feedbackReport(s, full = false) {
  validateFeedbackState(s);
  if (!s.feedback) return undefined;
  return {
    policy: FEEDBACK_POLICY,
    entries: s.feedback.entries.map((e) => ({
      id: e.id,
      questionId: e.questionId,
      interpretation: e.interpretation,
      at: e.at,
      identityVerified: false,
      ...(full
        ? {
            evidence: e.evidence,
            nextStep: e.nextStep,
            successCriterion: e.successCriterion,
          }
        : {}),
    })),
    followups: s.feedback.followups.map((r) => ({
      feedbackId: r.feedbackId,
      signal: r.signal,
      at: r.at,
      condition: r.condition,
      ...(full ? { text: r.text } : {}),
    })),
  };
}
