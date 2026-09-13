// Question types are reasoning operations, not evidence of universal human traits.
export const BREADTH_POLICY = "mangal-breadth/1";
export const MIN_TYPES = 20;
export const MAX_QUESTIONS = 40;
export const questionTypes = Object.fromEntries(
  [
    [
      "definition",
      "Definition",
      "Choose an important term. Define it precisely and distinguish it from a nearby idea.",
    ],
    [
      "reconstruction",
      "Reconstruction",
      "Reconstruct the main explanation in your own words without copying its sentences.",
    ],
    [
      "mechanism",
      "Mechanism",
      "Trace how the described parts or steps produce the result. Identify a link the passage does not explain.",
    ],
    [
      "evidence",
      "Evidence",
      "Identify a claim and the evidence supplied for it. Distinguish an assertion from supporting evidence.",
    ],
    [
      "assumptions",
      "Assumptions",
      "Identify an unstated assumption. Explain how the reasoning depends on it.",
    ],
    [
      "prerequisites",
      "Prerequisites",
      "What earlier idea must someone understand to follow this explanation? Explain the connection.",
    ],
    [
      "comparison",
      "Comparison",
      "Compare two ideas, cases or conditions in the material. Which difference matters for the objective?",
    ],
    [
      "classification",
      "Classification",
      "Propose a useful way to group examples from this topic. State the rule and explain a difficult case.",
    ],
    [
      "causality",
      "Causality",
      "Does the material establish a cause, describe an association, or neither? Explain what permits that conclusion.",
    ],
    [
      "prediction",
      "Prediction",
      "Derive a prediction from the material. State the conditions and what observation would challenge it.",
    ],
    [
      "counterfactual",
      "Counterfactual",
      "Change one stated condition. Explain what would change and which parts of your answer are conjecture.",
    ],
    [
      "boundaries",
      "Boundary conditions",
      "Identify where the explanation applies and a situation where applying it would require more evidence.",
    ],
    [
      "counterexample",
      "Counterexample",
      "Propose a possible counterexample to a claim. Explain whether it really contradicts the claim or falls outside its scope.",
    ],
    [
      "error",
      "Error diagnosis",
      "Construct a plausible mistaken interpretation, label it as mistaken, and correct the exact reasoning error.",
    ],
    [
      "uncertainty",
      "Uncertainty",
      "Separate what the passage establishes from what remains unknown. What missing information matters most?",
    ],
    [
      "representation",
      "Representation",
      "Describe a diagram, relationship map or ordered sequence for the idea. Explain what that representation leaves out.",
    ],
    [
      "interpretation",
      "Alternative interpretation",
      "Offer another interpretation of the same information. What evidence could distinguish the interpretations?",
    ],
    [
      "application",
      "Application",
      "Propose a different situation where the idea could apply. Justify the mapping and identify a limit.",
    ],
    [
      "synthesis",
      "Synthesis",
      "Connect the main parts into one explanation of the objective. Resolve a tension or identify a remaining gap.",
    ],
    [
      "inquiry",
      "Question generation",
      "Ask an important question not answered by the passage. Explain why it matters and what evidence could answer it.",
    ],
    [
      "measurement",
      "Measurement",
      "How could the relevant property or outcome be observed or measured? Explain a limitation of that method.",
    ],
    [
      "experiment",
      "Testing explanations",
      "Design a comparison or investigation to distinguish two explanations. State what evidence would count against each.",
    ],
    [
      "tradeoff",
      "Trade-offs",
      "Identify a decision related to the material. What competing criteria would change the preferred choice?",
    ],
    [
      "provenance",
      "Source provenance",
      "What would you need to know about the origin of this material to evaluate its reliability? Distinguish supplied information from missing information.",
    ],
  ].map(([id, title, prompt]) => [id, { title, prompt }]),
);
export const normalizeQuestion = (s) =>
  s.toLowerCase().replace(/[^\p{L}\p{N}]/gu, "");
function check(ok, msg) {
  if (!ok) throw Error(msg);
}
const text = (v, min, max) =>
  typeof v === "string" && v.trim().length >= min && v.length <= max;
export function makeQuestion(type, plan, id = crypto.randomUUID()) {
  check(Object.hasOwn(questionTypes, type), "Unknown inquiry type.");
  return {
    id,
    type,
    prompt: `${questionTypes[type].prompt} Keep the inquiry connected to: ${plan.objective}`,
    relevance: `Review whether this ${questionTypes[type].title.toLowerCase()} question advances the selected objective. This is an unreviewed template, not semantic analysis.`,
    criterion:
      "Look for a justified connection to the source and a clear distinction between supplied evidence, inference and missing information. Adapt these criteria to this question before approval.",
    anchor: plan.quote.slice(0, 240),
    page: plan.page,
    kind: [
      "definition",
      "reconstruction",
      "evidence",
      "comparison",
      "synthesis",
    ].includes(type)
      ? "source"
      : "investigation",
    reviewed: false,
    origin: "template",
    model: null,
  };
}
export function expandPlan(plan) {
  return {
    ...plan,
    breadthPolicy: BREADTH_POLICY,
    questions: Object.keys(questionTypes)
      .slice(0, MIN_TYPES)
      .map((type, i) => makeQuestion(type, plan, `q${i + 1}`)),
  };
}
export function validateQuestion(
  q,
  plan,
  { draft = false, reviewed = false } = {},
) {
  check(
    q &&
      typeof q === "object" &&
      text(q.id, 1, 100) &&
      Object.hasOwn(questionTypes, q.type),
    "Invalid question identity or type.",
  );
  for (const field of ["prompt", "relevance", "criterion"])
    check(
      text(q[field], draft ? 0 : 12, 1800),
      `Review question ${q.id}: ${field} must be 12–1800 characters.`,
    );
  check(
    Number.isInteger(q.page) &&
      q.page === plan.page &&
      text(q.anchor, draft ? 0 : 20, 300) &&
      plan.quote.includes(q.anchor),
    "Question anchor must occur in the selected source passage on its cited page.",
  );
  check(
    ["source", "investigation", "needs-material"].includes(q.kind),
    "Choose an evidence condition for each question.",
  );
  check(
    typeof q.reviewed === "boolean" &&
      ["template", "local-ai"].includes(q.origin) &&
      (q.model === null || text(q.model, 1, 200)),
    "Invalid question review or provenance.",
  );
  if (reviewed)
    check(
      q.reviewed && q.kind !== "needs-material",
      "Review every question. Resolve missing material or explicitly frame an investigation before starting.",
    );
  return q;
}
export function validateQuestionSet(plan, options = {}) {
  check(
    plan.breadthPolicy === BREADTH_POLICY &&
      Array.isArray(plan.questions) &&
      plan.questions.length >= MIN_TYPES &&
      plan.questions.length <= MAX_QUESTIONS,
    "Prepare 20–40 questions with at least 20 distinct inquiry types.",
  );
  plan.questions.forEach((q) => validateQuestion(q, plan, options));
  check(
    new Set(plan.questions.map((q) => q.id)).size === plan.questions.length,
    "Question IDs must be unique.",
  );
  if (!options.draft)
    check(
      new Set(plan.questions.map((q) => q.type)).size >= MIN_TYPES,
      "At least 20 distinct inquiry types are required; paraphrases do not count as breadth.",
    );
  if (!options.draft)
    check(
      new Set(plan.questions.map((q) => normalizeQuestion(q.prompt))).size ===
        plan.questions.length,
      "Repeated question wording detected. Replace it with a distinct reasoning task.",
    );
  return plan;
}
