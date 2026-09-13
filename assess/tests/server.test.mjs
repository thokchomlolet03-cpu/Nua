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
    "/inquiry-questions.js",
    "/material.js",
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
