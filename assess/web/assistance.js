export const ASSISTANCE_POLICY = "nua-cognitive-support/1";

export const assistanceKinds = Object.freeze([
  "source_view", "ai_hint", "educator_help", "peer_help", "worked_example", "other",
]);

export const assistanceLevels = Object.freeze({
  0: Object.freeze({ id: 0, label: "Independent", description: "No assistance." }),
  1: Object.freeze({ id: 1, label: "Metacognitive cue", description: "Identify the point of difficulty or reasoning goal." }),
  2: Object.freeze({ id: 2, label: "Attention cue", description: "Point to a relevant feature without supplying the inference." }),
  3: Object.freeze({ id: 3, label: "Source scaffold", description: "Reveal relevant reviewed source evidence." }),
  4: Object.freeze({ id: 4, label: "Reasoning scaffold", description: "Provide a bounded sub-question or one reasoning step." }),
  5: Object.freeze({ id: 5, label: "Partial worked structure", description: "Provide an incomplete structure the learner must finish." }),
  6: Object.freeze({ id: 6, label: "Direct explanation", description: "Provide a direct explanation only after independent evidence is locked." }),
});

const learnerSignals = new Set(["not-recorded", "ready-to-explain", "unsure", "need-help"]);
function requireThat(ok, message) { if (!ok) throw Error(message); }
function validTime(v) { return Number.isSafeInteger(v) && v >= 0 && v <= 8640000000000000; }
function optionalText(value, max = 240) { return value === null || value === undefined || (typeof value === "string" && value.length <= max); }

export function validateAssistanceEvent(event) {
  requireThat(event && typeof event === "object", "Assistance event is required.");
  requireThat(typeof event.id === "string" && event.id.length > 0, "Assistance event ID is required.");
  requireThat(validTime(event.at), "Assistance event timestamp is invalid.");
  requireThat(typeof event.phase === "string" && event.phase.length > 0, "Assistance phase is required.");
  requireThat(optionalText(event.questionId, 128), "Invalid question ID.");
  requireThat(optionalText(event.constructId, 128), "Invalid construct ID.");
  requireThat(assistanceKinds.includes(event.kind), "Unsupported assistance kind.");
  requireThat(Number.isInteger(event.level) && assistanceLevels[event.level], "Assistance level must be 0–6.");
  requireThat(event.policy === ASSISTANCE_POLICY, "Unsupported assistance policy.");
  requireThat(optionalText(event.provider, 160), "Invalid assistance provider.");
  requireThat(optionalText(event.model, 200), "Invalid assistance model.");
  requireThat(optionalText(event.hintId, 128), "Invalid hint ID.");
  requireThat(typeof event.sourceAnchorUsed === "boolean", "sourceAnchorUsed must be explicit.");
  requireThat(typeof event.humanHelp === "boolean", "humanHelp must be explicit.");
  requireThat(learnerSignals.has(event.learnerSignal), "Invalid learner help signal.");
  requireThat(typeof event.attemptExistedBeforeAssistance === "boolean", "attemptExistedBeforeAssistance must be explicit.");
  requireThat(typeof event.independentResponseLocked === "boolean", "independentResponseLocked must be explicit.");
  if (event.level === 6)
    requireThat(event.attemptExistedBeforeAssistance && event.independentResponseLocked, "Direct explanation requires a prior locked independent attempt.");
  return event;
}

export function makeAssistanceEvent(input, now = Date.now(), idFactory = () => crypto.randomUUID()) {
  const event = {
    id: input.id || idFactory(), at: input.at ?? now, phase: input.phase,
    questionId: input.questionId ?? null, constructId: input.constructId ?? null,
    kind: input.kind, level: input.level, policy: ASSISTANCE_POLICY,
    provider: input.provider ?? null, model: input.model ?? null, hintId: input.hintId ?? null,
    sourceAnchorUsed: input.sourceAnchorUsed === true,
    humanHelp: input.humanHelp === true,
    learnerSignal: input.learnerSignal || "not-recorded",
    attemptExistedBeforeAssistance: input.attemptExistedBeforeAssistance === true,
    independentResponseLocked: input.independentResponseLocked === true,
  };
  return validateAssistanceEvent(event);
}

export function validateAssistanceEvents(events) {
  requireThat(Array.isArray(events), "Assistance events must be an array.");
  requireThat(events.length <= 1000, "Too many assistance events.");
  let previousAt = -1;
  const ids = new Set();
  for (const event of events) {
    validateAssistanceEvent(event);
    requireThat(!ids.has(event.id), "Duplicate assistance event ID.");
    requireThat(event.at >= previousAt, "Assistance events must remain chronological.");
    ids.add(event.id); previousAt = event.at;
  }
  return events;
}

export function recordAssistanceEvent(session, input, now = Date.now(), idFactory) {
  requireThat(session && typeof session === "object", "Session is required.");
  if (session.assistanceEvents === undefined) session.assistanceEvents = [];
  validateAssistanceEvents(session.assistanceEvents);
  const event = makeAssistanceEvent(input, now, idFactory);
  const next = [...session.assistanceEvents, event];
  validateAssistanceEvents(next);
  session.assistanceEvents = next;
  if (Number.isSafeInteger(session.updatedAt)) session.updatedAt = now;
  return event;
}

export function assistanceSummary(events = []) {
  validateAssistanceEvents(events);
  return {
    eventCount: events.length,
    kinds: [...new Set(events.map((e) => e.kind))],
    highestLevel: events.reduce((max, e) => Math.max(max, e.level), 0),
    usedSource: events.some((e) => e.kind === "source_view"),
    usedAI: events.some((e) => e.kind === "ai_hint"),
    usedHumanHelp: events.some((e) => e.humanHelp || e.kind === "educator_help" || e.kind === "peer_help"),
    directExplanationUsed: events.some((e) => e.level === 6),
  };
}

export function evidenceCondition(events = []) {
  const summary = assistanceSummary(events);
  if (!summary.eventCount) return "independent";
  const labels = [];
  if (summary.usedSource) labels.push("source");
  if (summary.usedAI) labels.push("ai");
  if (summary.usedHumanHelp) labels.push("human");
  if (!labels.length) labels.push("other");
  return `assisted:${labels.join("+")}`;
}
