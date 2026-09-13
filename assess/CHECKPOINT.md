# MVP checkpoint — 0.4.0 — 2026-09-13

Current default: material-based Mangal Inquiry with 20+ distinct inquiry types, one question shown at a time. Read [MANGAL-DESIGN.md](MANGAL-DESIGN.md) before resuming. 67 Node tests pass; Android assembly, lint and JVM tests pass. Browser verification covered PDF/text preparation, 20/20 synthetic inquiry responses, per-question support labels, pause/draft restoration and synthesis. Local AI remains optional and human-reviewed. Prepared-lesson import and physical Android testing remain outstanding. No cloud AI, public deployment, GitHub push or competition submission change was performed.

## Historical 0.2.1 checkpoint

## Latest audit checkpoint

39 Node tests and six Android JVM tests pass. APK assembly and lint pass (0 errors, 6 warnings); APK metadata is versionName 0.2.1 / versionCode 3. No Android device is connected. See `AUDIT-0.2.1.md` for the findings, verification and remaining acceptance gates.

This pass adds exclusive browser editing, checked deletion, stronger saved-record validation, release-pinned offline assets, byte-correct Hindi request parsing, explicit fallback counts and native operation/reply isolation. Existing valid session/content schemas are retained; damaged records are blocked and offered recovery download, not silently discarded.

Synthetic browser verification used a separate origin on port 4191. A second window was blocked, then regained editing after the original closed. A real local request displayed the authored repetition prompt (~3 seconds). With that test server stopped, a draft survived reload, guided and immediate transfer submissions completed, a teacher annotation survived another reload, and a summary export was downloaded and parsed with no raw answers or teacher notes. This does not verify a real 24-hour delay or an Android runtime.

Browser updates: close all Nua tabs for the same origin, then reopen while the current preview server is running. New offline releases deliberately wait for old tabs to close. The 4191 test server was stopped; the user's existing 4173 session was not cleared.

## Previous 0.2.0 improvement pass

Final guidance revision: 26 Node tests and three Android JVM tests pass. New AI calls select bounded authored guidance with a 16-token desktop output budget; raw model prose is not displayed. A live local call returned `REPEAT` and resolved to the authored repetition explanation. Invalid routes fall back explicitly. This change followed a live free-text hint inventing "brands of water". Historical outputs remain labelled as earlier experimental AI output. Device model inference and educator review are still outstanding.

Version 0.2.0 aligns the web/package and Android version labels (Android versionCode 2). Production inference and the evaluation script now share `local-model.mjs`, including their request builder, 60-second timeout and route extraction. Content and session schemas remain compatible with the previous prototype. No Android device was connected on September 13. See `RELEASE-0.2.0.md` for the release handoff and `SYSTEM-REVIEW.md` for the product analysis.

## Implemented

Three bilingual science scenarios; immutable-in-UI submissions; autosaved drafts; independent/guided/24-hour-transfer conditions; explicit immediate demo; local AI and labelled authored fallback prompts; teacher rubric notes; summary/full JSON exports; local event log and AI call metrics; offline browser cache; standalone Android app with LiteRT-LM import/inference bridge.

## Historical verification of the original 0.1 prototype (September 12)

- 16 deterministic Node tests passed: state transitions, invalid responses, delayed gate, demo label, hint gate/budget, export privacy, restoration, content coverage, static/API restrictions.
- Android `assembleDebug` and `lintDebug` passed after fixing an API-level guard. Non-blocking warnings remain for version updates, JavaScript-enabled WebView and backup configuration compatibility. The bridge exposes only bundled UI, blocks external navigation and uses no Internet permission.
- Browser end-to-end synthetic session: baseline → lesson → guided → wait gate → immediate transfer → report.
- Draft choices/explanation/confidence survived reload. Teacher rubric and note survived reload.
- Real Ollama calls reached the UI (qwen2.5:1.5b): approximately 24 seconds first observed call, approximately 1 second subsequent observed call. These are individual observations, not a benchmark.
- First hint was weak/repetitive. Revised prompt provided some conceptual explanation but still did not consistently follow the ideal scaffold-plus-question format. No educator quality validation.
- An optional gemma4:e2b comparison produced a 60-second timeout, then approximately 31-second and 2.4-second responses. Hindi response mixed English scaffold and Hindi question. Kept the lighter default. Model files were not downloaded or removed.
- At 390px viewport, document width was 390px (no horizontal overflow); desktop document width matched 1280px viewport.
- Both JSON exports downloaded and were parsed from disk. Summary excluded raw explanations, teacher notes and events; full export included all three attempts and event trail, labelled immediate-demo.
- Browser reloaded successfully with the preview server stopped, displaying saved progress and AI-unavailable status.

## Not verified / not implemented

- No connected Android device: installation, WebView rendering on phone, LiteRT model compatibility/import, actual inference, SAF export, thermal/battery/RAM behavior remain hardware acceptance tests.
- No learner pilot, teacher validation, research partner agreement, calibrated rubric, equated task forms, learning-effect estimate or validated skill score.
- No multi-learner roster, authenticated teacher roles, secure research upload, cloud AI, audio/image input, app-store release, or automatic reminders.
- Prompt consistency and model-file rollback are implemented; native execution still needs device testing.

## Next iteration: one real device

1. Record phone model, Android version, RAM and free storage.
2. Install development APK; complete the full authored-only flow in airplane mode.
3. Import one appropriately licensed LiteRT-LM instruction model. Record model hash and file size.
4. Test three hints, failure behavior, relaunch persistence and export in airplane mode. Measure cold/warm latency and memory; verify timeout behavior.
5. Have one science educator rate explanations/hints for correctness, answer leakage, usefulness and reading level, including Hindi.
6. Fix failures before expanding the task bank. Only then plan a consented feasibility pilot; do not call a demo a learning-impact study.

All responses in the current browser session and downloaded test exports are synthetic engineering fixtures, not student data.
