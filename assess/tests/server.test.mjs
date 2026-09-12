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
const port = 4189,
  base = `http://127.0.0.1:${port}`;
let child;
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
