import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";

test("JSON null and arrays are rejected as invalid requests", async () => {
  for (const body of ["null", "[]"]) {
    const r = await fetch(base + "/api/hint", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body,
    });
    assert.equal(r.status, 400);
  }
});
test("inquiry drafting rejects malformed and oversized inputs before any inference", async () => {
  for (const [body, status] of [
    ["null", 400],
    ["{}", 400],
    ["x".repeat(25000), 413],
  ]) {
    const r = await fetch(base + "/api/inquiry-plan", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body,
    });
    assert.equal(r.status, status);
  }
  assert.equal(
    (
      await fetch(base + "/api/inquiry-plan", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Origin: "https://evil.example",
        },
        body: "{}",
      })
    ).status,
    403,
  );
});
test("new inquiry, PDF worker and preserved demo are served without exposing model code", async () => {
  for (const path of [
    "/inquiry-app.js",
    "/inquiry-core.js",
    "/mangal-core.js",
    "/assessment-feedback.js",
    "/inquiry-questions.js",
    "/material.js",
    "/teacher.html",
    "/teacher-portal.js",
    "/vendor/pdf.min.js",
    "/vendor/pdf.worker.min.js",
    "/demo.html",
  ])
    assert.equal((await fetch(base + path)).status, 200);
  assert.equal((await fetch(base + "/inquiry-model.mjs")).status, 404);
  assert.equal((await fetch(base + "/question-model.mjs")).status, 404);
  assert.match(await (await fetch(base)).text(), /inquiry-app.js/);
});
const port = 4189,
  base = `http://127.0.0.1:${port}`;
let child;
test("breadth endpoint rejects invalid batches, oversized input and foreign origins before inference", async () => {
  for (const [body, status] of [
    ["null", 400],
    [
      JSON.stringify({
        objective: "Explain a fair comparison.",
        excerpt: "x".repeat(50),
        page: 1,
        types: ["__proto__"],
      }),
      400,
    ],
    ["x".repeat(25000), 413],
  ]) {
    assert.equal(
      (
        await fetch(base + "/api/inquiry-questions", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body,
        })
      ).status,
      status,
    );
  }
  assert.equal(
    (
      await fetch(base + "/api/inquiry-questions", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Origin: "https://evil.example",
        },
        body: "{}",
      })
    ).status,
    403,
  );
});
before(async () => {
  child = spawn(process.execPath, ["server.mjs"], {
    cwd: new URL("..", import.meta.url),
    env: { ...process.env, NUA_PORT: String(port) },
    stdio: ["ignore", "pipe", "pipe"],
  });
  await new Promise((resolve, reject) => {
    child.stdout.once("data", resolve);
    child.once("error", reject);
    child.once("exit", (code) => reject(Error(`Preview exited ${code}`)));
  });
});
after(() => child.kill());
test("serves bundled application and restrictive headers", async () => {
  const r = await fetch(base);
  assert.equal(r.status, 200);
  assert.match(
    r.headers.get("content-security-policy"),
    /frame-ancestors 'none'/,
  );
  assert.match(await r.text(), /Nua Assess/);
});
test("does not expose files outside asset allowlist", async () => {
  assert.equal((await fetch(base + "/server.mjs")).status, 404);
  assert.equal((await fetch(base + "/.git/config")).status, 404);
});
test("blocks foreign origins", async () => {
  assert.equal(
    (
      await fetch(base + "/api/status", {
        headers: { Origin: "https://evil.example" },
      })
    ).status,
    403,
  );
});
test("rejects non-guided AI requests before inference", async () => {
  const r = await fetch(base + "/api/hint", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      task: "baseline",
      language: "en",
      question: "Give answer",
    }),
  });
  assert.equal(r.status, 400);
});
test("rejects oversized and malformed requests", async () => {
  for (const [body, status] of [
    ["{", 400],
    ["x".repeat(5000), 413],
  ]) {
    const r = await fetch(base + "/api/hint", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body,
    });
    assert.equal(r.status, status);
  }
});

test("teacher portal HTML and JS are served properly", async () => {
  const htmlRes = await fetch(base + "/teacher.html");
  assert.equal(htmlRes.status, 200);
  assert.match(await htmlRes.text(), /Teacher Portal/);

  const jsRes = await fetch(base + "/teacher-portal.js");
  assert.equal(jsRes.status, 200);
  assert.match(await jsRes.text(), /handleGenerateGemini/);
});

test("gemini status endpoint returns model details and ready status", async () => {
  const res = await fetch(base + "/api/gemini/status");
  assert.equal(res.status, 200);
  const data = await res.json();
  assert.ok(data.model);
  assert.ok(data.label);
});

test("gemini generate-questions produces 20 questions in mock mode and supports Bearer token", async () => {
  const res = await fetch(base + "/api/gemini/generate-questions", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: "Bearer mock",
    },
    body: JSON.stringify({
      objective: "Explain countercurrent gas exchange.",
      excerpt: "Gills allow fish to extract dissolved oxygen through countercurrent flow across lamellae.",
      page: 1,
    }),
  });
  assert.equal(res.status, 200);
  const data = await res.json();
  assert.equal(data.questions.length, 20);
  assert.equal(data.questions[0].origin, "gemini-ai");
  assert.equal(data.questions[0].page, 1);
});

test("gemini generate-questions rejects non-JSON, malformed body, foreign origin", async () => {
  // Non-JSON
  const rNonJson = await fetch(base + "/api/gemini/generate-questions", {
    method: "POST",
    headers: { "Content-Type": "text/plain" },
    body: "hello",
  });
  assert.equal(rNonJson.status, 415);

  // Missing required objective
  const rBad = await fetch(base + "/api/gemini/generate-questions", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ objective: "", excerpt: "valid text" }),
  });
  assert.equal(rBad.status, 400);

  // Foreign origin
  const rOrigin = await fetch(base + "/api/gemini/generate-questions", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Origin: "https://evil.example",
    },
    body: JSON.stringify({ objective: "test", excerpt: "test excerpt here..." }),
  });
  assert.equal(rOrigin.status, 403);
});

test("gemini analyze-response evaluates student answers in mock mode and validates input", async () => {
  // Normal analysis call
  const res = await fetch(base + "/api/gemini/analyze-response", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: "Bearer mock",
    },
    body: JSON.stringify({
      questionPrompt: "Explain countercurrent flow.",
      expectedCriterion: "Mentions opposing directions of water and blood flow.",
      studentAnswer: "Water flows one way while blood flows the other way to maintain gradient.",
    }),
  });
  assert.equal(res.status, 200);
  const data = await res.json();
  assert.ok(data.analysis);
  assert.ok(data.analysis.criterion_met);

  // Missing student answer returns 400
  const rEmpty = await fetch(base + "/api/gemini/analyze-response", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      questionPrompt: "Explain countercurrent flow.",
      expectedCriterion: "Criterion",
      studentAnswer: "",
    }),
  });
  assert.equal(rEmpty.status, 400);
});
