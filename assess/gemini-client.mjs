import {
  questionTypes,
  questionGuidance,
  completeAnchor,
} from "./web/inquiry-questions.js";

export const GEMINI_MODEL =
  (typeof process !== "undefined" && process.env?.GEMINI_MODEL) ||
  "gemini-3.8-flash";
export const GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models";

const SYSTEM_INSTRUCTION = `You are a senior learning designer and secondary-school science assessment specialist.
Your task is to draft classroom inquiry questions for human review, exactly one per requested inquiry type.
Strict constraints:
1. Output ONLY valid JSON matching the requested schema. Do not write filler introductory text or commentary.
2. Each question must perform a distinct cognitive operation from the requested types.
3. Every question must be grounded in the supplied excerpt and advance the stated learning objective.
4. "anchor" MUST be an exact verbatim substring from the source text (20–300 characters).
5. "criterion" must describe observable evidence in a written student response, not just why the question matters.
6. "kind" must be "source" if the question can be answered from the passage, or "investigation" if the student must design a comparison or identify missing evidence.
7. Do not embed the answers in the questions. Do not include learner diagnoses or scores.`;

export function buildPrompt({ objective, excerpt, page = 1, types, subject, gradeLevel }) {
  const typesDetail = types.map((type) => ({
    type,
    title: questionTypes[type]?.title || type,
    operation: questionTypes[type]?.prompt || "",
    responseContract: questionGuidance({ type }).responseContract,
    glossaryTerms: questionGuidance({ type }).glossary.map((g) => g.term),
  }));

  return `Generate classroom inquiry questions for the following educational material.

${subject ? `SUBJECT: ${subject}` : ""}
${gradeLevel ? `TARGET LEVEL: ${gradeLevel}` : ""}
LEARNING OBJECTIVE: ${objective}
PAGE: ${page}

SOURCE MATERIAL EXCERPT:
"""
${excerpt}
"""

REQUESTED INQUIRY TYPES (${types.length} total):
${JSON.stringify(typesDetail, null, 2)}

OUTPUT SCHEMA:
Return ONLY a JSON object with a "questions" array containing exactly ${types.length} items:
{
  "questions": [
    {
      "type": "<one of the requested types>",
      "prompt": "<specific question text, max 300 chars>",
      "relevance": "<one sentence: how this reasoning operation advances the objective>",
      "criterion": "<observable evidence required in student answer, max 250 chars>",
      "anchor": "<exact verbatim quote from the SOURCE MATERIAL EXCERPT, 20-300 chars>",
      "kind": "source" | "investigation"
    }
  ]
}

Ensure all anchors are verbatim matches in the source text.`;
}

export async function generateInquiryQuestionsGemini(apiKey, options = {}) {
  const {
    objective,
    excerpt,
    page = 1,
    subject = "Science",
    gradeLevel = "Secondary",
    types = Object.keys(questionTypes).slice(0, 20),
    timeoutMs = 60000,
  } = options;

  if (!objective || typeof objective !== "string" || objective.trim().length < 5) {
    throw new Error("A valid learning objective is required.");
  }
  if (!excerpt || typeof excerpt !== "string" || excerpt.trim().length < 20) {
    throw new Error("Valid source material excerpt is required (minimum 20 characters).");
  }

  // Dry-run / Mock mode for tests or offline operation
  if (apiKey === "mock" || apiKey === "test" || !apiKey) {
    return generateMockQuestions({ objective, excerpt, page, types });
  }

  const prompt = buildPrompt({ objective, excerpt, page, types, subject, gradeLevel });

  const url = `${GEMINI_API_URL}/${GEMINI_MODEL}:generateContent?key=${apiKey}`;
  const payload = {
    contents: [
      {
        role: "user",
        parts: [{ text: prompt }],
      },
    ],
    systemInstruction: {
      parts: [{ text: SYSTEM_INSTRUCTION }],
    },
    generationConfig: {
      temperature: 0.35,
      maxOutputTokens: 8192,
      responseMimeType: "application/json",
    },
  };

  const response = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
    signal: AbortSignal.timeout(timeoutMs),
  });

  if (!response.ok) {
    const errBody = await response.text().catch(() => "");
    throw new Error(`Gemini API error (${response.status}): ${errBody || response.statusText}`);
  }

  const data = await response.json();
  const rawText = data?.candidates?.[0]?.content?.parts?.[0]?.text;
  if (!rawText) {
    throw new Error("Gemini returned an empty response.");
  }

  return parseGeminiResponse(rawText, { objective, excerpt, page, types });
}

export function parseGeminiResponse(rawText, { excerpt, page, types }) {
  let parsed;
  try {
    parsed = JSON.parse(rawText);
  } catch (e) {
    throw new Error(`Failed to parse Gemini output as JSON: ${e.message}`);
  }

  const questionsList = parsed?.questions;
  if (!Array.isArray(questionsList) || questionsList.length === 0) {
    throw new Error("Gemini response did not contain a valid 'questions' array.");
  }

  return types.map((type, i) => {
    const item = questionsList.find((q) => q?.type === type) || questionsList[i] || {};
    const guidance = questionGuidance({ type });

    // Validate verbatim anchor against excerpt; fallback safely to completeAnchor if Gemini slightly hallucinated quotation
    let anchor = typeof item.anchor === "string" ? item.anchor.trim() : "";
    if (!anchor || !excerpt.includes(anchor)) {
      anchor = completeAnchor(excerpt, type);
    }

    const kind =
      item.kind === "source" || item.kind === "investigation"
        ? item.kind
        : ["definition", "reconstruction", "evidence", "comparison", "synthesis"].includes(type)
          ? "source"
          : "investigation";

    return {
      id: `q${i + 1}`,
      type,
      prompt: (item.prompt || questionTypes[type]?.prompt || "").slice(0, 400),
      relevance: (
        item.relevance ||
        `Examines ${questionTypes[type]?.title?.toLowerCase() || type} reasoning for this objective.`
      ).slice(0, 400),
      criterion: (item.criterion || "Look for sound reasoning grounded in evidence.").slice(0, 400),
      anchor,
      page,
      kind,
      responseContract: guidance.responseContract,
      glossaryTerms: guidance.glossary.map((g) => g.term),
      reviewed: false,
      origin: "gemini-ai",
      model: GEMINI_MODEL,
    };
  });
}

export async function analyzeResponseGemini(apiKey, { questionPrompt, expectedCriterion, studentAnswer, timeoutMs = 30000 }) {
  if (!studentAnswer || typeof studentAnswer !== "string" || studentAnswer.trim().length < 5) {
    throw new Error("Valid student answer required for analysis.");
  }

  if (apiKey === "mock" || apiKey === "test" || !apiKey) {
    return {
      criterion_met: "partially",
      strength: "Student identified key conceptual elements in the prompt.",
      gap: "Could be more specific regarding the controlled condition.",
      suggested_next_step: "Ask the student to state what remains unchanged in the comparison.",
    };
  }

  const prompt = `You are assisting an educator reviewing a secondary-school student's inquiry response.
This is a focused diagnostic task. Do not run extended reasoning.

QUESTION: ${questionPrompt}
OBSERVABLE CRITERION: ${expectedCriterion}
STUDENT'S RESPONSE:
"""
${studentAnswer}
"""

Return ONLY a JSON object:
{
  "criterion_met": true | false | "partially",
  "strength": "<one specific sentence noting what the student got right, or null>",
  "gap": "<one specific sentence noting what is missing or ambiguous, or null>",
  "suggested_next_step": "<one actionable instructional next step for the teacher to suggest, max 120 chars>"
}`;

  const url = `${GEMINI_API_URL}/${GEMINI_MODEL}:generateContent?key=${apiKey}`;
  const response = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      contents: [{ role: "user", parts: [{ text: prompt }] }],
      generationConfig: {
        temperature: 0.2,
        maxOutputTokens: 1024,
        responseMimeType: "application/json",
      },
    }),
    signal: AbortSignal.timeout(timeoutMs),
  });

  if (!response.ok) {
    const errText = await response.text().catch(() => "");
    throw new Error(`Gemini feedback error (${response.status}): ${errText || response.statusText}`);
  }

  const data = await response.json();
  const text = data?.candidates?.[0]?.content?.parts?.[0]?.text;
  return JSON.parse(text);
}

function generateMockQuestions({ objective, excerpt, page, types }) {
  return types.map((type, i) => {
    const guidance = questionGuidance({ type });
    return {
      id: `q${i + 1}`,
      type,
      prompt: `Explain how ${questionTypes[type]?.title?.toLowerCase() || type} applies to: ${objective}`,
      relevance: `Examines ${questionTypes[type]?.title?.toLowerCase() || type} reasoning for the objective.`,
      criterion: `Provide observable evidence connecting the source material to ${objective}.`,
      anchor: completeAnchor(excerpt, type),
      page,
      kind: ["definition", "reconstruction", "evidence"].includes(type) ? "source" : "investigation",
      responseContract: guidance.responseContract,
      glossaryTerms: guidance.glossary.map((g) => g.term),
      reviewed: false,
      origin: "gemini-ai",
      model: GEMINI_MODEL,
    };
  });
}
