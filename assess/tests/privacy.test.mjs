import test from "node:test";
import assert from "node:assert/strict";
import {
  createSession,
  report,
  submitResponse,
  validateSession,
} from "../web/core.js";
test("summary excludes teacher free-text notes too", () => {
  const s = createSession(0, "x");
  submitResponse(s, {
    choices: { evidence: 0, investigation: 1, judgment: 2 },
    explanation: "A synthetic student response.",
    confidence: 1,
  });
  s.reviews.baseline = { status: "teacher-annotated", note: "private review" };
  assert.equal(report(s).attempts[0].explanationReview.note, undefined);
  assert.equal(
    report(s, true).attempts[0].explanationReview.note,
    "private review",
  );
});
test("damaged stage and language fail closed", () => {
  const s = createSession(0, "x");
  s.stage = "report";
  assert.throws(() => validateSession(s));
  s.stage = "baseline";
  s.language = "xx";
  assert.throws(() => validateSession(s));
});
