// Bump for EVERY bundled-asset change. Do not skipWaiting or claim clients:
// an open assessment must finish using its existing release.
const CACHE = "nua-assess-release-0.7.0-r1";
const FILES = [
  "/",
  "/index.html",
  "/style.css",
  "/app.js",
  "/core.js",
  "/content.js",
  "/storage.js",
  "/demo.html",
  "/inquiry-app.js",
  "/inquiry-core.js",
  "/inquiry-questions.js",
  "/mangal-core.js",
  "/assessment-feedback.js",
  "/material.js",
  "/material-review.js",
  "/vendor/pdf.min.js",
  "/vendor/pdf.worker.min.js",
];
self.addEventListener("install", (e) =>
  e.waitUntil(
    caches
      .open(CACHE)
      .then((c) =>
        c.addAll(FILES.map((path) => new Request(path, { cache: "reload" }))),
      ),
  ),
);
self.addEventListener("activate", (e) =>
  e.waitUntil(
    caches
      .keys()
      .then((keys) =>
        Promise.all(
          keys
            .filter((k) => k.startsWith("nua-assess-") && k !== CACHE)
            .map((k) => caches.delete(k)),
        ),
      ),
  ),
);
self.addEventListener("fetch", (e) => {
  const url = new URL(e.request.url);
  if (
    url.origin !== self.location.origin ||
    !FILES.includes(url.pathname) ||
    e.request.method !== "GET"
  )
    return;
  e.respondWith(
    // Never substitute a network asset from a different release.
    caches
      .open(CACHE)
      .then((cache) => cache.match(url.pathname))
      .then(
        (response) =>
          response ||
          new Response(
            "Offline release is incomplete. Close all Nua tabs and reopen while online.",
            { status: 503, headers: { "Content-Type": "text/plain" } },
          ),
      ),
  );
});
