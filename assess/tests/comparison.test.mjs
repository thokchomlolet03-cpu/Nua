import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";

const dir = new URL("../simulations/", import.meta.url);
const settings = [
  "response-contract",
  "plain-glossary",
  "text-organiser",
  "staged-sessions",
  "structured-format",
  "neutral-help",
];

for (const setting of settings) {
  test(
    "controlled pair is complete and keeps the same question IDs: " + setting,
    () => {
      const value = JSON.parse(
        fs.readFileSync(
          new URL("comparison-" + setting + ".json", dir),
          "utf8",
        ),
      );
      assert.equal(value.synthetic, true);
      assert.equal(value.baseline.length, 20);
      assert.equal(value.safeguarded.length, 20);
      assert.deepEqual(
        value.baseline.map((answer) => answer.id),
        value.safeguarded.map((answer) => answer.id),
      );
      for (const answers of [value.baseline, value.safeguarded]) {
        for (const answer of answers) {
          assert.ok(answer.text.length >= 12 && answer.text.length <= 1800);
          assert.ok(
            [
              "not-recorded",
              "ready-to-explain",
              "unsure",
              "need-help",
            ].includes(answer.learnerSignal),
          );
        }
      }
      assert.match(value.answerLeakCheck, /No|low|risk|not/i);
    },
  );
}
