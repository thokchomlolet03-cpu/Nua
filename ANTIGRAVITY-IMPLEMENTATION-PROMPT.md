# Google Antigravity entrypoint — Nua Assess AI-era assessment

Work on branch `codex/ai-era-assessment-v1`.

The complete product and implementation specification is `IMPLEMENTATION-BRIEF-AI-ERA-ASSESSMENT.md`. Read it first. The Nua Assess implementation is under `assess/`; the same AI-era foundation has already been mirrored there:

- `assess/web/assessment-constructs.js`
- `assess/web/assistance.js`
- `assess/web/transfer.js`
- `assess/web/ai-era-core.js`
- `assess/tests/ai-era-assessment.test.mjs`

The corresponding standalone implementation branch is `thokchomlolet03-cpu/nua-assess:codex/ai-era-assessment-v1`. Keep `assess/` behavior synchronized with that repository rather than letting two incompatible assessment engines emerge.

## Mission

Implement Nua Assess as an assessment system for the AI age, not an AI-free exam simulator. It must make visible what a learner can do independently, where they need support, how they use AI, whether they can evaluate AI output, how their reasoning changes, and whether the underlying understanding transfers later without assistance.

Do not create a single mastery, intelligence, attention, delegation, cognitive-independence or AI-literacy score. Preserve the evidence trajectory.

## Continue the implementation in this order

1. Replace the fixed-first-20 question assumption for new sessions with construct coverage using `evidence_evaluation`, `investigation_design` and `transfer`, while keeping old saved records readable.
2. Make the Nua Assess learner/teacher UI use the AI-era core for new sessions and display construct, cognitive operation, evidence scope, source anchor and version metadata.
3. Implement graduated learner help levels 0–6 and record each source/AI/human assistance event separately.
4. Integrate the existing Android LiteRT-LM bridge with general material-based questions; keep the learner APK without INTERNET permission. A local hint must be bounded to the requested level and return structured provenance metadata.
5. Add reviewed AI-explanation evaluation tasks: the learner judges claims using evidence rather than being graded on agreement with AI.
6. Add reviewed parallel transfer task pairs: same construct, different surface context, comparable criterion, delayed task without source/AI help by default.
7. Make cloud Gemini learner-response review explicitly opt-in, advisory and evidence-linked. Server must reject raw-response cloud analysis without privacy acknowledgement. Never silently transmit learner text.
8. Preserve the existing educator evidence-to-action workflow as primary: exact learner quote + interpretation + next step + observable success criterion + separate learner follow-up.
9. Update reports to expose construct/task/rubric/assistance/transfer/model versions and evidence conditions without an aggregate score.
10. Update pilot/competition docs: the 24 Mangal operations are a probe registry, not proof that 20 questions is optimal. Treat workload, pacing, construct validity and learning effects as empirical questions.

## Assistance policy

- 0 independent
- 1 metacognitive cue
- 2 attention cue
- 3 source scaffold
- 4 reasoning scaffold
- 5 partial worked structure
- 6 direct explanation only after relevant independent evidence is locked

AI use is not failure. Help-seeking must not be punished. Do not infer hidden mental states from clicks/timing.

## AI roles

Keep three distinct trust boundaries:

- Authoring AI: teacher-side drafting; human approval mandatory.
- Scaffolding AI: learner-side bounded support; prefer local/on-device.
- Review AI: optional educator advisory support; never authoritative scoring.

## Verification

Before editing, run the current Node tests and record the actual baseline. After changes run the complete Node suite and the Android test/build/lint commands documented by the project. Do not claim a command passed if it was not run.

Any old test whose only purpose is enforcing a universal 20-question minimum should be replaced by two tests: legacy breadth records still load, and new AI-era plans validate by construct coverage without filler.

At minimum verify:

- Measurement and Experiment can appear in a valid new plan with fewer than 20 tasks.
- all three science-pilot constructs are covered;
- task/rubric/construct/policy versions survive export;
- source, AI and human assistance remain distinct;
- direct explanation requires locked independent evidence;
- old records without AI-era fields remain readable;
- transfer pair validation and no-assistance delayed transfer;
- immediate demo never masquerades as delayed transfer;
- cloud raw-response review requires explicit privacy acknowledgement;
- AI review cannot overwrite educator feedback automatically;
- Android manifest still has no INTERNET permission;
- source-anchor and exact feedback-evidence validation remain intact.

When complete, report files changed, migration notes, exact test/build results, remaining research/validation gaps, privacy limitations and commit SHA(s). Do not merge to the main branch until reviewed.
