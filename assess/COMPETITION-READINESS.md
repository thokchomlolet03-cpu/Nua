# Competition alignment review — Nua Assess 0.7.0

Reviewed 13 September 2026. This report distinguishes implemented behavior, engineering checks, proposed research and unverified claims. It is not a funding prediction. The exact submitted Phase I answers were not available for reconciliation; no submission was changed.

## Bottom line

Nua Assess is a credible early-stage prototype for the **Reimagining Assessment / Catalyst** category, not yet a validated school assessment. Catalyst does not require a minimum user count. The track emphasizes K–12 assessment that produces useful next steps, meaningful student agency and accessible use. Its criteria include novelty, learning impact, equity, demand, learning engineering and scale. These priorities fit the intended direction, but implementation alone does not establish their achievement. [Official assessment track](https://tools-competition.org/27-assessment/)

The strongest positioning is: **turn teacher-selected learning material into a reviewed, focused inquiry sequence that makes students' reasoning visible and connects that evidence to a next instructional step.** Local AI is an optional preparation method, not the educational outcome. Twenty-plus distinct inquiry types are the central design commitment; their educational benefit remains a hypothesis to test.

## What the system actually does

1. Locally extracts readable PDF, TXT or Markdown, or accepts pasted text. Limits are explicit: 12 MB, 80 pages and 180,000 extracted characters.
2. An author chooses a learning objective and supporting passage. This is not semantic analysis of every concept in the uploaded document.
3. Creates a draft set of 20 distinct reasoning types. The registry contains 24 types; a plan can contain up to 40 questions. Each question has a source anchor, relevance explanation, evidence condition and observable criterion.
4. Offers optional desktop Ollama drafting in batches of four. Templates remain available without inference. Human review is mandatory; structural validation cannot determine educational correctness.
5. Presents one question at a time while requiring the full approved set. The objective stays visible; pauses and saved drafts retain the remaining questions. Focus restricts distraction, not inquiry breadth.
6. Preserves recall, individual inquiry responses, learner-generated questions, synthesis/revision and subsequent application as separate evidence. Source consultation labels the affected response. A 24-hour gate has an explicitly labelled immediate-demo override.
7. Now records optional learner uncertainty/help signals, evidence-linked educator interpretations, a concrete next step and an observable success criterion. The learner can record a separate follow-up after trying that step.
8. Exports either minimized metadata or full evidence. Prepared lessons can be reused without regenerating questions; imported lessons must be reviewed again.

The browser works offline after assets are cached. Android packages the material workflow, but new question drafting currently runs only on the desktop. The Android native model bridge belongs to the older fixed science demonstration, not general uploaded-material drafting.

## Gap assessment

These judgments concern the current repository, not all eligibility conditions or the text of the submitted application.

| Area | Evidence now | Remaining distance |
|---|---|---|
| Assessment purpose | Separate attempts, source-use labels, question criteria and new evidence-to-action loop | Educators must establish whether this changes a useful instructional decision |
| Mangal differentiation | At least 20 distinct inquiry types; one objective; one-at-a-time delivery | Distinct labels do not guarantee distinct reasoning demands; expert review and comparisons are needed |
| Material relevance | Exact selected-passage anchors and author review | No full-document concept coverage, OCR or dependable diagram/table interpretation; insufficient passages must be expanded, not padded with filler |
| Question quality | Bounded local drafting with rejection and templates | A previous four-question local-model check was structurally usable but educationally weak. No credible automatic question-quality claim yet |
| Actionability | Educator cites actual response text, chooses interpretation, gives next step and check; learner follow-up remains separate | Text inclusion validates quotation, not the correctness of the judgment. No automatic grading or authenticated educator role |
| Timing | Immediate source support; recorded uncertainty; later educator review | Educator feedback opens only after application to preserve that attempt's condition. This is not live classroom intervention or continuous teacher monitoring |
| Teacher workload | Reusable lesson JSON, drafts and prioritization of help requests | Every question still needs initial review. Preparation and review time have not been measured with teachers |
| Learning evidence | Versioned, restorable evidence with support conditions | No real learner outcomes, validated rubric, equated tasks or causal impact estimate |
| Access | Offline completion, no recurring inference requirement, no native Internet permission | English interface, device literacy, accessibility and low-end hardware need field tests; offline is not automatically inclusive |
| Cost and scale | Generate/review once, reuse repeatedly; follow-up has no AI fee | Authoring, teacher time, device provision, maintenance and support remain real costs. No deployment economics established |
| Privacy and governance | Local storage; no automatic research transmission; minimized export option | No institutional consent workflow, role separation, encrypted student-record system or approved research-sharing arrangement |
| Adoption | Runnable prototype and portable prepared lessons | No verified school partner, demand study, recurring classroom usage or deployment trial |

## Changes made in this alignment pass

- Added `assessment-feedback.js` with a versioned evidence-to-action record. A saved interpretation must quote 12–500 characters present in the selected learner response and include a concrete next step and observable check.
- Added optional per-question self-reports: ready to explain, unsure, needs help, or not recorded. These are not inferred emotions, focus measurements or diagnoses.
- Educator review initially selects a flagged response when available. Reviewing a useful subset is allowed; the learner still completes all required inquiry questions.
- Educator drafts save locally. Saved feedback history and learner follow-ups preserve earlier responses and application conditions. Teacher identity remains explicitly unverified.
- Summary exports omit quoted evidence, next-step prose and follow-up text; full exports include them. Even summaries are not guaranteed anonymous.
- Fixed source-anchor editing: incomplete draft anchors can now be saved while editing, but exact source matching is still required at approval.
- Added deterministic response contracts for every default inquiry type, optional type-relevant plain-language glossary terms, generic safeguards for the causal question cluster, three pacing checkpoints and complete source-anchor selection. Local-AI drafted items inherit the same contract and glossary metadata; human review remains required.
- Updated offline assets and Android packaging to version 0.6.0 / Android version code 7. This is a code and workflow update, not evidence of improved learning.

This directly addresses the webinar's emphasis on interpretable learning evidence and the next instructional move, rather than treating response collection as sufficient. [Supplied webinar transcript, assessment discussion](</Users/lolet/.codex/attachments/d12072bb-10ad-4bf4-96b1-9847561b2a58/pasted-text.txt:483>)

## Research and public contribution

The competition's learning-engineering material emphasizes improvement through learning data, research questions and collaboration. Event logs alone do not deliver that. The companion [pilot protocol](PILOT-PROTOCOL.md) defines question-quality review, feasibility measures, evidence interpretation and explicit limits on learning claims. [Learning engineering guidance](https://tools-competition.org/learning-engineering/)

Proposed public contribution: the inquiry taxonomy, annotation/evaluation protocol, export schema documentation and synthetic examples. These can support replication without publishing student answers or copyrighted class material. They are proposed deliverables, not already published or licensed public goods. Confirm release permissions and an appropriate license before external publication.

The rules call for broad affordability and a public-good contribution; awardees also have evaluation obligations. Verify the actual submitted commitment and all applicable entrant conditions before making new promises. Local processing alone does not meet these obligations. [Official rules](https://tools-competition.org/27-official-rules/)

## Focused route forward

**First gate — educator usefulness.** Proposed initial scope: ages 12–16, one science unit, English interface, teacher-supervised use. This is a pilot recommendation, not a confirmed submitted audience. Recruit a small educator review group and review permitted material before collecting learner data. Check all 20+ questions for genuine distinctness and source sufficiency. Stop plans containing fabricated premises or unresolved missing material.

**Second gate — workflow feasibility.** Observe preparation time, revision burden, question completion across short sessions, uncertain responses, teacher interpretation and follow-up usefulness. Preserve the 20-question minimum; investigate pacing and scheduling instead of quietly cutting the inquiry breadth.

**Third gate — evaluation readiness.** Agree on observable response criteria, independent educator ratings, consent/data handling and a research partner's study design. Small feasibility pilots establish usability and failure modes, not educational efficacy.

**Fourth gate — sustainable reuse.** Test exporting a prepared lesson, importing/reviewing it on another device, offline completion and evidence handoff. Measure realistic teacher and support costs. Test a physical Android phone before claiming on-device deployment readiness.

Avoid new subjects, gamification, dashboards, speculative brain models and cloud routing until these gates expose a concrete need. Cloud AI may eventually help authoring quality, but it requires an explicit cost/privacy decision and a quality benchmark first.

## Competition timing and claims

The published schedule lists Phase I on 13 October 2026, Phase II invitations on 24 November 2026, detailed proposals on 21 January 2027 and virtual finalist pitches in April 2027. An inconsistent banner on the assessment page says Phase II is closed; use the dated schedule and verify any account-specific notice with the organizer. Phase III is not evidence of a US travel invitation. [Competition schedule](https://tools-competition.org/)

Appropriate claims now: working local-first prototype; reviewed 20-plus inquiry workflow; separately labelled evidence conditions; optional local drafting; educator-controlled follow-up; engineering checks.

Unsupported claims now: proven learning gains; uniquely human skill measurement; mastery certification; validated focus/brain training; full-document understanding; autonomous reliable assessment; established school adoption; physical-phone performance; competition selection probability.

## Verification record

Automated suite: 89 Node tests passed, including the controlled safeguard fixtures, generic response scaffolds, source-anchor handling, feedback validation, immutable follow-up, learner signals, draft restoration, export minimization and draft-anchor editing. Android 0.6.0 assembly, lint and JVM tests passed; lint reports six non-blocking warnings and no errors. A fresh browser smoke test visibly confirmed the response contract, relevant glossary and Session 2 checkpoint after eight responses. This verifies engineering behavior, not educational impact. Detailed browser acceptance and remaining device limitations are recorded in [CHECKPOINT.md](CHECKPOINT.md).
