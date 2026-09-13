# Second synthetic agent assessment round

Run date: 13 September 2026. This is a controlled software QA exercise, not a real-user study, educator validation, psychometric analysis, retention result or evidence of learning impact.

## Actors

Six new student agents were used, distinct from the first round:

1. Transfer learner — understands the definition but struggles with a new context.
2. Overconfident learner — fluent responses with unjustified generalisations.
3. Minimal-response learner — short, valid answers and potential writing-burden signal.
4. Additional-language learner — simple, occasionally awkward English and wording sensitivity.
5. Creative learner — unusual but potentially relevant analogies and examples.
6. Cautious learner — selective source requests and uncertainty even where partial reasoning is present.

Two new teacher agents reviewed the frozen synthetic lesson. One focused on construct validity and cognitive differentiation; the other focused on classroom practicality, accessibility, pacing and teacher workload. Both preserved the requirement that all 20 inquiry types remain in the system.

The student agents read only the task brief. They did not receive source code, hidden validation rules, previous student answers or the teacher reviews. Their JSON fixtures were replayed through `mangal-core.js`, not merely read as text.

## Production replay result

All six actors completed all 20 questions and the immediate-demo application. Each passed 23 production checks covering invalid short input, paused submission, premature delayed return, evidence preservation, source-support labels, response locking and minimized export behavior.

| Actor | Completed | Source-support requests | Signal pattern | Agent-reported confusion |
|---|---:|---:|---|---|
| Transfer | 20/20 | 8 | 15 ready, 3 unsure, 2 help | q6, q8, q11, q13, q18/q20 |
| Overconfident | 20/20 | 0 | 17 ready, 3 unsure | q12, q13, q20 |
| Minimal | 20/20 | 0 | 18 ready, 1 unsure, 1 help | q3, q8, q12, q13, q15 |
| Additional-language | 20/20 | 0 | 16 ready, 3 unsure, 1 help | q3, q15, q17, q20 |
| Creative | 20/20 | 0 | 18 ready, 2 unsure | q6, q15 |
| Cautious | 20/20 | 6 | 16 ready, 3 unsure, 1 help | q5, q6, q9, q20 |

These counts describe authored agent outputs, not distributions of actual students. “Ready” is a synthetic self-report and is not a correctness score.

## Cross-actor findings

The strongest recurring issues are:

- q20: under-specified generalisation/inquiry task, often without a concrete new population, environment or result.
- q13: counterexample task needs the claim and hypothetical difference stated explicitly.
- q15: evidence/uncertainty task needs a minimum evidence checklist or ranked choices.
- q6: prerequisite/variable language is abstract and absent from the source glossary.
- q3: the biological-mechanism branch is not supported by the source and can reward guessing rather than causal reasoning.
- q8 and q18: classification and transfer need explicit response structures, such as change / keep equal / measure.

The construct reviewer also found a dense causal-diagnosis cluster: q7, q9, q11, q14 and q17 vary surface form more than underlying reasoning. This does not justify deleting them. It requires distinct stimuli and outputs: paired designs, a third confounder, error diagnosis and a comparison matrix.

The practical reviewer found that 20 open responses should be staged across three sessions for this age range. That is a pacing proposal, not validated timing. Multiple response modes—short text, table, diagram or supervised oral explanation—can reduce writing burden while preserving each question’s reasoning criterion.

## What the second round demonstrates

The system can preserve a fixed 20-question breadth requirement while handling varied response length, language expression, uncertainty, source requests, transfer attempts and persistent misconceptions. Its exact-quotation validation can prevent invented evidence from entering the educator feedback record. These are useful engineering properties.

The system cannot infer that an agent’s persona corresponds to a real learner group. It cannot distinguish fluent unsupported reasoning without a qualified reviewer and a defensible rubric. It cannot establish that 20 types are genuinely distinct for students, that the staged schedule works, or that any feedback produces durable learning.

## Decision before system update

Do not add broad new features. Update only the assessment-generation and authoring safeguards in the next pass:

1. require complete, question-specific source anchors;
2. require explicit premises for hypothetical results and counterexamples;
3. add a small glossary and concrete change / keep equal / measure scaffolds;
4. distinguish the five causal questions through different stimuli and response requirements;
5. preserve all 20+ inquiry types but support staged delivery and alternate response formats;
6. add fixture-level regression checks for overgeneralisation, unsupported mechanisms, short answers, language variation and creative-but-off-target responses.

These changes should be made only after preserving this round’s fixtures as a frozen regression baseline. The next result remains simulated QA; qualified educators and governed learner use are still required before claiming educational validity.
