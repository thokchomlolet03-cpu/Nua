# Nua Assess — AI-Era Assessment Implementation Brief

Version: 0.8 architecture brief
Status: implementation target, not evidence of learning efficacy

## North star

Nua Assess is not an anti-AI assessment system and must not pretend ChatGPT, Codex or other AI systems do not exist. Its purpose is to make the learner's evidence trajectory visible across independent reasoning, assistance, revision, AI evaluation and later independent transfer.

The central question is:

> How can assessment help a learner become more capable with AI while remaining able to understand, judge, verify and act without blindly depending on it?

The product must distinguish at least these states:

1. What the learner can do independently.
2. Where the learner becomes uncertain or blocked.
3. What assistance the learner chooses or receives.
4. Whether assistance scaffolds thinking or substitutes for it.
5. Whether the learner can evaluate an AI-produced claim or explanation.
6. How the learner's reasoning changes after assistance.
7. Whether the underlying understanding survives after delay and context change without assistance.
8. What evidence an educator uses to choose a next instructional action.

Do not reduce this to a single mastery, intelligence, attention, delegation or AI-literacy score. The first implementation should preserve inspectable evidence and conditions.

## Architectural principle: construct != cognitive operation

The existing 24 Mangal inquiry types remain a reusable cognitive-operation registry. They are not, by themselves, the assessment constructs and the number 20 is not a scientific minimum.

Add an explicit construct layer with versioned definitions. Initial science pilot constructs:

- `evidence_evaluation`: evaluate claims, evidence, uncertainty, causal support, counterexamples and provenance when relevant.
- `investigation_design`: define measurements, compare conditions, identify variables/assumptions, design tests that distinguish explanations and identify challenging evidence.
- `transfer`: apply the same underlying reasoning in a changed surface context and identify where the mapping does or does not hold.

Question types are selected because they provide evidence for a construct. A lesson may use fewer than 20 questions if construct coverage, source sufficiency and educator review are satisfied. Never add filler to hit a count.

## Required versioned policies

Introduce these stable identifiers in exported records:

- `constructPolicy`: `nua-constructs/1`
- `taskPolicy`: `nua-ai-era-task/1`
- `rubricPolicy`: `nua-rubric/1`
- `assistancePolicy`: `nua-cognitive-support/1`
- `transferPolicy`: `nua-parallel-transfer/1`
- existing schema/policy identifiers remain for backward compatibility where practical

Every task should eventually carry `taskVersion`, `rubricVersion`, `constructId`, `constructVersion`, and, for transfer pairs, `transferPairId` plus `transferTaskRole` (`initial` or `parallel`).

## Cognitive effort preservation

AI assistance must be designed to preserve useful cognitive work rather than maximize answer completion speed.

Use a graduated assistance ladder:

- Level 0 — independent attempt; no assistance.
- Level 1 — metacognitive cue: asks the learner to identify the point of difficulty or relevant reasoning goal.
- Level 2 — attention cue: points to a relevant feature, variable, source region or comparison without giving the inference.
- Level 3 — source scaffold: reveals the relevant reviewed evidence/anchor.
- Level 4 — reasoning scaffold: asks or provides a bounded sub-question/structure that advances one reasoning step without completing the answer.
- Level 5 — partial worked structure: supplies an incomplete template the learner must finish.
- Level 6 — direct explanation: pedagogically allowed only when the assessment evidence for the independent attempt has already been locked; mark this as high assistance and do not treat the resulting answer as independent competence.

The system must not infer mental state from timing, clicks or text. Learner help signals are self-report only.

## Assistance event model

Do not encode assistance only as one flattened string. Preserve independent dimensions.

Suggested normalized event:

```json
{
  "id": "uuid",
  "at": 0,
  "phase": "investigate",
  "questionId": "q7",
  "constructId": "investigation_design",
  "kind": "ai_hint",
  "level": 2,
  "policy": "nua-cognitive-support/1",
  "provider": "android-local",
  "model": "imported LiteRT-LM",
  "hintId": "uuid-or-null",
  "sourceAnchorUsed": true,
  "humanHelp": false,
  "learnerSignal": "unsure",
  "attemptExistedBeforeAssistance": true
}
```

Supported assistance kinds should include at least `source_view`, `ai_hint`, `educator_help`, `peer_help`, `worked_example`, and `other`. Do not claim off-screen help detection; record only declared/observed conditions.

Preserve the existing high-level response condition for backward compatibility, but derive/report richer assistance separately.

## Student workflow

Target evidence sequence:

`independent attempt -> learner uncertainty/help signal -> optional graduated assistance -> revision/reasoning continuation -> AI-evaluation task when assigned -> synthesis -> delay -> parallel transfer task without assistance -> educator evidence-to-action -> learner follow-up`

Important invariants:

- An independent response is locked before direct AI explanation can be shown.
- Transfer responses are not allowed to access the original source or AI assistance under the default transfer policy.
- Immediate-demo transfer remains clearly labelled and must not be mixed with delayed-transfer analysis.
- Assistance never silently changes an existing response's condition.
- Every assistance request creates an append-only event.
- The learner may ask for support; the product should not punish help-seeking.
- Source viewing and AI hinting are different assistance conditions and must remain separately observable.

## AI-evaluation tasks

Add an authentic task type in which the learner evaluates an AI-produced explanation rather than merely accepting it.

For a reviewed science item, the system may present a deliberately controlled explanation that is either sound, incomplete or contains a plausible error. The learner must identify which claims are supported, cite evidence, explain uncertainty, and propose what additional evidence would be needed.

The learner is assessed on reasoning and evidence, not on whether they agree or disagree with AI.

Do not generate adversarial content live for high-stakes use. For the pilot, educator-reviewed AI explanations should be versioned lesson content.

## Parallel delayed transfer

The current open-ended application prompt is useful practice but is not enough to claim calibrated transfer.

Add explicit transfer-pair metadata. A transfer pair contains:

- one initial task and one parallel task;
- the same `constructId`;
- different surface context;
- pre-reviewed comparable response criterion;
- a `transferPairId` shared by both;
- separate task/rubric versions;
- delay metadata and actual transfer mode.

Example: initial task concerns uncontrolled variables in plant growth; parallel task concerns cooling rates. Both require identifying confounds and designing a fair comparison.

The product must call this `parallel transfer evidence`, not proof of generalized mastery, until validated.

## Authoring AI, scaffolding AI, review AI

Treat the three AI roles as separate products with different trust boundaries.

### Authoring AI

Purpose: draft teacher-reviewable questions, criteria, transfer pairs and controlled AI explanations from selected curriculum material.

Cloud Gemini is acceptable for authoring when the teacher intentionally sends curriculum text. Every generated item remains unreviewed until a human approves it. Exact source anchors are provenance checks, not proof of educational correctness or answerability.

### Scaffolding AI

Purpose: provide the minimum useful learner support at a requested hint level.

Student-facing scaffolding should prefer local/on-device inference when available. The prompt must include the reviewed task, source anchor when permitted, learner's current attempt when policy permits, requested hint level and an explicit prohibition against revealing more than that level allows.

The Android LiteRT-LM bridge should be connected to the general material-based assessment flow rather than remaining a science-demo-only path.

### Review AI

Purpose: provide optional, non-authoritative suggestions to an educator.

AI must never silently convert a response into a final score. Prefer `supported`, `partly_supported`, `needs_clarification`, and `uncertain` over authoritative grading. Human evidence-to-action records remain primary.

Any cloud review of raw learner responses requires an explicit privacy boundary in the UI and API: the educator must be told that response text will be sent to the configured external provider. Do not send names or student identifiers. Local/manual review must remain available.

## Source grounding and answerability

Keep exact source-anchor validation, but add `evidenceScope`/`answerability` because an exact quote alone does not prove the question is answerable from the source.

Allowed values:

- `source_explicit`
- `source_inferable`
- `prior_knowledge_required`
- `investigation_design`
- `external_evidence_required`
- `transfer`

Teacher review must confirm both the exact anchor and evidence scope before export.

## Teacher evidence-to-action

Preserve the current evidence-linked educator workflow. A saved educator interpretation should continue to cite an exact substring of the learner response and include a concrete next step plus an observable success criterion.

AI suggestions may prefill a draft, but the saved educator interpretation must remain clearly human-approved and identity verification limitations must remain explicit.

Do not compress heterogeneous evidence into one score in v0.8.

## Privacy boundary

Student data is local by default. The Android app should continue to omit INTERNET permission for the learner workflow.

Cloud Gemini question generation is a teacher-authoring action. Cloud Gemini response diagnosis is a separate operation and must require explicit opt-in each time or an institution-configured policy with visible disclosure.

Full evidence exports may contain student text and are not automatically anonymous. Summary exports should minimize prose. Do not claim de-identification merely because names are absent.

## Backward compatibility and migration

Do not destroy existing `nua-mangal/1` sessions. Add new fields as optional for old records. Validation should accept legacy records and normalize them in reports without silently rewriting historical evidence.

New sessions should initialize:

```json
{
  "assessment": {
    "constructPolicy": "nua-constructs/1",
    "taskPolicy": "nua-ai-era-task/1",
    "rubricPolicy": "nua-rubric/1",
    "assistancePolicy": "nua-cognitive-support/1",
    "transferPolicy": "nua-parallel-transfer/1"
  },
  "assistanceEvents": []
}
```

## Implementation sequence

Phase 1 — domain model and tests

- Add construct registry and mappings from cognitive operations to constructs.
- Replace the hard `MIN_TYPES = 20` assumption with a policy-driven selection model.
- Ensure Measurement and Experiment are first-class options in science pilot policy.
- Add task/rubric/construct/version metadata.
- Add assistance-event module with validation and append-only recording.
- Add transfer-pair metadata and validation.
- Add tests before UI changes.

Phase 2 — learner UI and local scaffolding

- Update preparation UI to show construct coverage, not only question count.
- Allow educator to choose/review task types; block approval when required pilot constructs are missing, not when count is below 20.
- Add help-request flow: first ask what kind of difficulty the learner has, then choose bounded hint level.
- Integrate Android `NuaNative` hint bridge into material-based questions.
- Record hint request, returned hint metadata and whether an attempt existed before help.
- Keep full answers hidden until allowed by policy.

Phase 3 — AI evaluation and transfer

- Add educator-authored/reviewed AI-explanation evaluation items.
- Add transfer-pair authoring and delayed parallel task delivery.
- Disable source/AI support during default delayed transfer.
- Report initial-assisted-revised-transfer trajectory without converting it into a global score.

Phase 4 — review AI/privacy

- Change Gemini learner-response diagnosis to advisory output with explicit uncertainty.
- Add cloud disclosure/confirmation before raw learner response transmission.
- Prefer evidence quote extraction and criterion linkage over a binary correctness flag.

Phase 5 — research readiness

- Update event schema documentation.
- Version task/rubric/construct/assistance policies.
- Produce synthetic fixtures for independent -> assisted -> revised -> transfer trajectories.
- Update pilot protocol so 20 questions are not assumed optimal. Evaluate construct coverage, item quality, workload, pacing, help use and transfer evidence.

## Minimum acceptance tests

The implementation is not complete until tests demonstrate at least the following:

1. A science plan can include `measurement` and `experiment` while containing fewer than 20 tasks.
2. Plan approval can be based on required construct coverage rather than a fixed question count.
3. An assistance event cannot be added without valid policy, timestamp, kind and level.
4. `source_view` and `ai_hint` remain distinguishable in exported evidence.
5. A direct-explanation hint cannot be labelled independent performance.
6. Old sessions without `assessment` or `assistanceEvents` still validate/read.
7. New sessions export versioned construct/task/rubric/assistance/transfer policies.
8. A transfer task can be paired to an initial task with the same construct and different role.
9. Delayed transfer records whether it was genuinely delayed or immediate demo.
10. The default delayed transfer path has no source/AI support event.
11. AI review remains advisory and cannot overwrite the human evidence-to-action record.
12. Cloud diagnosis endpoint/UI surfaces a privacy disclosure before learner text is transmitted.
13. Android learner app continues to build without INTERNET permission.
14. Existing anchor validation and educator evidence-quote validation still pass.

## Product language

Prefer:

- independent evidence
- assisted evidence
- learner-reported uncertainty
- bounded scaffold
- evidence trajectory
- parallel transfer evidence
- educator interpretation
- suggested next step

Avoid unless validated:

- mastery
- cognitive independence score
- attention span score
- delegation score
- proven learning gain
- AI-proof assessment
- measures intelligence

## Definition of done for v0.8 architecture

A v0.8 candidate is ready for educator feasibility review when a teacher can author a science assessment around the three constructs, assign a reviewed subset of cognitive probes, a learner can first attempt tasks independently and request recorded graduated support, the system can preserve a later parallel transfer task without assistance, an educator can inspect the entire evidence trajectory and choose a next step, and all of this is represented in versioned exportable records without requiring a single aggregate score.

Engineering success is not validation of educational impact. Keep that distinction explicit in the UI, documentation and competition materials.
