# Nua Assess — technical MVP 0.2.0

A local-first formative-assessment derivative of Nua. **Working developer prototype, not a validated educational assessment or a production release.**

The current improvement pass is documented in `SYSTEM-REVIEW.md`. New AI hints use the model to select a relevant authored explanation; generated free-form prose is no longer shown. Invalid selections use explicitly labelled authored fallback. Learners can rate whether guidance helped. Teacher feedback is withheld until transfer is complete, and conflicting browser saves offer a recovery download. These changes preserve the existing local session format.

## Try it on this Mac

```sh
cd "/Users/lolet/Documents/ChatGPT/tools competition/nua-assess/assess"
npm start
```

Open http://127.0.0.1:4173. Node 20+; no npm install required. Use this same origin consistently: localhost and 127.0.0.1 have separate browser storage.

The desktop preview uses the installed Ollama service and `qwen2.5:1.5b`. Configure another installed model with `NUA_MODEL=your-model npm start`. No automatic downloads, cloud fallback, API keys, analytics or paid APIs. Without Ollama/model access, authored prompts and the entire assessment still work. After the first successful load, browser assets are cached for offline use. Desktop AI requires the preview server and Ollama to remain running, not internet access.

The browser currently contains a completed **synthetic test session**. Its report is not student research data. To try your own session, use Settings → Delete session & start fresh after exporting anything you wish to keep.

## What the learner does

1. Independently evaluates a fertilizer experiment with a light confound; selects three responses, writes reasoning and records confidence.
2. Reads a short fair-test lesson.
3. Evaluates repeated paper-towel measurements, with optional authored prompts or up to three local-AI requests.
4. Returns after 24 hours for an unassisted sugar-dissolving transfer task. An explicit immediate-demo override is available for testing and permanently labelled as such.
5. Reviews evidence with an educator: separate attempts, author-key matches, original explanations, support counts, confidence and rubric annotations.

English and Hindi task content is included. The interface is mostly English. Hindi, scientific content, task difficulty and rubric all need educator review. Three different tasks are not psychometrically equated; do not interpret a change in the 0–3 counts as a measured learning gain.

## Android APK

Built APK: `android/app/build/outputs/apk/debug/app-debug.apk` (debug-signed; development use only).

This is a separate application (`org.nua.assess`), so it does not replace the original Nua app. It packages the same assessment UI and a Kotlin LiteRT-LM CPU bridge. Android 8/API 26+ is the shell minimum, **not a promise that every supported device can run a model**. Model memory requirements must be measured on the target phone.

Settings → Import local model opens Android's document picker. Import a compatible **`.litertlm`** text-instruction model, at most 2.5 GB. Ollama/GGUF files are not interchangeable with LiteRT-LM files. No model is bundled; obtain one from its publisher and review its license. Import copies it to app-private storage and initializes the CPU engine. There is no INTERNET permission. Initial model acquisition outside the app still needs connectivity. A replacement is committed only after successful initialization; failed imports restore the previous model.

**Android runtime/model import/inference has not been tested on a physical device.** No phone was connected during this build. Compilation and lint checks are not evidence of on-phone usability, latency or memory safety. File rollback has JVM test coverage, but keep your original model file until device testing is complete.

Build from the Nua checkout root:

```sh
./gradlew -p assess/android assembleDebug lintDebug
```

Requires a compatible JDK (this build used Homebrew OpenJDK 19), Android platform 36 and build-tools 36.0.0. Set `sdk.dir` in ignored `assess/android/local.properties` to your SDK location. This Mac's local SDK is under `.tooling/android-sdk`; the base Nua wrapper is reused without configuring the original app's native modules or asset packs.

## Evidence, privacy and cost

- One local pseudonymous session; no roster or login. Drafts save automatically. Submission locks are application-workflow locks, not cryptographic guarantees or exam security.
- Learner and teacher views share the device. Teacher review is unauthenticated. Supervise use; outside assistance cannot be detected.
- Summary JSON omits student explanations, teacher notes and event text. Full JSON requires confirmation and includes these. Nothing uploads automatically; exported files are not encrypted by this app.
- Full exports include timing, language changes, hint requests/results/failures, attempt submissions and teacher-annotation timestamps. No key-by-key telemetry.
- Structured responses use authored keys. AI does **not** grade written reasoning, infer intelligence, diagnose disabilities or certify durable skills.
- Three AI calls per session in the UI; 400-character questions; bounded preview token output and 60-second inference timeout. The client-side budget is not a hardened quota.
- Model/device/electricity, development, educator review, localization and support still cost resources. "$0 cloud/API charge" does not mean zero total operating cost.
- Use synthetic data until consent, child safeguarding, retention and research arrangements are agreed with a partner.

## Reliability checkpoint: 0.2.1

See `AUDIT-0.2.1.md` for the deeper audit and acceptance plan. Supported desktop browsers allow one editor per origin using Web Locks; another window is read-only until the first closes and the second reloads. Browsers without that capability fail closed; the standalone native host remains supported. Different origins (including localhost versus 127.0.0.1) have separate data and locks.

The browser caches a complete release and serves its installed assets without replacing individual files from the network. Close all tabs for that origin before reopening to activate a downloaded update. Every asset change requires a new service-worker cache version; install from a stable, complete release. Cache eviction can still prevent offline startup. Android instead uses APK-bundled assets.

Saved sessions now have stronger nested-field and sequence checks. If validation or saving fails, preserve the offered recovery copy before resetting. Recovery files can contain raw answers; restoration is still manual. Summary exports explicitly distinguish AI-selected authored guidance, fallback guidance and historical experimental output, and state the limits of device-clock timing and score comparisons.

## Tests and iteration

```sh
npm test
node evaluate-local.mjs
```

The first command runs deterministic core/privacy/server tests without invoking a model. The second makes three **optional live local** model calls (English, injection probe, Hindi) using the production request builder, timeout and output parser. It prints the raw route, displayed guidance, settings and latency for human review; it is not a validated benchmark.

See `CHECKPOINT.md` for verified state and the next iteration. Suggested next step: install the APK on one target Android phone, test offline model loading/hints/export/relaunch, then have one science educator review the three tasks before adding more subjects.

## Source map

- `web/content.js`: versioned authored bilingual tasks and built-in prompts.
- `web/core.js`: assessment state machine, scoring, exports, hint specification.
- `web/app.js`, `style.css`: shared responsive UI and local persistence.
- `web/sw.js`: browser-only offline cache; Android serves bundled assets.
- `server.mjs`: loopback-only desktop Ollama adapter and static server.
- `android/app/.../MainActivity.kt`: bundled WebView, local model import, CPU inference, Android document export.
- `tests/`: deterministic regression checks.

Upstream Nua checkout: `09f8f0b3adbe5330f57586be39ebf1b6b6ab452d`. Work is isolated under `assess/` on `codex/nua-assess-mvp`; original app source is unchanged. Nothing has been pushed, published or resubmitted to the competition.
