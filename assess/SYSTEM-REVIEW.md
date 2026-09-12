# System review — 2026-09-12

Scope: the Nua Assess product in `assess/`: learning flow, rubric, AI adapters, persistence, exports, offline assets and Android host. This is not an audit of every upstream Nua module.

## Overall assessment

The workflow coherently preserves independent, assisted and transfer responses. Its largest remaining weakness is that neither the tasks nor the hints have demonstrated usefulness with learners. Three author-keyed items per scenario cannot establish general durable skills or learning gains. Several alternatives are obviously weak; educators must check how well the tasks distinguish reasoning levels. Treat this as a technical prototype pending device and educator testing.

## Changes implemented

| Area | Finding | Improvement |
| --- | --- | --- |
| Feedback timing | The report exposed correctness before transfer. | The normal UI now shows progress only until transfer is submitted. |
| Rubric alignment | The rubric evaluated test design and limits that the writing prompt did not explicitly request. | The prompt now requests evidence, a controlled repeatable test and conclusion limits; exports record its revision. |
| Scenario guidance | A wrong towel response triggered advice assuming multiple changed conditions. | Guidance now addresses comparable repeated readings. |
| AI consistency | Android and desktop maintained different prompts. | The bundled interface supplies Android the same shared prompt used by the desktop adapter; requests record policy version and language. |
| Hint evaluation | No learner feedback was collected. | Helpful/not-helpful ratings are saved; summary counts use each hint's latest rating and identify it as self-report. |
| Interrupted inference | Closing the app left unresolved request records. | Relaunch records interruptions; native concurrent inference is rejected and detected timeouts cannot return partial output as successful. |
| Persistence | Another tab could overwrite a newer session. | Saves compare the last observed stored value; stale windows stop editing and offer recovery download/reload. |
| Corrupt saves | Nested event, draft and metric data could reach rendering unchecked. | Added nested validation and impossible-response-order checks. |
| Model replacement | An incompatible import could destroy the working model. | Previous bytes remain available until initialization succeeds; failed or interrupted replacement rolls back. |
| API handling | JSON null produced an internal error. | Invalid request shapes receive a client error; desktop inference has an additional browser timeout. |

## Practical limits

An additional live test reproduced invented context about "brands of water". New hints now use AI only to select among five authored guidance categories. Only exact allowed codes are accepted; invalid model output displays labelled authored fallback. Generated prose is no longer displayed for new requests. This limits factual invention and reduces desktop output to a 16-token budget. Routing can still be wrong and the authored content still needs educator review. Historical generated hints remain recorded as earlier experimental output.

The feedback gate improves normal use but does not make an exam-secure client. Bundled keys remain inspectable, outside assistance is self-reported, and older sessions may already have received feedback. Tasks are not equated; changes in 0–3 item counts are not measured learning gains.

The storage check is an optimistic conflict guard, not a transaction across simultaneous processes. Extremely close concurrent writes and any future multi-process use need stronger storage. Recovery downloads may contain raw answers and currently require manual recovery; there is no end-user restore workflow. One session per device and unauthenticated teacher review remain limitations for classroom use.

AI consistency and feedback collection do not establish correctness, usefulness, Hindi quality or resistance to answer leakage. Before changing models or buying cloud access, have an educator rate a fixed question set for those properties and compare with authored prompts. Record cold/warm latency and RAM on the intended phone. Choose a model from those results rather than model size or API price alone.

JVM tests validate file replacement using temporary files. They do not validate LiteRT execution, Android file-picker behavior, cancellation or low-memory conditions. Physical-device acceptance testing remains necessary. No model weights or cloud service were added.

## Verification

The regression suite covers stale saves, failed writes, interrupted hints, nested corruption, scenario guidance, feedback counting, malformed API requests and bounded guidance routing. Android JVM replacement/recovery tests cover three file lifecycle cases. See the latest checkpoint for final test counts.

Browser checks confirmed the revised reasoning prompt, withheld report feedback after baseline, stale-window editing lock and reload of the latest stage. These used synthetic test responses.

## Next priority

Use one target Android phone and one science educator: test model loading, airplane-mode operation, relaunch and exports, then task wording, rubric agreement and hint usefulness. Expand subjects, cloud AI or the roster only after resolving those findings. There is no learner-impact evidence yet.
