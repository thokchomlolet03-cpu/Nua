# Synthetic agent assessment report

Run date: 13 September 2026. This is a software QA artifact using generated actors. It is not a study, human-user test, educator validation, psychometric analysis, retention result or evidence that Mangal Inquiry improves learning.

## Method

Three student agents were given the same synthetic lower-secondary science lesson and the same 20-question plan, without access to the implementation or each other's responses:

- A: concise learner with generally sound understanding and localized uncertainty.
- B: learner who persistently confuses repeated trials with removing a systematic confound.
- C: learner missing prerequisites around “isolate” and “variable”, who requests source support and expresses uncertainty.

A separate teacher-role agent reviewed the lesson structure. It flagged ambiguous hypothetical results, repeated reasoning demands, underspecified prerequisites, unclear success criteria and incomplete source anchors. It proposed reducing the set to eight questions; that recommendation was rejected because the product requirement is to preserve at least 20 inquiry types. The agent later hit the platform usage limit before producing response-level teacher feedback. The response-level fixture in `teacher-feedback.json` is therefore a transparent synthetic fallback based on the observed production records, not a teacher-agent output.

The student fixtures were replayed through `mangal-core.js`, not merely inspected as text. The runner tests recall, every individual inquiry response, optional help signals, source-support conditions, pause protection, delayed-application protection, application completion, feedback validation, immutable evidence and minimized export behavior.

## Results

All three cases completed all 20 questions and the labelled immediate application. Each case passed 23 negative/positive checks in the runner, including rejection of a short response, rejection of educator feedback before application, rejection of submission while paused, rejection of early delayed application, preservation of source-support conditions and preservation of original learner evidence after feedback.

| Case | Distinct observed pattern | Help signals | Source-supported responses | Feedback target |
|---|---|---:|---:|---|
| A | Mostly connected reasoning; uncertainty about operationalizing a new investigation | 0 | 1 | q18: plan the changed, controlled and measured features |
| B | Persistent systematic-confound misconception in transfer reasoning | 0 | 1 | q9: distinguish random variation from a repeated unequal comparison |
| C | Prerequisite and vocabulary gaps; explicit requests for source support | 4 | 4 | q6: map changed, controlled and measured variables |

The three synthetic educator feedback records passed exact-quotation validation. Each produced one actionable next step and one observable criterion. The original responses were unchanged. The minimized report contained feedback metadata but not response text, quoted evidence, next-step prose or follow-up text.

## What this tells us

The current system can preserve and expose meaningful *test-pattern differences* when those patterns are deliberately authored into the actors. It can prevent feedback from citing text that is not actually present. It can route uncertainty signals toward review without turning them into a score or diagnosis. It can keep a 20-question sequence intact while allowing feedback to focus on one useful response.

The lesson itself is not ready to be treated as a reliable assessment. Agent review exposed likely prompt-quality defects that should be fixed in the lesson generator or authoring guidance: complete question-specific anchors, explicit hypothetical premises, clear vocabulary and directly observable criteria. The 20 labels also need expert review for genuine reasoning distinctness; the simulation cannot establish that.

## Limits

Agent personas are authored test inputs. They do not represent actual students, age-level distributions, disability access needs, language variation, motivation, fatigue or unauthorized assistance. All actors share a generated-content pipeline and cannot provide independent evidence of model behavior. The teacher role is a structured critique, not professional judgment. Completion and self-reported confidence are not mastery. Immediate application is not delayed retention. A successful replay is not learning impact.

## Next engineering gate

Freeze this baseline, revise the question-authoring safeguards without deleting the 20-question requirement, then rerun the same actors as a regression suite. Add adversarial fixtures for malformed anchors, unsupported claims, contradictory student answers, very short source passages and multilingual text. After that, the remaining decisive evidence must come from qualified educators and appropriately governed learner use.
