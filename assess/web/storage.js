export const SESSION_KEY = "nua-assess-session-v1";
export const EDITOR_LOCK = "nua-assess-session-editor-v1";
// The UI holds an exclusive Web Lock for its lifetime. This additional
// comparison also catches writes from older app versions and developer tools.
export function saveSession(storage, session, expected) {
  if (storage.getItem(SESSION_KEY) !== expected)
    throw Error(
      "This session changed in another window. Save a recovery copy, then reload the latest saved session.",
    );
  const serialized = JSON.stringify(session);
  storage.setItem(SESSION_KEY, serialized);
  return serialized;
}

export function deleteSession(storage, expected) {
  if (storage.getItem(SESSION_KEY) !== expected)
    throw Error(
      "This session changed in another window. Reload before deleting.",
    );
  storage.removeItem(SESSION_KEY);
}

// Never recover requests while a different editor may still be executing them.
// The lock is origin-scoped; Android hosts only one bundled activity.
export async function withSessionEditor(locks, nativeHost, initialize) {
  if (locks) {
    return locks.request(EDITOR_LOCK, { ifAvailable: true }, async (lock) => {
      initialize(Boolean(lock));
      if (lock) await new Promise(() => {});
    });
  }
  initialize(Boolean(nativeHost));
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
