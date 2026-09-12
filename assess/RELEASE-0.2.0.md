# Nua Assess 0.2.0 — local developer checkpoint

Date: 2026-09-13. Branch: `codex/nua-assess-mvp`.

## Included

The first versioned source checkpoint includes the complete assessment prototype and reliability improvements: three science scenarios, independent/guided/transfer conditions, bilingual task content, authored guidance selected by local AI, teacher rubric review, delayed feedback, local persistence, conflict detection, recovery download, evidence exports and an Android LiteRT-LM bridge with recoverable model replacement.

Version 0.2.0 uses Android versionCode 2. Existing session and content identifiers are retained. This is a development APK, not an app-store release.

## Evaluation consistency

The desktop adapter and `evaluate-local.mjs` use one shared request builder and output parser in `local-model.mjs`: temperature 0, 16 output tokens, 1024 context tokens, no thinking output, five-minute keep-alive and a 60-second inference timeout. The script prints both the raw route and the actual authored guidance that would be displayed. Three synthetic prompts cover English repetition, an answer-seeking instruction override and Hindi repetition. This small check is not an accuracy estimate or an educational evaluation.

## Files to use

- APK: `android/app/build/outputs/apk/debug/app-debug.apk`.
- Start preview: run `npm start` in `assess/`, then open `http://127.0.0.1:4173`.
- Regression checks: `npm test` in `assess/`.
- Native checks: from the checkout root, run `./gradlew -p assess/android assembleDebug lintDebug testDebugUnitTest`.
- Optional live routing check: run `node evaluate-local.mjs` in `assess/` with Ollama running.

Local SDK settings, downloaded tooling, build outputs, model weights and device data are excluded from the source commit. The APK can be rebuilt using the documented SDK configuration. A local commit is not a remote backup; no GitHub push is part of this checkpoint.

## Next acceptance step

Verification on September 13: 26 Node regression tests passed; all three Android JVM model-file tests passed; APK assembly and lint succeeded. The APK metadata reports versionName 0.2.0 and versionCode 2. The live routing samples returned REPEAT, GENERAL and REPEAT for English repetition, instruction override and Hindi repetition respectively, taking approximately 2.83 seconds, 0.16 seconds and 0.22 seconds on this Mac. These are three individual observations, not a performance or accuracy benchmark.

No Android device was connected when checked on September 13. Connect the intended test phone by USB and authorize this Mac for debugging. Record model, Android version, RAM and free storage; then install this development APK and exercise offline assessment, model import, guidance, relaunch, export and failed-model rollback.

Model selection/license acceptance, physical-device measurements and educator participation are still outstanding. The prototype has not established learner impact or a validated skill score.
