export const SESSION_KEY = "nua-assess-session-v1";
// Compare the last observed value before writing, so a stale tab cannot
// silently replace newer work. localStorage's individual writes are atomic.
export function saveSession(storage, session, expected) {
  if (storage.getItem(SESSION_KEY) !== expected)
    throw Error(
      "This session changed in another window. Save a recovery copy, then reload the latest saved session.",
    );
  const serialized = JSON.stringify(session);
  storage.setItem(SESSION_KEY, serialized);
  return serialized;
}

export function recoverInterruptedHint(session, now = Date.now()) {
  const requested = session.events.filter(
    (e) => e.type === "ai_requested",
  ).length;
  const resolved = session.events.filter((e) =>
    ["ai_hint", "ai_failed", "ai_interrupted"].includes(e.type),
  ).length;
  if (requested > resolved) {
    const missing = requested - resolved;
    session.metrics.aiFailures += missing;
    for (let i = 0; i < missing; i++)
      session.events.push({
        seq: session.events.length + 1,
        at: now,
        type: "ai_interrupted",
        reason: "App closed before a result was saved.",
      });
    session.updatedAt = now;
    return true;
  }
  return false;
}
