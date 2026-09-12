# Nua Assess: deep system audit and 0.2.1 checkpoint

Date: 13 September 2026. Scope: `assess/`, not the entire upstream Nua application. This is a local developer build, not a public release, competition resubmission, security certification or educational validation.

## Verdict

Nua Assess now has a stronger, tested single-session workflow for collecting evidence of scientific reasoning. It is suitable for the next round of supervised technical testing using synthetic data. It is not yet a validated assessment product or ready for unsupervised classroom deployment. Calling it perfect would be unsupported.

The useful core is the separation of independent work, supported practice and transfer, with written explanations for educator review. Local AI classifies a question into one of five categories; the interface displays a corresponding authored prompt. It does not freely tutor, grade written reasoning or establish uniquely human abilities. An incorrect category is still possible even though arbitrary generated prose is no longer displayed.

## Confirmed findings and implemented changes

| Area | Failure mechanism found | Change and evidence |
| --- | --- | --- |
| Concurrent editing | Comparing localStorage values alone was not a cross-window transaction. Opening a second window could count the first window's active inference as interrupted. | Exclusive origin-scoped Web Lock before recovery or editing; lifetime lock with read-only fallback. Regression test plus real two-window denial and ownership handover. Older clients or developer tools can bypass this protocol; comparison checks remain as a second guard. |
| Failed saves | An AI request could still execute after its request record failed to save; annotation code could show a success toast after a failed write. | Stop inference dispatch on save failure, validate before writes, guard downstream actions and show annotation success only after persistence succeeds. Unsaved in-memory work stays available for recovery download. |
| Destructive confirmation | A deletion confirmation could remove a session changed since the dialog opened. Browser-wide storage-clear events were ignored. | Compare saved value immediately before deletion, block stale mutation dialogs and handle storage-clear events. Regression test proves stale deletion does not remove the newer value. |
| Corrupt records | Some invalid dates, confidence values, nested reviews, drafts, event identities and transfer labels reached the UI. | Validate those fields, stage-response dependencies and the due time derived from the guided submission. Valid stage/draft/review roundtrips and malformed-field cases are tested. No automatic deletion or silent repair of damaged responses. |
| Data boundaries | Submitted answer objects and summary review objects could include unrelated fields; caller-supplied event metadata could replace event identity. | Allowlist response and summary review fields; assign event sequence, timestamp and type after supplied data. Regression tests cover unexpected free text and event overrides. |
| Offline updates | Network-first caching replaced each file independently, allowing old and new application files to coexist. | Precache the release, then serve its immutable cached assets; do not force activation into open clients. APIs bypass cache. Tests cover cache selection, query URLs, missing assets and unrelated caches. Actual offline reload and workflow completion passed. Initial installation still requires a stable complete source release; this is not a content-hash verified deployment system. |
| Hindi input transport | Decoding each incoming byte chunk separately could split a UTF-8 character; body limits counted characters instead of bytes. | Assemble bounded bytes, decode UTF-8 strictly, then parse JSON. Tests split a Hindi question at every byte and reject oversized or malformed UTF-8 input. |
| Guidance accounting | The AI-hint total included authored fallback and historical experimental output without an explicit breakdown. | Retain the legacy count for compatibility, define it, and add separate categories to summaries and the teacher report. Timing is labelled as an unverified device clock; unequal tasks are not treated as learning-gain measurements. |
| Native model replacement | Failure deleting the previous-model backup occurred after the rollback block, leaving inconsistent failure/recovery semantics. | Treat cleanup as part of the transaction; restore the previous model immediately on failure. Six JVM cases now cover success, incompatible replacement, interrupted replacement, cleanup failure, failed first import and missing candidate. |
| Native operations | Picker processing and inference could queue across UI requests; reused numeric reply IDs could collide after a page reload. Oversized bridge requests silently timed out. | One atomic reservation spans inference or picker plus file processing; UUID reply IDs; explicit oversized-request errors; cancellation clears pending export text. Compiled and linted, but native runtime behavior remains unverified. |

## Verification performed

- 39 Node tests passed; no failures or skips. They cover the state machine, guidance policy, privacy, HTTP boundaries, locking abstraction, Unicode handling and service-worker behavior. Tests do not invoke paid APIs.
- Android APK assembly, lint and JVM tests passed. Six JVM tests passed. Lint reports 0 errors and 6 warnings, covering SDK/dependency freshness, JavaScript-enabled WebView and older backup configuration. Versions were not upgraded without compatibility testing.
- APK metadata confirms `org.nua.assess`, versionName `0.2.1`, versionCode `3`, minimum Android API 26. It is debug-signed; no model weights are included.
- Browser test on isolated `127.0.0.1:4191`: baseline submitted; correctness withheld; competing editor disabled; after the first tab closed, the second reloaded the saved lesson and could continue.
- One real qwen2.5:1.5b request selected the authored repetition guidance in approximately three seconds. This single observation is neither a latency distribution nor a quality benchmark.
- After stopping that preview server: guided draft restored on reload; guided submission and immediate-demo transfer completed; teacher annotation saved and survived reload; summary downloaded and parsed from disk. It contained three attempts, one AI call, zero AI failures, one AI-selected authored prompt, no raw explanations, no teacher note and no event log.
- Desktop report screenshot inspected. Actual 24-hour return was not waited out; the delayed transition has deterministic tests. No new phone-width visual test was performed in this pass.
- Device enumeration returned no connected Android device. JVM file tests do not exercise the model engine, file picker, Android lifecycle, timeouts, low-memory handling or hardware performance.

The test responses and annotation were explicitly synthetic. The user's existing session on port 4173 was not cleared. No GitHub push, cloud deployment, paid inference, account creation or competition edit was performed.

## Remaining risks, in priority order

1. **Educational usefulness is unknown.** Three items per scenario and uncalibrated explanations cannot establish general durable skills, mastery or learning gains. The guided task differs materially from baseline/transfer; forms are not equated. Some distractors are obviously weak. Correct answers may reflect option elimination. No educator or learner data resolves this yet.
2. **Android remains a build, not a verified device experience.** Model initialization may fail on the target phone. Memory, battery, thermal load, import cancellation, process death and inference cancellation require hardware tests. Model identity/hash is not automatically included in reports. Retain original model files.
3. **One device, one session.** There is no roster, teacher authentication, session-switching workflow or supported recovery-file import. A recovery export is not a tested backup-and-restore system. Storage eviction or app removal can erase local work.
4. **Assessment integrity is workflow-level.** Bundled keys are inspectable; outside assistance is self-reported; the device clock can change. The editor lock is origin-scoped and not a security boundary. Localhost and 127.0.0.1 are separate stores. Do not use the prototype for high-stakes grading or learner selection.
5. **Research provenance is incomplete.** Explanations carry prompt revision and hints carry routing-policy metadata, but summary language is the current session language, teacher identity is not authenticated, and only the latest rubric annotation is retained. Full events aid interpretation but do not provide a tamper-evident research record.
6. **Privacy is local-first, not guaranteed anonymity.** Free text can contain personal information; exports are not encrypted by the app. There are no partner-approved consent, retention, safeguarding or research-sharing procedures. Use synthetic responses until these are agreed.
7. **Offline availability has prerequisites.** Browser assets must finish installing before disconnection. Cache eviction can break startup. Installed releases wait for older tabs to close. Native APK assets do not depend on the browser service worker, but usable AI still requires compatible model weights.

## Cost and usefulness decision

The current local model only selects among five authored categories. Its plausible benefit is accepting a learner's natural-language question, not producing unlimited explanations. That convenience has not been demonstrated to justify model download size, memory use or waiting time. The always-available authored prompts remain an important baseline, not merely an emergency fallback.

Do not add cloud AI solely to make the proposal sound more advanced. First compare authored-only support with locally routed support using educator-rated questions. Record selection correctness, answer leakage, reading level, helpfulness, latency and device cost. If routing alone does not improve usefulness, simplify the AI component. If genuinely open-ended feedback is needed, evaluate it as a separate experimental feature with an explicit privacy and cost budget; do not silently replace the bounded guidance policy.

## Next acceptance gates

These are proposed engineering/education acceptance criteria, not results already obtained.

| Gate | Required evidence before moving on |
| --- | --- |
| Target phone | Record phone/Android/RAM/free storage and model/license/hash. Complete authored-only and model-assisted workflows in airplane mode. No lost drafts or submissions after relaunch; readable exports; incompatible model leaves previous model usable. Exercise picker cancellation, process death, timeout and low-memory cases. Measure cold/warm latency rather than promising a device-independent target. |
| Educator review | Review all three tasks, answer alternatives, explanation prompts and rubric. Identify ambiguous wording and weak distractors. Review English and Hindi separately. Have two reviewers independently annotate a shared synthetic/example response set and inspect disagreements before interpreting rubric values. |
| Hint comparison | Build a fixed question set spanning all five categories, answer requests, unclear input and both languages. Compare the displayed authored-only and AI-routed support. Require no known factual errors or direct answer leakage in the reviewed set; label that finite coverage honestly. Record wrong routes, fallback rates and latency. |
| Supervised feasibility | Obtain partner-approved consent/safeguarding and data handling before learner recruitment. Measure completion, return rate, teacher review time, failures and hint use. Predefine outcomes and an appropriate comparison before any learning-effect claim. |

## Handoff

Build artifact: `android/app/build/outputs/apk/debug/app-debug.apk`. Source version: 0.2.1; content remains `fair-tests-1.0.0`; session schema remains `nua-assess/1`. Valid existing sessions are retained. Source checkpoint stays local on `codex/nua-assess-mvp`; a local commit is not an off-device backup.

The next high-value work requires a target phone and an educator, not more subjects or an unmeasured cloud-AI integration.
