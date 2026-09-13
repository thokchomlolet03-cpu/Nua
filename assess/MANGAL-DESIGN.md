# Mangal Inquiry — 0.4.0 implementation and acceptance record

## Product boundary

A local-first formative-assessment prototype, not a validated test, attention tracker, psychological intervention or production learning platform. The aim is useful evidence about understanding of one objective from the learner's actual material. More questions are not automatically better.

The primary sequence is material review → one objective → a reviewed set of at least 20 distinct inquiry types → one question at a time → synthesis and learner question → source-based revision → stop → return after 24 hours for application → educator review. Focus means that all questions remain connected to the same objective and material; it does not mean reducing inquiry to one angle. The initial registry contains 24 types, including definition, reconstruction, mechanism, evidence, assumptions, prerequisites, comparison, classification, causality, prediction, counterfactuals, boundaries, counterexamples, error diagnosis, uncertainty, representation, alternative interpretation, application, synthesis, question generation, measurement, experimental design, trade-offs and provenance.

## What is implemented

- Readable PDF, TXT, Markdown and pasted text. Extraction runs locally; PDF pages are processed up to explicit limits: 12 MB, 80 pages, 180,000 extracted characters. Page and exact quotation are retained for review.
- The author selects an objective and passage. A transparent template creates 20 distinct question types by default, with up to 40 questions allowed. Each question has a type, objective relevance, source anchor, evidence condition and observable review criterion. Every question must be reviewed before approval. A self-study plan is marked provisional because its author has seen the source and criteria.
- Optional desktop local-AI drafting uses Ollama in batches of up to four distinct question types. Only the selected passage, objective and requested types are sent to the local model. Exact source-anchor constraints, per-question validation, duplicate checks and a bounded 40-question attempt budget are applied. No cloud fallback or paid API.
- Source quotations and provenance cannot be replaced by model output. Malformed, incomplete, duplicate-question and rationale-as-rubric outputs are rejected without replacing the editable template. This is structural checking, not semantic validation.
- One question at a time, a persistent objective, a visible coverage map, all required inquiry questions, a bounded list of up to three unrelated side questions, pause/resume and saved drafts. Pausing has no penalty. The app does not measure focus or infer emotion.
- Separate per-question submitted attempts; source access labels assisted work for the exact question. Synthesis follows completion of the full set. Revision presents source and criteria after initial attempts. Optional reflection is explicitly self-report.
- A 24-hour return gate and a permanently labelled immediate demonstration option. Device time and outside assistance are unverified. This is one delayed application, not adaptive spaced repetition or proof of retention.
- Educator annotations and summary/full JSON exports. Summary excludes material, response text and teacher notes; full exports contain sensitive learning content and require care. No automatic research upload or analytics.
- Versioned export/import of prepared lesson JSON for reuse; imported plans require review again and do not restore learner attempts. New sessions use a separate storage key from the legacy short inquiry format, so old work is not silently rewritten. One active inquiry per browser origin; the old science demo uses separate storage.
- Bundled offline browser assets and Android shell. Android's material workflow uses templates or imported plans; new AI plan drafting is desktop-only. Native model hints remain in the fixed science demonstration, with model loading deferred until needed.

## Important exclusions

Full PDF text extraction is **not full-document semantic analysis**. The current version assesses a selected objective/passage, not every concept in a book. There is no OCR, dependable diagram/table/math interpretation, handwriting, audio/video or native slide parsing. Text extraction can lose layout and meaning; source review is mandatory.

No automatic grading, mastery score, psychometric equating, authenticated teacher identity, class roster, cloud sync or learning-data consent workflow. Local storage is not encrypted student-record infrastructure. School use needs educator supervision and appropriate governance before real learner data is collected.

Fluent Forever and The Chimp Paradox are inspirations supplied by the user, not validation for this product. Retrieval, feedback, bounded workload and low-pressure recovery are design choices to evaluate. No literal brain-part or emotion model from either book is encoded.

## Verification on 2026-09-13

- 67 Node tests passed: old-demo regression checks plus 20/24-question breadth, distinct types, per-question response locking, pause restoration, source provenance, assistance labels, delayed/demo separation, draft restoration, export minimisation, AI batch validation, endpoint restrictions and offline assets.
- Android debug assembly, lint and JVM tests passed. This verifies build/static behaviour, not phone usability or inference performance.
- Synthetic one-page PDF successfully extracted in the browser. An initial PDF.js cleanup API mismatch was found and fixed.
- A real local model generated an editable four-question batch in about 24.5 seconds. It returned structurally valid output but weak criteria and marked all four as needing more material; the batch remained editable and unapproved. Earlier model output repeated its rationale as a rubric; rejection checks cover these structural failures. Automated checks do not establish semantic quality.
- A synthetic 20-question session reached 20/20, preserved seven responses plus an unfinished eighth draft through pause/reload, labelled source support per question, and reached synthesis with all 20 responses available. The 24-hour application gate remains in the sequence.
- Prepared-lesson browser import, final transfer/export and physical Android use remain **not counted as verified**. Core transition, report and export tests pass, but do not replace acceptance testing.
- The final start screen rendered at 390-pixel width. This is a limited responsive check, not an accessibility audit or complete mobile journey test.

## Running and upgrading

From `assess/`, use Node 22.13+ and run `npm ci`, then `npm test` and `npm start`. PDF.js 6.3.289 is pinned; its worker and licence are copied locally by the prepare step. Generated vendor assets are not committed. Android pre-build checks that required assets exist.

From the repository root, build using `JAVA_HOME=/opt/homebrew/opt/openjdk ./gradlew -p assess/android assembleDebug lintDebug testDebugUnitTest --console=plain` on this Mac. Debug APK: `assess/android/app/build/outputs/apk/debug/app-debug.apk`, version 0.4.0 / code 5. No model is bundled and the app has no Internet permission.

Offline release cache is `nua-assess-release-0.4.0`. Updates intentionally wait for older app tabs to close. Export important work before browser maintenance; do not clear storage to update. Keep a consistent origin because localhost, 127.0.0.1 and different ports have separate data.

## Next acceptance gates — polish, not feature growth

1. Finish browser lesson import, delayed/demo application and both report exports after resolving the browser-control interruption.
2. Test PDF picking, draft recovery, exports and memory usage on an actual Android device. Test model inference separately in the science demo.
3. Ask an educator to review a small set of real permitted materials: extraction accuracy, objective relevance, perspective choice, answerability and actionable criteria. Record rejected drafts, not only successful examples.
4. With appropriate permission and safeguards, observe whether learners can complete the sequence without explanation. Assess question quality, correction and delayed application using educator review; do not substitute engagement counts for learning evidence.
5. Only after those gates, decide whether full-document objective mapping or another file format removes a demonstrated user obstacle.

No external submission, publication or deployment was changed by this implementation.
