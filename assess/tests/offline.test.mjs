import test from "node:test";
import assert from "node:assert/strict";
import vm from "node:vm";
import { readFile } from "node:fs/promises";
const source = await readFile(new URL("../web/sw.js", import.meta.url), "utf8");
function harness() {
  const handlers = {},
    deleted = [],
    added = [],
    lookups = [];
  let missing = false;
  const cache = {
    addAll: async (requests) => added.push(...requests),
    match: async (path) => {
      lookups.push(path);
      return missing ? undefined : new Response("pinned release");
    },
  };
  vm.runInNewContext(source, {
    self: {
      location: { origin: "http://127.0.0.1:4191" },
      addEventListener: (type, handler) => (handlers[type] = handler),
    },
    caches: {
      open: async () => cache,
      keys: async () => [
        "nua-assess-release-0.6.0-r1",
        "nua-assess-release-0.7.0-r1",
        "nua-assess-mvp-2",
        "unrelated-app",
      ],
      delete: async (key) => deleted.push(key),
    },
    Request: class {
      constructor(path, options) {
        this.path = path;
        this.cache = options.cache;
      }
    },
    Response,
    URL,
    fetch: () => {
      throw Error("Release assets must never mix with network assets");
    },
  });
  return {
    handlers,
    deleted,
    added,
    lookups,
    setMissing: () => (missing = true),
  };
}
test("offline install caches the complete release and activation preserves unrelated caches", async () => {
  const h = harness();
  let work;
  h.handlers.install({ waitUntil: (promise) => (work = promise) });
  await work;
  for (const path of [
    "/",
    "/index.html",
    "/app.js",
    "/core.js",
    "/content.js",
    "/storage.js",
    "/style.css",
    "/inquiry-app.js",
    "/inquiry-core.js",
    "/mangal-core.js",
    "/assessment-feedback.js",
    "/inquiry-questions.js",
    "/material.js",
    "/material-review.js",
    "/demo.html",
    "/vendor/pdf.min.js",
    "/vendor/pdf.worker.min.js",
  ])
    assert.ok(h.added.some((r) => r.path === path && r.cache === "reload"));
  h.handlers.activate({ waitUntil: (promise) => (work = promise) });
  await work;
  assert.deepEqual(h.deleted, ["nua-assess-release-0.6.0-r1", "nua-assess-mvp-2"]);
});
test("installed assets stay release-pinned online or offline, including query URLs", async () => {
  const h = harness();
  let response;
  h.handlers.fetch({
    request: { url: "http://127.0.0.1:4191/app.js?reload=1", method: "GET" },
    respondWith: (p) => (response = p),
  });
  assert.equal(await (await response).text(), "pinned release");
  assert.deepEqual(h.lookups, ["/app.js"]);
  h.setMissing();
  h.handlers.fetch({
    request: { url: "http://127.0.0.1:4191/core.js", method: "GET" },
    respondWith: (p) => (response = p),
  });
  assert.equal((await response).status, 503);
});
test("service worker never caches AI, external requests or writes", () => {
  const h = harness();
  for (const [url, method] of [
    ["http://127.0.0.1:4191/api/status", "GET"],
    ["https://example.com/app.js", "GET"],
    ["http://127.0.0.1:4191/app.js", "POST"],
  ]) {
    h.handlers.fetch({
      request: { url, method },
      respondWith: () => assert.fail("Should bypass offline cache"),
    });
  }
});
