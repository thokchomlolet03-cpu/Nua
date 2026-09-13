import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import { replay } from "../simulations/replay.mjs";
const read = (name) =>
  JSON.parse(
    fs.readFileSync(new URL("../simulations/" + name, import.meta.url), "utf8"),
  );
const teacher = read("teacher-feedback.json");
for (const [caseId, name] of [
  ["A", "concise"],
  ["B", "misconception"],
  ["C", "prerequisite"],
]) {
  test(
    "synthetic agent scenario " +
      caseId +
      " preserves all 20 answers and support conditions",
    () => {
      const proposed = teacher.cases.find((c) => c.caseId === caseId);
      const result = replay(
        read("lesson.json"),
        read(name + ".json"),
        caseId,
        proposed.feedback,
      );
      assert.equal(result.synthetic, true);
      assert.equal(result.summary.breadth.completed, 20);
      assert.equal(result.summary.transferMode, "immediate-demo");
      assert.equal(
        result.full.responses.transfer.text,
        read(name + ".json").transfer,
      );
      assert.ok(result.checks.includes("early delayed application rejected"));
      assert.equal(result.summary.feedback.entries.length, 1);
      assert.equal(result.summary.feedback.followups.length, 0);
    },
  );
}

for (const name of [
  "transfer",
  "overconfident",
  "minimal",
  "language",
  "creative2",
  "anxious",
]) {
  test(
    "round-two synthetic actor " +
      name +
      " completes the frozen 20-question workflow",
    () => {
      const result = replay(
        read("lesson.json"),
        read(name + "-round2.json"),
        "round2-" + name,
      );
      assert.equal(result.synthetic, true);
      assert.equal(result.summary.breadth.completed, 20);
      assert.equal(result.summary.breadth.distinctTypes, 20);
      assert.equal(result.summary.transferMode, "immediate-demo");
      assert.equal(result.full.breadth.responses.length, 20);
      assert.ok(result.checks.length >= 23);
    },
  );
}
