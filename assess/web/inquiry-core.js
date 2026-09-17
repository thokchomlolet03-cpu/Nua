export const INQUIRY_SCHEMA = "nua-mangal/1";
export const POLICY = "focused-inquiry-1";
export const KEY = "nua-mangal-session-v1";
export const DAY = 86400000;
export const phases = [
  "prepare",
  "recall",
  "investigate",
  "question",
  "revise",
  "waiting",
  "transfer",
  "complete",
];
export const perspectives = {
  mechanism: {
    title: "Mechanism",
    why: "Examine how the described process produces its result.",
    prompt:
      "Explain the sequence that produces the result. Which step is necessary, and why?",
  },
  evidence: {
    title: "Evidence",
    why: "Connect a claim to its supporting evidence and identify what remains uncertain.",
    prompt:
      "Choose one claim. What evidence supports it, and what does that evidence not establish?",
  },
  causality: {
    title: "Causality",
    why: "Distinguish a proposed cause from other explanations.",
    prompt:
      "Identify a proposed cause and an alternative explanation. What comparison could distinguish them?",
  },
  limits: {
    title: "Boundaries",
    why: "Examine the conditions under which an explanation applies.",
    prompt:
      "Under what conditions does this explanation apply? Describe a case that would test its limits.",
  },
  perspective: {
    title: "Perspective",
    why: "Consider how another interpretation changes the argument.",
    prompt:
      "Describe another interpretation of the same material. What evidence would help evaluate it?",
  },
};
const plain = (x) =>
  x !== null &&
  typeof x === "object" &&
  !Array.isArray(x) &&
  Object.getPrototypeOf(x) === Object.prototype;
const str = (x, min, max) =>
  typeof x === "string" && x.trim().length >= min && x.length <= max;
const time = (x) => Number.isSafeInteger(x) && x >= 0 && x <= 8640000000000000;
function requireThat(ok, message) {
  if (!ok) throw Error(message);
}
export function normalizePages(pages) {
  requireThat(
    Array.isArray(pages) && pages.length > 0 && pages.length <= 80,
    "Use 1–80 pages. Split longer documents into lessons.",
  );
  const result = pages.map((p, i) => {
    requireThat(
      plain(p) && typeof p.text === "string",
      "Invalid extracted page.",
    );
    return { page: i + 1, text: p.text.replace(/\u0000/g, "").trim() };
  });
  requireThat(
    result.reduce((n, p) => n + p.text.length, 0) <= 180000,
    "This document has too much extracted text. Split it into lessons.",
  );
  requireThat(
    result.some((p) => p.text.length >= 40),
    "No usable text found. Scanned pages need OCR outside this prototype; paste checked text instead.",
  );
  return result;
}
export function suggestPerspective(text) {
  if (/cause|effect|experiment|control|कारण|प्रभाव/i.test(text))
    return "causality";
  if (/process|step|cycle|mechanism|प्रक्रिया/i.test(text)) return "mechanism";
  if (/argument|interpret|viewpoint|histor|दृष्टिकोण/i.test(text))
    return "perspective";
  if (/condition|exception|limit|सीमा/i.test(text)) return "limits";
  return "evidence";
}
export function templatePlan(
  objective,
  excerpt,
  page,
  perspective = suggestPerspective(excerpt),
) {
  requireThat(
    str(objective, 12, 300),
    "Write one specific learning objective (12–300 characters).",
  );
  requireThat(
    str(excerpt, 40, 6000) && Number.isInteger(page) && page > 0,
    "Choose a checked source passage of 40–6000 characters.",
  );
  requireThat(
    Object.hasOwn(perspectives, perspective),
    "Choose a supported perspective.",
  );
  return {
    objective: objective.trim(),
    page,
    quote: excerpt.trim(),
    perspective,
    reason: perspectives[perspective].why,
    recall: `Without reopening the passage, explain what you remember that addresses this objective: ${objective.trim()}`,
    investigate: `${perspectives[perspective].prompt} Keep your response connected to: ${objective.trim()}`,
    transfer: `Describe a different situation where the idea in this objective might apply: ${objective.trim()} Explain your reasoning and one condition that could change the result. This learner-created application is not a calibrated transfer test.`,
    rubric:
      "Check accuracy against the source; the connection between claim and evidence; the relevance of the chosen perspective; and whether the proposed question or application can be justified. A fluent explanation alone is not evidence of understanding.",
    origin: "rule-based-template",
    model: null,
    policy: POLICY,
  };
}
export function validatePlan(p, pages) {
  requireThat(
    plain(p) &&
      str(p.objective, 12, 300) &&
      Object.hasOwn(perspectives, p.perspective),
    "Invalid objective or perspective.",
  );
  requireThat(
    Number.isInteger(p.page) &&
      p.page >= 1 &&
      p.page <= pages.length &&
      str(p.quote, 40, 6000) &&
      pages[p.page - 1].text.includes(p.quote),
    "The source quotation must occur exactly on the referenced page.",
  );
  for (const field of ["reason", "recall", "investigate", "transfer", "rubric"])
    requireThat(
      str(p[field], 12, 1800),
      `Review the ${field} field (12–1800 characters).`,
    );
  requireThat(
    ["rule-based-template", "local-ai-draft", "gemini-ai-draft"].includes(p.origin) &&
      p.policy === POLICY &&
      (p.model === null || str(p.model, 1, 200)),
    "Invalid plan provenance.",
  );
  return p;
}
export function createInquiry(
  { title, pages, plan, mode, warnings = [], id = crypto.randomUUID() },
  now = Date.now(),
) {
  pages = normalizePages(pages);
  validatePlan(plan, pages);
  requireThat(
    str(title, 1, 160) &&
      ["teacher-reviewed", "self-study-provisional"].includes(mode),
    "Choose a title and review condition.",
  );
  return {
    schema: INQUIRY_SCHEMA,
    policy: POLICY,
    id,
    title,
    pages,
    coverageWarnings: [...warnings],
    plan: structuredClone(plan),
    approval: { mode, at: now, identityVerified: false, sourceChecked: true },
    phase: "prepare",
    paused: false,
    createdAt: now,
    updatedAt: now,
    responses: {},
    drafts: {},
    sourceViews: {},
    parked: [],
    events: [],
    dueAt: null,
    transferMode: null,
    reflection: null,
    review: null,
  };
}
function record(s, type, data = {}, now = Date.now()) {
  s.events.push({ ...data, type, at: now, seq: s.events.length + 1 });
  s.updatedAt = now;
}
export function beginRecall(s, now = Date.now()) {
  requireThat(s.phase === "prepare", "Prepare from the source first.");
  s.phase = "recall";
  record(s, "recall_started", {}, now);
}
export function exposeSource(s, now = Date.now()) {
  requireThat(
    ["recall", "investigate", "question"].includes(s.phase),
    "Source support is not available in this stage.",
  );
  s.sourceViews[s.phase] = true;
  record(s, "source_support", { phase: s.phase }, now);
}
export function submit(s, text, now = Date.now()) {
  requireThat(
    ["recall", "investigate", "question", "revise", "transfer"].includes(
      s.phase,
    ) && !s.responses[s.phase],
    "This response is locked or unavailable.",
  );
  requireThat(
    str(text, 12, 1800),
    "Write 12–1800 characters, or use the available support during practice.",
  );
  const phase = s.phase;
  s.responses[phase] = {
    text: text.trim(),
    at: now,
    condition:
      phase === "revise"
        ? "source-and-rubric-review"
        : s.sourceViews[phase]
          ? "source-assisted"
          : phase === "transfer"
            ? "unassisted-self-report"
            : phase === "recall"
              ? "closed-source-self-report"
              : "perspective-prompted",
  };
  delete s.drafts[phase];
  record(s, "response_locked", { phase }, now);
  s.phase = {
    recall: "investigate",
    investigate: "question",
    question: "revise",
    revise: "waiting",
    transfer: "complete",
  }[phase];
  if (phase === "revise") s.dueAt = now + DAY;
}
export function returnForTransfer(s, demo = false, now = Date.now()) {
  requireThat(s.phase === "waiting", "Finish practice first.");
  requireThat(now >= s.dueAt || demo, "The delayed return is not due yet.");
  s.transferMode = now >= s.dueAt ? "delayed-device-clock" : "immediate-demo";
  s.phase = "transfer";
  record(s, "transfer_started", { mode: s.transferMode }, now);
}
export function parkQuestion(s, text, now = Date.now()) {
  requireThat(
    ["recall", "investigate", "question", "revise"].includes(s.phase),
    "Save related questions during practice only.",
  );
  requireThat(
    s.parked.length < 3 && str(text, 3, 400),
    "Save up to three later questions of 3–400 characters.",
  );
  s.parked.push({ text: text.trim(), at: now });
  record(s, "question_parked", {}, now);
}
export function saveReflection(s, value, now = Date.now()) {
  requireThat(
    s.phase === "revise" &&
      ["corrected-error", "added-evidence", "still-unsure"].includes(value),
    "Choose a reflection during revision.",
  );
  s.reflection = value;
  record(s, "revision_self_report", { value }, now);
}
export function saveReview(s, value, note, now = Date.now()) {
  requireThat(
    s.phase === "complete" &&
      [
        "needs-follow-up",
        "partly-supported",
        "supported-in-this-response",
      ].includes(value) &&
      typeof note === "string" &&
      note.length <= 1000,
    "Review after the independent application.",
  );
  s.review = { value, note, at: now, identityVerified: false };
  record(s, "educator_review", { value }, now);
}
export function validateInquiry(s) {
  requireThat(
    plain(s) &&
      s.schema === INQUIRY_SCHEMA &&
      s.policy === POLICY &&
      str(s.id, 1, 128) &&
      str(s.title, 1, 160) &&
      phases.includes(s.phase) &&
      time(s.createdAt) &&
      time(s.updatedAt),
    "Saved inquiry is incompatible. Preserve a copy before replacing it.",
  );
  requireThat(
    typeof s.paused === "boolean" &&
      Array.isArray(s.coverageWarnings) &&
      s.coverageWarnings.length <= 100 &&
      s.coverageWarnings.every((w) => str(w, 1, 1000)),
    "Saved pause or coverage information is damaged.",
  );
  const normalized = normalizePages(s.pages);
  requireThat(
    JSON.stringify(normalized) === JSON.stringify(s.pages),
    "Saved source pages are damaged.",
  );
  validatePlan(s.plan, s.pages);
  requireThat(
    plain(s.approval) &&
      ["teacher-reviewed", "self-study-provisional"].includes(
        s.approval.mode,
      ) &&
      time(s.approval.at) &&
      s.approval.identityVerified === false &&
      s.approval.sourceChecked === true,
    "Saved review condition is damaged.",
  );
  requireThat(
    [s.responses, s.drafts, s.sourceViews].every(plain) &&
      Array.isArray(s.events) &&
      Array.isArray(s.parked),
    "Saved inquiry fields are damaged.",
  );
  const expected = {
    prepare: [],
    recall: [],
    investigate: ["recall"],
    question: ["recall", "investigate"],
    revise: ["recall", "investigate", "question"],
    waiting: ["recall", "investigate", "question", "revise"],
    transfer: ["recall", "investigate", "question", "revise"],
    complete: ["recall", "investigate", "question", "revise", "transfer"],
  }[s.phase];
  requireThat(
    Object.keys(s.responses).length === expected.length &&
      expected.every(
        (k) =>
          plain(s.responses[k]) &&
          str(s.responses[k].text, 12, 1800) &&
          time(s.responses[k].at) &&
          [
            "source-and-rubric-review",
            "source-assisted",
            "unassisted-self-report",
            "closed-source-self-report",
            "perspective-prompted",
          ].includes(s.responses[k].condition),
      ),
    "Saved response sequence is damaged.",
  );
  requireThat(
    Object.entries(s.drafts).every(
      ([k, v]) => k === s.phase && typeof v === "string" && v.length <= 1800,
    ),
    "Saved draft is damaged.",
  );
  requireThat(
    Object.entries(s.sourceViews).every(
      ([k, v]) =>
        ["recall", "investigate", "question"].includes(k) && v === true,
    ),
    "Saved support record is damaged.",
  );
  requireThat(
    s.events.every(
      (e, i) => plain(e) && str(e.type, 1, 80) && time(e.at) && e.seq === i + 1,
    ),
    "Saved event sequence is damaged.",
  );
  requireThat(
    s.parked.length <= 3 &&
      s.parked.every((p) => plain(p) && str(p.text, 3, 400) && time(p.at)),
    "Saved later questions are damaged.",
  );
  requireThat(
    s.reflection === null ||
      ["corrected-error", "added-evidence", "still-unsure"].includes(
        s.reflection,
      ),
    "Saved reflection is damaged.",
  );
  requireThat(
    s.review === null ||
      (s.phase === "complete" &&
        plain(s.review) &&
        [
          "needs-follow-up",
          "partly-supported",
          "supported-in-this-response",
        ].includes(s.review.value) &&
        typeof s.review.note === "string" &&
        s.review.note.length <= 1000 &&
        time(s.review.at) &&
        s.review.identityVerified === false),
    "Saved educator annotation is damaged.",
  );
  requireThat(
    ["waiting", "transfer", "complete"].includes(s.phase)
      ? time(s.dueAt) && s.dueAt === s.responses.revise.at + DAY
      : s.dueAt === null,
    "Saved return time is damaged.",
  );
  requireThat(
    ["transfer", "complete"].includes(s.phase)
      ? ["immediate-demo", "delayed-device-clock"].includes(s.transferMode)
      : s.transferMode === null,
    "Saved transfer condition is damaged.",
  );
  return s;
}
export function inquiryReport(s, full = false) {
  validateInquiry(s);
  return {
    schema: INQUIRY_SCHEMA,
    policy: POLICY,
    sessionId: s.id,
    phase: s.phase,
    approval: { ...s.approval },
    perspective: s.plan.perspective,
    origin: s.plan.origin,
    model: s.plan.model,
    transferMode: s.transferMode,
    dueAt: s.dueAt,
    notice:
      "Prototype, not a validated score. Timing and outside assistance are unverified. Teacher review is not authenticated. Self-study setup exposes questions/rubric. Transfer is an application task, not an equated learning-gain measure.",
    responses: Object.fromEntries(
      Object.entries(s.responses).map(([k, r]) => [
        k,
        { at: r.at, condition: r.condition, ...(full ? { text: r.text } : {}) },
      ]),
    ),
    reflection: s.reflection,
    review: s.review
      ? {
          value: s.review.value,
          at: s.review.at,
          identityVerified: false,
          ...(full ? { note: s.review.note } : {}),
        }
      : null,
    coverageWarningCount: s.coverageWarnings.length,
    includesRawMaterial: full,
    ...(full
      ? {
          title: s.title,
          pages: s.pages,
          plan: s.plan,
          coverageWarnings: s.coverageWarnings,
          parked: s.parked,
          events: s.events,
        }
      : {}),
  };
}
export function validatePreparation(p) {
  requireThat(
    plain(p) &&
      typeof p.title === "string" &&
      p.title.length <= 160 &&
      typeof p.objective === "string" &&
      p.objective.length <= 300 &&
      typeof p.excerpt === "string" &&
      p.excerpt.length <= 6000 &&
      Array.isArray(p.pages) &&
      Array.isArray(p.warnings) &&
      p.warnings.length <= 100 &&
      p.warnings.every((w) => str(w, 1, 1000)) &&
      ["teacher-reviewed", "self-study-provisional"].includes(p.mode),
    "Saved preparation is damaged.",
  );
  if (p.pages.length) {
    normalizePages(p.pages);
    requireThat(
      Number.isInteger(p.page) && p.page >= 1 && p.page <= p.pages.length,
      "Saved page selection is damaged.",
    );
  }
  if (p.plan) {
    // Incomplete edited question fields are valid drafts, not approvable plans.
    const complete = { ...p.plan };
    for (const k of ["reason", "recall", "investigate", "transfer", "rubric"]) {
      requireThat(
        typeof complete[k] === "string" && complete[k].length <= 1800,
        "Saved plan draft is damaged.",
      );
      if (complete[k].trim().length < 12)
        complete[k] = "Temporary draft awaiting review.";
    }
    validatePlan(complete, p.pages);
  }
  return p;
}
