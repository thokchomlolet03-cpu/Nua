export const CONSTRUCT_POLICY = "nua-constructs/1";
export const TASK_POLICY = "nua-ai-era-task/1";
export const RUBRIC_POLICY = "nua-rubric/1";
export const DEFAULT_TASK_VERSION = 1;
export const DEFAULT_RUBRIC_VERSION = 1;

export const constructs = Object.freeze({
  evidence_evaluation: Object.freeze({
    id: "evidence_evaluation",
    version: 1,
    title: "Evaluate evidence",
    description:
      "Judge what a claim is supported by, what remains uncertain, and which alternative explanations or limits matter.",
    operations: Object.freeze([
      "evidence", "causality", "uncertainty", "counterexample", "assumptions",
      "boundaries", "interpretation", "provenance", "error", "comparison",
    ]),
  }),
  investigation_design: Object.freeze({
    id: "investigation_design",
    version: 1,
    title: "Design investigations",
    description:
      "Define what to observe, compare conditions, identify variables and design evidence that can distinguish explanations.",
    operations: Object.freeze([
      "measurement", "experiment", "comparison", "assumptions", "causality",
      "uncertainty", "prediction", "inquiry",
    ]),
  }),
  transfer: Object.freeze({
    id: "transfer",
    version: 1,
    title: "Transfer understanding",
    description:
      "Use the same underlying reasoning in a changed context and identify where the mapping holds or needs more evidence.",
    operations: Object.freeze([
      "application", "prediction", "counterfactual", "synthesis", "boundaries", "mechanism",
    ]),
  }),
});

export const REQUIRED_SCIENCE_PILOT_CONSTRUCTS = Object.freeze([
  "evidence_evaluation", "investigation_design", "transfer",
]);

// Starting policy only. This is not a claim that twelve tasks, or these exact
// tasks, are scientifically optimal. Select for construct coverage, not count.
export const SCIENCE_PILOT_TYPES = Object.freeze([
  "evidence", "causality", "uncertainty", "counterexample",
  "measurement", "experiment", "assumptions", "comparison",
  "application", "prediction", "counterfactual", "synthesis",
]);

export const EVIDENCE_SCOPES = Object.freeze([
  "source_explicit", "source_inferable", "prior_knowledge_required",
  "investigation_design", "external_evidence_required", "transfer",
]);

function requireThat(ok, message) { if (!ok) throw Error(message); }

export function assessmentPolicies(overrides = {}) {
  return {
    constructPolicy: CONSTRUCT_POLICY,
    taskPolicy: TASK_POLICY,
    rubricPolicy: RUBRIC_POLICY,
    assistancePolicy: overrides.assistancePolicy || "nua-cognitive-support/1",
    transferPolicy: overrides.transferPolicy || "nua-parallel-transfer/1",
  };
}

export function validateAssessmentPolicies(value) {
  requireThat(value && typeof value === "object", "Assessment policy metadata is required.");
  requireThat(value.constructPolicy === CONSTRUCT_POLICY, "Unsupported construct policy.");
  requireThat(value.taskPolicy === TASK_POLICY, "Unsupported task policy.");
  requireThat(value.rubricPolicy === RUBRIC_POLICY, "Unsupported rubric policy.");
  requireThat(typeof value.assistancePolicy === "string" && value.assistancePolicy.length > 0, "Assistance policy is required.");
  requireThat(typeof value.transferPolicy === "string" && value.transferPolicy.length > 0, "Transfer policy is required.");
  return value;
}

export function constructsForOperation(type) {
  return Object.values(constructs)
    .filter((construct) => construct.operations.includes(type))
    .map((construct) => construct.id);
}

export function primaryConstructForOperation(type) {
  if (["measurement", "experiment", "inquiry"].includes(type)) return "investigation_design";
  if (["application", "counterfactual", "synthesis"].includes(type)) return "transfer";
  return constructsForOperation(type)[0] || "evidence_evaluation";
}

export function inferEvidenceScope(type, kind = "source") {
  if (["application", "counterfactual", "synthesis"].includes(type)) return "transfer";
  if (["measurement", "experiment", "inquiry"].includes(type)) return "investigation_design";
  if (kind === "investigation") return "external_evidence_required";
  return ["evidence", "definition", "reconstruction", "comparison"].includes(type)
    ? "source_explicit" : "source_inferable";
}

export function taskAssessmentMetadata(input = {}) {
  const constructId = input.constructId || primaryConstructForOperation(input.type);
  const construct = constructs[constructId];
  requireThat(construct, `Unknown assessment construct: ${constructId}`);
  const evidenceScope = input.evidenceScope || inferEvidenceScope(input.type, input.kind);
  requireThat(EVIDENCE_SCOPES.includes(evidenceScope), "Unknown evidence scope.");
  return {
    constructId,
    constructVersion: construct.version,
    taskVersion: Number.isInteger(input.taskVersion) ? input.taskVersion : DEFAULT_TASK_VERSION,
    rubricVersion: Number.isInteger(input.rubricVersion) ? input.rubricVersion : DEFAULT_RUBRIC_VERSION,
    evidenceScope,
    transferPairId: input.transferPairId ?? null,
    transferTaskRole: input.transferTaskRole ?? null,
  };
}

export function validateTaskAssessmentMetadata(meta) {
  requireThat(meta && typeof meta === "object", "Task assessment metadata is required.");
  const construct = constructs[meta.constructId];
  requireThat(construct, "Unknown task construct.");
  requireThat(meta.constructVersion === construct.version, "Unsupported construct version.");
  requireThat(Number.isInteger(meta.taskVersion) && meta.taskVersion > 0, "Invalid task version.");
  requireThat(Number.isInteger(meta.rubricVersion) && meta.rubricVersion > 0, "Invalid rubric version.");
  requireThat(EVIDENCE_SCOPES.includes(meta.evidenceScope), "Invalid evidence scope.");
  requireThat(meta.transferTaskRole === null || ["initial", "parallel"].includes(meta.transferTaskRole), "Invalid transfer task role.");
  requireThat(
    (meta.transferPairId === null && meta.transferTaskRole === null) ||
      (typeof meta.transferPairId === "string" && meta.transferPairId.length > 0 && meta.transferTaskRole),
    "Transfer pair ID and role must be supplied together.",
  );
  return meta;
}

export function decorateTask(task, overrides = {}) {
  requireThat(task && typeof task === "object", "Task is required.");
  return { ...task, assessment: taskAssessmentMetadata({ type: task.type, kind: task.kind, ...overrides }) };
}

export function constructCoverage(tasks = []) {
  const covered = new Set();
  for (const task of tasks) {
    const id = task?.assessment?.constructId || primaryConstructForOperation(task?.type);
    if (constructs[id]) covered.add(id);
  }
  return [...covered];
}

export function validateConstructCoverage(tasks, required = REQUIRED_SCIENCE_PILOT_CONSTRUCTS) {
  requireThat(Array.isArray(tasks) && tasks.length > 0, "At least one assessment task is required.");
  const covered = new Set(constructCoverage(tasks));
  const missing = required.filter((id) => !covered.has(id));
  requireThat(missing.length === 0, `Missing required assessment constructs: ${missing.join(", ")}.`);
  return { covered: [...covered], missing };
}

export function selectSciencePilotTypes(availableTypes) {
  const available = new Set(availableTypes);
  const selected = SCIENCE_PILOT_TYPES.filter((type) => available.has(type));
  requireThat(selected.includes("measurement") && selected.includes("experiment"), "Science pilot requires Measurement and Experiment operations.");
  return selected;
}
