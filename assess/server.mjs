import http from "node:http";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { readJSONBody } from "./request-body.mjs";
import {
  validateQuestionRequest,
  questionModelRequest,
  parseQuestionDraft,
} from "./question-model.mjs";
import {
  validateDraftRequest,
  inquiryModelRequest,
  parseInquiryDraft,
} from "./inquiry-model.mjs";
import {
  modelRequest,
  extractRoute,
  INFERENCE_TIMEOUT_MS,
} from "./local-model.mjs";
const root = fileURLToPath(new URL("./web/", import.meta.url));
const port = Number(process.env.NUA_PORT || 4173),
  model = process.env.NUA_MODEL || "qwen2.5:1.5b";
const allowed = new Set([
  "index.html",
  "style.css",
  "app.js",
  "core.js",
  "content.js",
  "storage.js",
  "sw.js",
  "demo.html",
  "inquiry-app.js",
  "inquiry-core.js",
  "inquiry-questions.js",
  "mangal-core.js",
  "assessment-feedback.js",
  "material.js",
  "vendor/pdf.min.js",
  "vendor/pdf.worker.min.js",
  "vendor/PDFJS-LICENSE.txt",
]);
const types = {
  html: "text/html; charset=utf-8",
  css: "text/css",
  js: "text/javascript",
  mjs: "text/javascript",
  txt: "text/plain; charset=utf-8",
};
let running = false,
  lastRequest = 0;
function json(res, status, data) {
  res.writeHead(status, {
    "Content-Type": "application/json",
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
  });
  res.end(JSON.stringify(data));
}
async function ollama(path, options = {}) {
  return fetch("http://127.0.0.1:11434" + path, {
    ...options,
    signal: AbortSignal.timeout(
      path === "/api/tags" ? 3000 : INFERENCE_TIMEOUT_MS,
    ),
  });
}
const server = http.createServer(async (req, res) => {
  try {
    const host = req.headers.host;
    if (![`127.0.0.1:${port}`, `localhost:${port}`].includes(host))
      return json(res, 403, { error: "Local requests only." });
    const origin = req.headers.origin;
    if (origin && !["http://" + host].includes(origin))
      return json(res, 403, { error: "Origin not allowed." });
    const path = new URL(req.url, "http://" + host).pathname;
    if (
      ["/api/inquiry-plan", "/api/inquiry-questions"].includes(path) &&
      req.method === "POST"
    ) {
      const breadth = path === "/api/inquiry-questions";
      if (req.headers["content-type"] !== "application/json")
        return json(res, 415, { error: "JSON required." });
      let p;
      try {
        p = (breadth ? validateQuestionRequest : validateDraftRequest)(
          await readJSONBody(req, 24000),
        );
      } catch (e) {
        return json(res, e.status || 400, { error: e.message });
      }
      if (running || Date.now() - lastRequest < 1500)
        return json(res, 429, {
          error:
            "Local model is busy. Your editable template remains available.",
        });
      running = true;
      lastRequest = Date.now();
      try {
        const r = await ollama("/api/generate", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(
            (breadth ? questionModelRequest : inquiryModelRequest)(model, p),
          ),
        });
        if (!r.ok)
          throw Error("Local model is unavailable. Use the editable template.");
        const data = await r.json();
        if (data.done_reason === "length")
          throw Error(
            "Local model exceeded its draft budget. Use the editable template.",
          );
        return json(
          res,
          200,
          breadth
            ? { questions: parseQuestionDraft(data.response, p, model) }
            : {
                plan: parseInquiryDraft(data.response, p, model),
              },
        );
      } catch (e) {
        return json(res, 503, {
          error:
            e.name === "TimeoutError" ? "Local drafting timed out." : e.message,
        });
      } finally {
        running = false;
      }
    }
    if (path === "/api/status" && req.method === "GET") {
      try {
        const r = await ollama("/api/tags");
        const d = await r.json();
        const found = d.models?.some((m) => m.name === model);
        return json(res, 200, {
          available: !!found,
          label: found
            ? `Local AI · ${model}`
            : `Model not installed · ${model}`,
          model,
        });
      } catch {
        return json(res, 200, {
          available: false,
          label: "Ollama unavailable · built-in hints ready",
        });
      }
    }
    if (path === "/api/hint" && req.method === "POST") {
      if (req.headers["content-type"] !== "application/json")
        return json(res, 415, { error: "JSON required." });
      let p;
      try {
        p = await readJSONBody(req);
      } catch (e) {
        return json(res, e.status || 400, { error: e.message });
      }
      if (
        !p ||
        typeof p !== "object" ||
        Array.isArray(p) ||
        p.task !== "guided" ||
        typeof p.question !== "string" ||
        p.question.trim().length < 3 ||
        p.question.length > 400 ||
        !["en", "hi"].includes(p.language)
      )
        return json(res, 400, { error: "Invalid guided hint request." });
      if (running || Date.now() - lastRequest < 1500)
        return json(res, 429, {
          error: "Local model is busy. Try again shortly.",
        });
      running = true;
      lastRequest = Date.now();
      try {
        const r = await ollama("/api/generate", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(modelRequest(model, p.question, p.language)),
        });
        if (!r.ok)
          throw Error(
            "Local model request failed. Check that the configured model is installed.",
          );
        const d = await r.json();
        const text = extractRoute(d);
        if (!text) throw Error("Local model returned no usable hint.");
        return json(res, 200, { text, model, source: "ollama-local" });
      } catch (e) {
        return json(res, 503, {
          error:
            e.name === "TimeoutError" ? "Local model timed out." : e.message,
        });
      } finally {
        running = false;
      }
    }
    if (req.method !== "GET")
      return json(res, 405, { error: "Method not allowed." });
    const name = path === "/" ? "index.html" : path.slice(1);
    if (!allowed.has(name)) return json(res, 404, { error: "Not found." });
    const data = await readFile(root + name);
    res.writeHead(200, {
      "Content-Type": types[name.split(".").pop()],
      "Cache-Control": "no-cache",
      "X-Content-Type-Options": "nosniff",
      "Content-Security-Policy":
        "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline' data:; img-src 'self' data:; connect-src 'self'; object-src 'none'; frame-ancestors 'none'",
    });
    res.end(data);
  } catch {
    return json(res, 500, { error: "Request could not be completed." });
  }
});
server.listen(port, "127.0.0.1", () =>
  console.log(
    `Nua Assess: http://127.0.0.1:${port}\nLocal model: ${model}. No cloud provider configured.`,
  ),
);
