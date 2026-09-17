import test from "node:test";
import assert from "node:assert/strict";
import {
  GEMINI_MODEL,
  buildPrompt,
  generateInquiryQuestionsGemini,
  parseGeminiResponse,
  analyzeResponseGemini,
} from "../gemini-client.mjs";
import {
  questionTypes,
  validateQuestion,
  validateQuestionSet,
} from "../web/inquiry-questions.js";
import { validatePlan } from "../web/mangal-core.js";

const sampleExcerpt =
  "Gills and lungs represent two distinct structural solutions for gas exchange under different physical constraints. In aquatic respiration, water flows unidirectionally across gill lamellae where blood flows in the opposite direction. This countercurrent exchange mechanism maintains a concentration gradient along the entire capillary bed, allowing fish to extract up to 80% of dissolved oxygen from water.";

const sampleObjective =
  "Explain how countercurrent exchange maintains respiratory efficiency in aquatic environments.";

test("buildPrompt includes all requested question types, objective, and verbatim excerpt", () => {
  const types = ["definition", "mechanism", "evidence", "causality"];
  const prompt = buildPrompt({
    objective: sampleObjective,
    excerpt: sampleExcerpt,
    types,
    subject: "Zoology",
    gradeLevel: "Class 11",
  });

  assert.ok(prompt.includes("Zoology"));
  assert.ok(prompt.includes("Class 11"));
  assert.ok(prompt.includes(sampleObjective));
  assert.ok(prompt.includes(sampleExcerpt));
  for (const t of types) {
    assert.ok(prompt.includes(t));
  }
});

test("generateInquiryQuestionsGemini generates valid 20-question breadth set in mock/test mode", async () => {
  const questions = await generateInquiryQuestionsGemini("mock", {
    objective: sampleObjective,
    excerpt: sampleExcerpt,
    types: Object.keys(questionTypes).slice(0, 20),
  });

  assert.equal(questions.length, 20);
  assert.equal(questions[0].model, GEMINI_MODEL);

  const plan = {
    objective: sampleObjective,
    quote: sampleExcerpt,
    page: 1,
    questions,
  };

  // Ensure each generated question passes existing inquiry validation
  for (const q of questions) {
    validateQuestion(q, plan, { draft: true, reviewed: false });
    assert.ok(q.anchor.length >= 20);
    assert.ok(sampleExcerpt.includes(q.anchor));
  }
});

test("parseGeminiResponse correctly maps raw JSON from Gemini and enforces verbatim anchors", () => {
  const mockGeminiJson = JSON.stringify({
    questions: [
      {
        type: "mechanism",
        prompt: "Trace how blood and water flow in countercurrent exchange.",
        relevance: "Directly explains the mechanism of oxygen transfer.",
        criterion: "Mentions opposing flow directions and concentration gradient.",
        anchor: "This countercurrent exchange mechanism maintains a concentration gradient along the entire capillary bed",
        kind: "source",
      },
      {
        type: "causality",
        prompt: "Does countercurrent exchange cause higher oxygen extraction?",
        relevance: "Isolates the effect of flow direction.",
        criterion: "Identifies gradient maintenance as the causal link.",
        anchor: "non-existent hallucinated quote that will trigger completeAnchor fallback",
        kind: "investigation",
      },
    ],
  });

  const parsed = parseGeminiResponse(mockGeminiJson, {
    objective: sampleObjective,
    excerpt: sampleExcerpt,
    page: 1,
    types: ["mechanism", "causality"],
  });

  assert.equal(parsed.length, 2);
  assert.equal(parsed[0].type, "mechanism");
  assert.equal(
    parsed[0].anchor,
    "This countercurrent exchange mechanism maintains a concentration gradient along the entire capillary bed",
  );
  assert.ok(sampleExcerpt.includes(parsed[0].anchor));

  // Second question's anchor was hallucinated in mock Gemini text, so completeAnchor fallback must provide a valid excerpt substring
  assert.equal(parsed[1].type, "causality");
  assert.ok(sampleExcerpt.includes(parsed[1].anchor));
  assert.ok(parsed[1].anchor.length >= 20);
});

test("analyzeResponseGemini returns structured diagnostic feedback in mock mode", async () => {
  const feedback = await analyzeResponseGemini("mock", {
    questionPrompt: "Explain countercurrent exchange.",
    expectedCriterion: "Mentions opposite flow directions.",
    studentAnswer: "Water flows across gill lamellae and blood flows the other way.",
  });

  assert.ok(feedback.criterion_met);
  assert.ok(typeof feedback.strength === "string");
  assert.ok(typeof feedback.suggested_next_step === "string");
});

test("exported plan generated with Gemini satisfies nua-mangal-lesson/2 full plan validation", async () => {
  const questions = await generateInquiryQuestionsGemini("mock", {
    objective: sampleObjective,
    excerpt: sampleExcerpt,
    types: Object.keys(questionTypes).slice(0, 20),
  });

  // Mark questions as reviewed as an educator would in the portal
  questions.forEach((q) => {
    q.reviewed = true;
  });

  const fullLesson = {
    schema: "nua-mangal-lesson/2",
    title: "Comparative Respiration",
    pages: [{ page: 1, text: sampleExcerpt }],
    plan: {
      objective: sampleObjective,
      page: 1,
      quote: sampleExcerpt,
      perspective: "causality",
      reason: "Explain respiratory efficiency.",
      recall: `Recall test: ${sampleObjective}`,
      investigate: `Investigation test: ${sampleObjective}`,
      transfer: `Transfer test: ${sampleObjective}`,
      rubric: "Check grounded evidence.",
      origin: "gemini-ai-draft",
      model: GEMINI_MODEL,
      policy: "focused-inquiry-1",
      breadthPolicy: "mangal-breadth/1",
      questions,
    },
  };

  validatePlan(fullLesson.plan, fullLesson.pages);
  validateQuestionSet(fullLesson.plan, { reviewed: true });
});
