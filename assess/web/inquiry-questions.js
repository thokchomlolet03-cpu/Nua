// Question types are reasoning operations, not evidence of universal human traits.
export const BREADTH_POLICY = "mangal-breadth/1";
export const MIN_TYPES = 20;
export const MAX_QUESTIONS = 40;
export const DELIVERY_POLICY = "mangal-delivery/1";
export const inquirySessions = [
  { id: 1, title: "Orient and reconstruct", start: 0, end: 8 },
  { id: 2, title: "Inspect evidence and diagnose", start: 8, end: 16 },
  { id: 3, title: "Transfer and generate inquiry", start: 16, end: 40 },
];
export const glossary = {
  variable: "something in the situation that can change",
  isolate:
    "make one possible explanation the main planned difference so alternatives are reduced",
  evidence: "information that supports or limits a claim",
  repeat: "measure more than once to examine variation between observations",
  boundary:
    "the conditions or cases that a conclusion does not automatically cover",
  cause: "a factor or process that produces an outcome; a difference alone does not prove this",
};
const responseContracts = {
  definition:
    "Define the key term in your own words, distinguish it from a nearby idea, and give a material-based example or boundary.",
  reconstruction:
    "State the main explanation in order: important parts or steps, their relationship, and the conclusion. Mark any gap instead of adding unsupported detail.",
  mechanism:
    "Trace two linked parts or steps from the material to its result, and identify one link that the material does not establish.",
  evidence:
    "Name the claim, the evidence supplied for it, and one limit. State whether the evidence is observed, reported, or absent.",
  assumptions:
    "Name one unstated assumption, explain why the reasoning needs it, and state how the conclusion changes if it fails.",
  prerequisites:
    "Name one earlier idea or term needed to follow the material, explain it in plain language, and connect it to the objective.",
  comparison:
    "Compare the cases on one relevant similarity and difference, then connect the difference to the strength or scope of the conclusion.",
  classification:
    "State a grouping rule, place two material-based examples in groups, and explain one example that does not fit neatly.",
  causality:
    "State the strongest justified conclusion, cite the supplied evidence, and name a competing explanation without overclaiming causation.",
  prediction:
    "Make a conditional prediction from the material, specify the condition and observable pattern, include possible variation, and separate it from an observed result.",
  counterfactual:
    "Name one changed condition, trace what would likely change, and label which part is conjecture rather than established by the material.",
  boundaries:
    "State the conclusion's scope, name one case outside that scope, and identify the evidence needed before extending the conclusion.",
  counterexample:
    "Restate the limited claim, test it against a hypothetical case, and say whether that case refutes the claim or lies outside its scope.",
  error:
    "Identify the unsupported reasoning step, name the alternative explanation, and repair either the evidence, reasoning, or conclusion.",
  uncertainty:
    "List relevant missing evidence and explain why at least one item matters. Do not invent a result or treat a plan as an observation.",
  representation:
    "Describe the elements and relationships in a useful representation, then state what it leaves out and cannot establish alone.",
  interpretation:
    "Name two plausible interpretations of the same information and propose a comparison or observation that could distinguish them.",
  application:
    "Name a new situation, map at least two elements from the material to it, and state where the mapping may fail or needs more evidence.",
  synthesis:
    "Connect the main parts into one bounded explanation, resolve a tension or gap, and distinguish supplied evidence from your inference.",
  inquiry:
    "Ask one important, answerable question not settled by the material, explain why it matters, and outline evidence that could answer it.",
  measurement:
    "Define what will be observed, explain how it will be recorded, and state one limitation of that measurement.",
  experiment:
    "Name the competing explanations, the main difference to compare, what must stay comparable, what to measure, and evidence that could challenge each explanation.",
  tradeoff:
    "Name the decision and competing criteria, explain the trade-off, and state which choice follows under a stated priority.",
  provenance:
    "State what is known about the material's origin, what is missing, and why that missing information matters for reliability.",
};
const glossaryByType = {
  mechanism: ["isolate", "cause", "evidence"],
  evidence: ["evidence", "cause"],
  assumptions: ["variable", "cause"],
  prerequisites: ["variable", "isolate"],
  causality: ["cause", "isolate"],
  prediction: ["repeat", "evidence"],
  boundaries: ["boundary", "evidence"],
  counterexample: ["boundary", "cause"],
  uncertainty: ["evidence", "repeat"],
  interpretation: ["evidence", "cause"],
  synthesis: ["isolate", "repeat", "evidence"],
  inquiry: ["boundary", "evidence"],
  measurement: ["evidence", "repeat"],
  experiment: ["variable", "isolate", "evidence"],
  tradeoff: ["boundary"],
  provenance: ["evidence"],
};
const promptOverrides = {
  mechanism:
    "Trace how two parts or steps in the material produce the stated result. Explain one connection the material establishes and identify one mechanism or link it leaves unknown.",
  prerequisites:
    "What earlier idea or key term must a learner understand to follow this material? Define it in plain language and explain its connection to the objective.",
  comparison:
    "Compare two cases, designs or ideas in the material. What is similar, what differs in a way that matters, and how does that affect the conclusion each supports?",
  causality:
    "If one case has a different outcome while another relevant factor also differs, what is the strongest justified conclusion? Name the competing explanation that remains.",
  prediction:
    "Suppose the material's main relationship holds under its stated conditions. What pattern would you predict across repeated observations or examples? Include possible variation and do not present the prediction as an observed result.",
  boundaries:
    "Would the material's conclusion apply to every case? Name one different context or example and the evidence needed before extending the conclusion.",
  counterexample:
    "Suppose the material makes a conclusion within stated conditions, but a new case shows a different result. Would that refute the limited claim? Explain what the new case would and would not show.",
  uncertainty:
    "What information is still missing before you could judge the material's main conclusion? Name relevant evidence, comparison details or definitions and explain why one matters.",
  application:
    "Apply one principle from the material to a named new situation. Which elements map across, what would you compare or observe, and where might the analogy fail?",
  inquiry:
    "Ask a new question about whether an important conclusion from the material applies in a named different context, and explain what evidence could answer it.",
};
const criteriaByType = {
  mechanism:
    "Traces a material-based chain from parts or steps to a result and explicitly marks a link or mechanism the source does not establish.",
  prerequisites:
    "Names and explains a prerequisite idea or term, connects it to the objective, and uses ordinary wording when technical language is unnecessary.",
  comparison:
    "Contrasts two material-based cases and links a relevant difference to the strength, scope, or meaning of their conclusions.",
  causality:
    "States what the evidence supports, names a competing explanation when relevant, and does not claim more causal certainty than the material permits.",
  prediction:
    "Makes a conditional prediction, acknowledges possible variation, and distinguishes an inference about a future or unobserved case from supplied observations.",
  boundaries:
    "Limits the claim to its stated cases or conditions, then names one concrete extension case and evidence needed before generalising.",
  counterexample:
    "Distinguishes a limited claim from a universal claim, evaluates the hypothetical case against the correct scope, and does not treat it as supplied evidence.",
  uncertainty:
    "Identifies relevant missing information, explains its importance, and distinguishes source evidence, design adequacy, and an observed result.",
  representation:
    "Includes the material's important elements and relationships and states a meaningful limit on what the representation alone can establish.",
  inquiry:
    "Names one new context, asks an answerable question, and proposes evidence matched to the material's unresolved issue and scope.",
  measurement:
    "Defines an observable outcome, gives a usable recording method, and identifies a limitation that could affect interpretation.",
  experiment:
    "Names competing explanations, differentiates the comparison, identifies relevant shared conditions and measurements, and states challenging evidence.",
  tradeoff:
    "Names competing criteria, explains their conflict, and makes the choice conditional on a stated priority rather than presenting it as universal.",
  provenance:
    "Separates known source information from missing provenance and connects that gap to a justified reliability question.",
};
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
export function completeAnchor(source, type) {
  const sentences = source.match(/[^.!?]+[.!?]+|[^.!?]+$/g) || [];
  const usable = sentences.map((s) => s.trim()).filter((s) => s.length >= 20 && s.length <= 300);
  if (usable.length)
    return usable[Object.keys(questionTypes).indexOf(type) % usable.length];
  const clauses = source.match(/[^,;:]+[,;:]/g) || [];
  const clause = clauses.map((s) => s.trim()).find((s) => s.length >= 20 && s.length <= 300);
  return clause || source.slice(0, 300);
}
export function questionGuidance(q) {
  const type = q?.type;
  const terms = (q?.glossaryTerms || glossaryByType[type] || []).filter((key) => glossary[key]);
  return {
    responseContract: q?.responseContract || responseContracts[type] || "Explain your reasoning and distinguish supplied evidence from inference.",
    glossary: terms.map((key) => ({ term: key, meaning: glossary[key] })),
  };
}
export function makeQuestion(type, plan, id = crypto.randomUUID()) {
  check(Object.hasOwn(questionTypes, type), "Unknown inquiry type.");
  return {
    id,
    type,
    prompt: `${promptOverrides[type] || questionTypes[type].prompt} Keep the inquiry connected to: ${plan.objective}`,
    relevance: `Review whether this ${questionTypes[type].title.toLowerCase()} question advances the selected objective. This is an unreviewed template, not semantic analysis.`,
    criterion:
      criteriaByType[type] ||
      "Look for a justified connection to the source and a clear distinction between supplied evidence, inference and missing information. Adapt these criteria to this question before approval.",
    responseContract: responseContracts[type],
    glossaryTerms: glossaryByType[type] || [],
    anchor: completeAnchor(plan.quote, type),
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
  if (q.responseContract !== undefined)
    check(
      text(q.responseContract, draft ? 0 : 12, 1000),
      `Review question ${q.id}: response contract must be 12–1000 characters.`,
    );
  if (q.glossaryTerms !== undefined)
    check(
      Array.isArray(q.glossaryTerms) &&
        q.glossaryTerms.length <= 8 &&
        q.glossaryTerms.every((key) => typeof key === "string" && glossary[key]),
      `Review question ${q.id}: invalid glossary terms.`,
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
      (draft || plan.quote.includes(q.anchor)),
    "Question anchor must occur in the selected source passage on its cited page.",
  );
  check(
    ["source", "investigation", "needs-material"].includes(q.kind),
    "Choose an evidence condition for each question.",
  );
  check(
    typeof q.reviewed === "boolean" &&
      ["template", "local-ai", "gemini-ai"].includes(q.origin) &&
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
  const minimum = plan.selectionPolicy === 'objective-coverage/1' ? 1 : MIN_TYPES;
  if (minimum === 1) check(text(plan.coverageReason,12,1000), 'Explain why this inquiry set covers the objective.');
  check(
    plan.breadthPolicy === BREADTH_POLICY &&
      Array.isArray(plan.questions) &&
      plan.questions.length >= minimum &&
      plan.questions.length <= MAX_QUESTIONS,
    minimum === 1
      ? "Prepare the selected number of questions and keep each reasoning type distinct."
      : "Prepare 20–40 questions with at least 20 distinct inquiry types.",
  );
  plan.questions.forEach((q) => validateQuestion(q, plan, options));
  check(
    new Set(plan.questions.map((q) => q.id)).size === plan.questions.length,
    "Question IDs must be unique.",
  );
  if (!options.draft)
    check(
      new Set(plan.questions.map((q) => q.type)).size >= minimum,
      minimum === 1
        ? "Each question must use a distinct reasoning type; paraphrases do not count as breadth."
        : "At least 20 distinct inquiry types are required; paraphrases do not count as breadth.",
    );
  if (!options.draft)
    check(
      new Set(plan.questions.map((q) => normalizeQuestion(q.prompt))).size ===
        plan.questions.length,
      "Repeated question wording detected. Replace it with a distinct reasoning task.",
    );
  return plan;
}
