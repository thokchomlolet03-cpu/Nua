export const TRANSFER_POLICY = "nua-parallel-transfer/1";
export const TRANSFER_ROLES = Object.freeze(["initial", "parallel"]);
function requireThat(ok, message) { if (!ok) throw Error(message); }
function validText(v, min = 1, max = 500) { return typeof v === "string" && v.trim().length >= min && v.length <= max; }
export function transferMetadata(input = {}) {
  return validateTransferMetadata({
    policy: TRANSFER_POLICY,
    transferPairId: input.transferPairId,
    role: input.role,
    constructId: input.constructId,
    taskVersion: input.taskVersion ?? 1,
    rubricVersion: input.rubricVersion ?? 1,
    surfaceContext: input.surfaceContext,
    comparableCriterion: input.comparableCriterion,
  });
}
export function validateTransferMetadata(value) {
  requireThat(value && typeof value === "object", "Transfer metadata is required.");
  requireThat(value.policy === TRANSFER_POLICY, "Unsupported transfer policy.");
  requireThat(validText(value.transferPairId, 1, 128), "Transfer pair ID is required.");
  requireThat(TRANSFER_ROLES.includes(value.role), "Transfer role must be initial or parallel.");
  requireThat(validText(value.constructId, 1, 128), "Transfer construct is required.");
  requireThat(Number.isInteger(value.taskVersion) && value.taskVersion > 0, "Invalid transfer task version.");
  requireThat(Number.isInteger(value.rubricVersion) && value.rubricVersion > 0, "Invalid transfer rubric version.");
  requireThat(validText(value.surfaceContext, 3, 500), "Transfer surface context is required.");
  requireThat(validText(value.comparableCriterion, 12, 1000), "Comparable transfer criterion is required.");
  return value;
}
export function validateTransferPair(initial, parallel) {
  validateTransferMetadata(initial); validateTransferMetadata(parallel);
  requireThat(initial.transferPairId === parallel.transferPairId, "Transfer tasks must share a pair ID.");
  requireThat(initial.constructId === parallel.constructId, "Transfer tasks must assess the same construct.");
  requireThat(initial.role === "initial" && parallel.role === "parallel", "Transfer pair roles are invalid.");
  requireThat(initial.surfaceContext !== parallel.surfaceContext, "Parallel transfer must change the surface context.");
  return true;
}
export function isDelayedIndependentTransferAllowed({ transferMode, assistanceEvents = [] }) {
  if (transferMode !== "delayed-device-clock") return false;
  return !assistanceEvents.some((event) => ["source_view", "ai_hint", "educator_help", "peer_help", "worked_example"].includes(event.kind));
}
