# Systematic update report — 0.6.0

Date: 14 September 2026.

This report records how the synthetic student/teacher rounds and the six-setting safeguard comparison changed the product. The tests are software QA with generated responses. They are not learner research, teacher validation, an accessibility audit, an effect estimate or evidence of improved learning.

## Decision rule

An update was accepted only when it preserved the 20-question inquiry breadth, kept the learner's objective visible, made the requested reasoning observable, reduced avoidable language or pacing burden, and did not turn a scaffold into an answer. Anything that could not satisfy those conditions remains a limitation or a future test.

## Finding-to-change trace

| Evidence from the test rounds | Failure mode | 0.6.0 change | Current boundary |
| --- | --- | --- | --- |
| Round 2 teacher reviews found truncated or repeated source anchors | The educator could not reliably locate the source evidence for an item | `completeAnchor()` selects a complete usable sentence where possible, otherwise an exact bounded source span; approval still requires exact source containment | Text extraction and reading order are not verified; diagrams and tables remain out of scope |
| Round 2 identified abstract prerequisite wording, especially q6 | Technical vocabulary can become a writing barrier before the target reasoning is attempted | Type-relevant glossary terms have short plain-language definitions and are disclosed on the learner screen | The glossary is authored, small and English-only; it does not diagnose language or learning needs |
| q13 and q20 were under-specified about hypothetical cases and generalisation | A response could be judged without knowing whether the learner addressed scope, refutation or evidence | Counterexample and inquiry prompts/contracts explicitly request claim scope, a new context and answerable evidence | The contract structures an answer; it does not grade whether the reasoning is correct |
| q15 and the causal cluster needed explicit evidence distinctions | Learners could confuse a plan, an observation, an explanation and a conclusion | Evidence, mechanism, causality, prediction, uncertainty and related types now have distinct generic response contracts and criteria | The first 20 templates are material-neutral; human review is still required to make each item genuinely relevant |
| Teacher reviews found surface overlap among q7/q9/q11/q14/q17 | Different labels can still demand the same reasoning | Comparison, causality, counterfactual, error and prediction prompts now separate the output demanded: compare, qualify a causal claim, alter one condition, repair an error, or predict conditionally | Distinct text is not proof of construct distinctness; blind expert ratings remain required |
| The safeguard comparison found pacing promising and no reason to cut breadth | Twenty questions can create a focus/load problem if delivered as one uninterrupted block | The sequence exposes three natural checkpoints: questions 1–8, 9–16 and 17 onward; pause remains optional and no focus score is produced | No active-time, attention or retention claim is made; the app does not enforce calendar sessions |
| The safeguard comparison found response contracts useful but capable of cueing | A checklist can reveal the expected answer structure | Contracts name evidence components but do not state the source conclusion; the learner may use prose, a labelled list or a table | Cueing and leakage require adversarial response testing and expert scoring |
| The comparison found neutral help had the highest cue risk | Fewer help requests can mean the answer was disclosed, not that learning improved | No neutral answer-revealing help ladder was added | Future help support must be tested for leakage before implementation |
| Inclusion review required equivalent modes and concise-answer acceptance | Length, grammar or diagram skill can be mistaken for reasoning | The learner is explicitly allowed concise written structures; responses remain open text and are reviewed against evidence criteria | Audio, handwriting, OCR, screen-reader and multimodal equivalence are not implemented |

## Current flow

1. Material is extracted locally from readable PDF, TXT or Markdown, or pasted by the author.
2. The author checks extracted pages, chooses one contiguous passage and states one observable objective.
3. The rule-based generator creates 20 distinct inquiry types. The registry contains 24 types and supports up to 40 reviewed questions.
4. Each item contains a prompt, objective relevance, exact source anchor, evidence condition, criterion, response contract and optional glossary terms. Local AI can tailor four items at a time; its output inherits the deterministic contract and glossary metadata.
5. An educator reviews every item before assignment. A model cannot approve an item, replace the source anchor or mark a learner response correct.
6. The learner closes the source, completes recall, works through the full inquiry set one question at a time, formulates a question, revises with source/criteria, waits for the delayed application, and then receives an educator-controlled evidence-linked next step.
7. The system preserves support conditions, uncertainty self-reports, response text, source anchors and follow-up evidence separately. It produces no focus score, emotion inference, intelligence label or mastery certification.

## Verification

- 89 deterministic Node tests pass, including the six frozen safeguard pairs, generic question scaffolds, 20/24-type coverage, response-contract validation, glossary validation, source-anchor checks, persistence, privacy exports, API restrictions, offline assets and all frozen synthetic agent replays.
- Android debug assembly, lint and JVM tests pass for version 0.6.0 / code 7. Lint reports six non-blocking warnings and no errors. No physical Android device was connected.
- A fresh browser preview used a generic evidence lesson. The rendered learner screen showed the response contract, type-relevant glossary and the checkpoint after eight responses. This is a synthetic smoke test; it did not submit or alter the user's 4173 session.

## What remains deliberately unresolved

- The app still selects a checked passage; it does not claim full-document semantic coverage.
- Local AI drafting is desktop-only for uploaded-material questions. The Android native model bridge remains associated with the older fixed demonstration and has not been tested on a phone.
- No automatic semantic scoring, teacher authentication, class roster, secure research export, OCR, dependable diagram/table interpretation or multimodal response equivalence exists.
- No real learner, teacher or school has established usability, question quality, workload, equity, retention or learning impact.

## Next evidence gate

Use a small set of permitted real materials and independent educators to rate each item's relevance, source sufficiency, distinctness, reading level, answer leakage and actionability. Run the same rubric on concise, creative, contradictory, additional-language, non-writer, off-topic and diagram-dependent responses. Only after that should full-document mapping, richer modalities or broader subject coverage be considered.
