import {
  KEY,
  POLICY,
  phases,
  perspectives,
  normalizePages,
  templatePlan,
  validatePlan,
  createInquiry,
  validateInquiry,
  validatePreparation,
  beginRecall,
  exposeSource,
  submit,
  returnForTransfer,
  parkQuestion,
  saveReflection,
  saveReview,
  inquiryReport,
  addFeedback,
  addFollowup,
} from "./mangal-core.js";
import {
  interpretations,
  learnerSignals,
  saveFeedbackDraft,
} from "./assessment-feedback.js";
import {
  questionTypes,
  inquirySessions,
  questionGuidance,
  expandPlan,
  makeQuestion,
  validateQuestionSet,
  MAX_QUESTIONS,
} from "./inquiry-questions.js";
import { withSessionEditor } from "./storage.js";
import { readMaterial } from "./material.js";
import { reviewInput, searchLink, approveSupplement } from './material-review.js';
const PREP = "nua-mangal-preparation-v2";
const $ = (s) => document.querySelector(s);
const esc = (x) =>
  String(x ?? "").replace(
    /[&<>"']/g,
    (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        c
      ],
  );
let session = null,
  setup = {
    title: "",
    pages: [],
    warnings: [],
    objective: "",
    page: 1,
    excerpt: "",
    plan: null,
    mode: "teacher-reviewed",
  },
  expected = null,
  prepExpected = null,
  owns = false,
  error = "",
  busy = false,
  showSource = false,
  message = "",
  editingQuestion = 0,
  feedbackQuestionIndex = null;
const labels = {
  prepare: "Prepare",
  recall: "Recall independently",
  investigate: "Explore the inquiry set",
  question: "Ask a useful question",
  revise: "Check and revise",
  waiting: "Practice complete",
  transfer: "Apply independently",
  complete: "Evidence and next step",
};
const pending = new Map();
window.nuaReply = (id, result) => {
  const p = pending.get(id);
  if (p) {
    clearTimeout(p.timer);
    pending.delete(id);
    result.error ? p.reject(Error(result.error)) : p.resolve(result);
  }
};
function nativeExport(name, text) {
  return new Promise((resolve, reject) => {
    const id = crypto.randomUUID(),
      timer = setTimeout(() => {
        pending.delete(id);
        reject(Error("Export did not finish."));
      }, 600000);
    pending.set(id, { resolve, reject, timer });
    window.NuaNative.request(id, "export", JSON.stringify({ name, text }));
  });
}
async function download(data, name) {
  try {
    if (window.NuaNative) await nativeExport(name, JSON.stringify(data));
    else {
      const url = URL.createObjectURL(
        new Blob([JSON.stringify(data, null, 2)], { type: "application/json" }),
      );
      const a = document.createElement("a");
      a.href = url;
      a.download = name;
      a.click();
      setTimeout(() => URL.revokeObjectURL(url), 1500);
    }
    message =
      "Export requested. Check the saved file before replacing any work.";
  } catch (e) {
    message = e.message;
  }
  render();
}
function persist() {
  if (!owns || error) return false;
  try {
    if (session) validateInquiry(session);
    else {
      setup.questionIndex = editingQuestion;
      validatePreparation(setup);
    }
    if (
      localStorage.getItem(KEY) !== expected ||
      localStorage.getItem(PREP) !== prepExpected
    )
      throw Error(
        "Saved work changed in another window. Preserve a recovery copy and reload.",
      );
    if (session) {
      const value = JSON.stringify(session);
      localStorage.setItem(KEY, value);
      expected = value;
    } else {
      const value = JSON.stringify(setup);
      localStorage.setItem(PREP, value);
      prepExpected = value;
    }
    return true;
  } catch (e) {
    error = "Progress could not be saved: " + e.message;
    render();
    return false;
  }
}
function change(fn) {
  if (!owns || busy || error) return;
  try {
    fn();
    if (persist()) {
      showSource = false;
      message = "";
      render();
      window.scrollTo(0, 0);
    }
  } catch (e) {
    message = e.message;
    render();
  }
}
function header() {
  return '<header><div class="brand">nua<span>Mangal Inquiry</span></div><span class="pill">One objective · Multiple angles</span></header>';
}
function footer() {
  return "<footer><span>Nua Assess 0.7.0 · Mangal Inquiry</span><span>Local storage · No cloud AI · Educational validation pending</span></footer>";
}
function source() {
  return `<section class="card tinted"><h2>Source passage · page ${session.plan.page}</h2><p class="response">${esc(session.plan.quote)}</p><p class="small">According to uploaded material, not independently fact-checked. Original diagrams and layout are not shown.</p></section>`;
}
function fields(plan) {
  return `${plan.questions ? questionEditor(plan) + "<details><summary>Recall, synthesis criteria and delayed application</summary>" : ""}<label ${plan.questions ? "hidden" : ""}>Relevant perspective<select id="perspective">${Object.entries(
    perspectives,
  )
    .map(
      ([key, p]) =>
        `<option value="${key}" ${key === plan.perspective ? "selected" : ""}>${p.title}</option>`,
    )
    .join("")}</select></label>${[
    ["reason", "Why this perspective?"],
    ["recall", "Closed-source recall question"],
    ["investigate", "Perspective question"],
    ["transfer", "Independent application question"],
    ["rubric", "Feedback / educator review criteria"],
  ]
    .map(
      ([k, label]) =>
        `<label ${plan.questions && ["reason", "investigate"].includes(k) ? "hidden" : ""}>${label}<textarea id="plan-${k}" maxlength="1800" minlength="12" required>${esc(plan[k])}</textarea></label>`,
    )
    .join("")}${plan.questions ? "</details>" : ""}`;
}
function questionEditor(plan) {
  editingQuestion = Math.min(editingQuestion, plan.questions.length - 1);
  const q = plan.questions[editingQuestion];
  const guidance = questionGuidance(q);
  return `<h2>Multiple angles. One coherent objective.</h2><p>${plan.questions.length} questions · ${new Set(plan.questions.map((q) => q.type)).size} distinct types · <span id="review-count">${plan.questions.filter((q) => q.reviewed).length}</span> reviewed. Every question is part of the learner journey, not optional extra content.</p><p class="notice">Templates are starting prompts, not automatically material-specific assessments. Review relevance, distinct reasoning, source sufficiency and criteria for every question. If the material cannot support the set, expand the selected material instead of approving filler.</p><label>Question to review<select id="edit-question">${plan.questions.map((q, i) => `<option value="${i}" ${i === editingQuestion ? "selected" : ""}>${i + 1}. ${questionTypes[q.type].title}</option>`).join("")}</select></label><label>Reasoning type<select id="question-type">${Object.entries(
    questionTypes,
  )
    .map(
      ([type, def]) =>
        `<option value="${type}" ${q.type === type ? "selected" : ""}>${def.title}</option>`,
    )
    .join(
      "",
    )}</select></label><p>Draft origin: ${esc(q.origin)}${q.model ? " · " + esc(q.model) : ""}</p>${[
    ["prompt", "Question"],
    ["relevance", "How this advances the objective"],
    ["criterion", "Observable evidence to look for"],
    ["responseContract", "Learner response contract (what must be shown)"],
    ["anchor", "Exact source anchor (20–300 characters)"],
  ]
    .map(
      ([key, label]) =>
        `<label>${label}<textarea id="question-${key}" maxlength="${key === "anchor" ? 300 : key === "responseContract" ? 1000 : 1800}">${esc(key === "responseContract" ? q[key] || guidance.responseContract : q[key] || "")}</textarea></label>`,
    )
    .join(
      "",
    )}<label>Evidence condition<select id="question-kind"><option value="source" ${q.kind === "source" ? "selected" : ""}>Answer supported by the passage</option><option value="investigation" ${q.kind === "investigation" ? "selected" : ""}>Investigation: reasoning or additional evidence needed</option><option value="needs-material" ${q.kind === "needs-material" ? "selected" : ""}>Blocked: more material needed before assignment</option></select></label><label class="option"><input id="question-reviewed" type="checkbox" ${q.reviewed ? "checked" : ""}> I reviewed this question's distinct reasoning, relevance, source anchor and criteria.</label><div class="actions"><button type="button" id="previous-question" class="secondary" ${editingQuestion === 0 ? "disabled" : ""}>Previous question</button><button type="button" id="next-question" class="secondary" ${editingQuestion === plan.questions.length - 1 ? "disabled" : ""}>Next question</button></div><details><summary>Extend or refine the inquiry set</summary><p>Choose distinct types that cover your objective. This prototype supports up to 40 questions per set. Additional questions must earn their place through relevance, not repeated wording.</p><button type="button" id="add-question" class="secondary" ${plan.questions.length >= MAX_QUESTIONS ? "disabled" : ""}>Add another question</button><button type="button" id="remove-question" class="secondary" ${plan.questions.length <= (plan.selectionPolicy === "objective-coverage/1" ? 1 : 20) ? "disabled" : ""}>Remove this draft question</button></details>`;
}
function setupScreen() {
  if (!setup.pages.length)
    return `<section class="hero"><div><span class="pill">MATERIAL → 20+ INQUIRY ANGLES → EVIDENCE</span><h1>Many angles.<br>One focus.</h1><p class="lead">Explore your lesson through a reviewed range of distinct question types, one question at a time. Recall, investigate, synthesize, revise and return to apply.</p><p>Focus means staying connected to the material—not narrowing the inquiry. Pause across sessions without losing the remaining questions.</p></div></section><section class="card"><h2>Start with your material</h2><p>Use material you are permitted to process. Files stay on this device; optional AI drafting sends only the selected passage to the local model on this computer.</p><label>Lesson title<input id="title" maxlength="160" value="${esc(setup.title)}" placeholder="For example: Causes and evidence"></label><label>Readable PDF, text or Markdown<input id="material-file" type="file" accept=".pdf,.txt,.md"></label><p class="small">Maximum 12 MB / 80 pages / 180,000 extracted characters. No OCR, audio, video or editable slide parsing. PDF extraction needs a modern browser/WebView.</p><label>Or paste a checked lesson passage<textarea id="pasted" maxlength="180000" placeholder="Paste the material, not personal student information."></textarea></label><button id="use-paste" class="primary">Review this material</button></section><p class="small">School-age use requires educator supervision. This is an English-interface prototype; extracted text may be multilingual, but language quality needs review.</p><p><a href="demo.html">Open the fixed science demonstration</a> · Existing demo sessions are kept separately.</p>`;
  if (!setup.plan)
    return `<span class="eyebrow">Review coverage before choosing a focus</span><h1>One lesson.<br>Many ways to inquire.</h1><section class="card"><h2>${esc(setup.title)}</h2><p>${setup.pages.length} page/section(s) processed. This is a text extraction preview, not an AI claim of full-document understanding.</p>${setup.warnings.map((w) => `<p class="notice">${esc(w)}</p>`).join("")}<label>Inspect page / section<select id="page">${setup.pages.map((p) => `<option value="${p.page}" ${p.page === setup.page ? "selected" : ""}>${p.page} · ${p.text.length} characters${p.text.length < 40 ? " · CHECK MISSING TEXT" : ""}</option>`).join("")}</select></label><details><summary>Show all extracted text on this page</summary><p class="response">${esc(setup.pages[setup.page - 1].text || "No text extracted.")}</p></details><label>Exact passage for this inquiry (40–6000 characters)<textarea id="excerpt" maxlength="6000">${esc(setup.excerpt)}</textarea></label><p class="small">Select a contiguous passage from this page rich enough for 20 distinct inquiry types. If it is too thin, choose broader material or paste a checked lesson section. This version does not semantically map the whole document. Do not use text whose meaning depends on an unread diagram.</p><label>What should the learner be able to explain or do?<textarea id="objective" maxlength="300" minlength="12" placeholder="Use one specific, observable objective.">${esc(setup.objective)}</textarea></label><button id="draft-plan" class="primary">Prepare inquiry questions</button><p class="small">The initial set uses editable question-type templates. Local AI can tailor four questions at a time; human review is required either way.</p></section>`;
  const p = setup.plan;
  return `<span class="eyebrow">Preparation · not a learner test</span><h1>Review the full inquiry.</h1><p class="notice">Review every question and its criteria. They are drafts, not validated assessment items. Editing is locked once learning starts.</p><section class="card"><h2>Objective</h2><p>${esc(p.objective)}</p><details><summary>Source · page ${p.page}</summary><p class="response">${esc(p.quote)}</p></details>${!p.questions ? '<p class="notice">Legacy preparation: preserve or export it first, then expand it for the new 20+ workflow.</p><button id="expand-legacy" class="secondary">Expand to 20 inquiry types</button>' : ""}<form id="approve-form">${fields(p)}<label>Review condition<select id="mode"><option value="teacher-reviewed" ${setup.mode === "teacher-reviewed" ? "selected" : ""}>Educator reviewed (identity not authenticated)</option><option value="self-study-provisional" ${setup.mode === "self-study-provisional" ? "selected" : ""}>Self-study provisional (questions and criteria already seen)</option></select></label><label class="option"><input type="checkbox" id="checked-source" required> I checked source accuracy, extraction, the full set's distinct reasoning and the relevance of its questions. Investigation tasks are labelled; missing prerequisites are resolved.</label><button class="primary">Approve and begin preparation</button></form><div class="actions"><button id="edit-source" class="secondary">Back to source and objective</button>${window.NuaNative ? '<p class="small">AI drafting is desktop-only. Edit templates or import a reviewed plan on this device.</p>' : '<button id="ai-plan" class="secondary">Tailor next four unreviewed questions with local AI</button>'}<button id="export-plan" class="secondary">Export lesson plan</button></div><p class="small">Each question gets at most one AI attempt in this preparation. Batches preserve reviewed questions and keep templates on failure. ${setup.aiTypesTried?.length || 0} question attempts used. No cloud fallback; reused plans need no new model calls.</p></section>`;
}
function activeScreen() {
  const s = session;
  const top = `<section class="card tinted"><span class="eyebrow">Your focus</span><h2>${esc(s.plan.objective)}</h2><p class="small">${labels[s.phase]} · ${esc(s.title)} · Source page ${s.plan.page}</p>${s.breadth ? `<p>${s.breadth.index}/${s.plan.questions.length} inquiry questions recorded · ${new Set(s.plan.questions.map((q) => q.type)).size} distinct types. Progress is coverage, not mastery. Pause at any time; the remaining questions stay in your journey.</p>` : '<p class="notice">Legacy short inquiry: your original questions and responses are preserved. New preparations use the multi-angle inquiry sequence.</p>'}</section>`;
  if (s.paused)
    return (
      top +
      '<section class="card"><h1>Pause is allowed.</h1><p>Your place and saved draft are kept. No penalty, countdown or attention score.</p><button id="resume" class="primary">Return to the same step</button></section>'
    );
  if (s.phase === "prepare")
    return (
      top +
      source() +
      `<section class="card"><h2>Prepare, then close the passage</h2><p>Read for the objective above. If a prerequisite or diagram is missing, ask your educator before beginning. You will first explain from memory; you may request source support, which will be recorded.</p><p class="notice">${s.approval.mode === "self-study-provisional" ? "Self-study: you have already seen the questions and criteria. Later work is practice evidence, not an unseen assessment." : "Educator review is self-reported, not authenticated. Keep the preparation screen separate from the learner attempt."}</p><button id="begin-recall" class="primary">Close source and attempt recall</button></section>`
    );
  if (s.phase === "investigate" && s.breadth) return top + breadthScreen(s);
  if (s.phase === "waiting")
    return (
      top +
      `<section class="card"><h1>Inquiry complete. Return to apply it.</h1><p>${s.breadth ? `You have worked through all ${s.plan.questions.length} questions, formulated your own question and revised your explanation. The breadth remains in your evidence record.` : "Your original short inquiry is complete."} Take a break before the later application.</p><p>Return after ${new Date(s.dueAt).toLocaleString()} for an independent application. This uses the unverified device clock. No automatic notification is sent.</p><button id="return-transfer" class="primary" ${Date.now() < s.dueAt ? "disabled" : ""}>Begin delayed application</button><button id="check-due" class="secondary">Check return time</button><details><summary>Developer / workflow demonstration</summary><p>Immediate testing is permanently labelled as a demo, not delayed retention.</p><button id="demo-transfer" class="secondary">Run immediate demo</button></details></section>`
    );
  if (s.phase === "complete")
    return (
      top +
      feedbackScreen(s) +
      breadthEvidence(s) +
      `<section class="card"><h1>Evidence, not a verdict.</h1><p class="notice">${s.transferMode === "immediate-demo" ? "Immediate demo: no delayed-retention claim." : "Delayed according to device clock; timing is not independently verified."} No overall score, intelligence label or attention estimate is produced.</p><p>Next: have an educator compare the explanations with the source and criteria. If a specific gap remains, teach or practise that concept before extending the scope.</p>${Object.entries(
        s.responses,
      )
        .map(
          ([phase, r]) =>
            `<details><summary>${labels[phase]} · ${esc(r.condition)}</summary><p class="response">${esc(r.text)}</p></details>`,
        )
        .join(
          "",
        )}<details><summary>Source and review criteria</summary><p class="response">${esc(s.plan.quote)}</p><p>${esc(s.plan.rubric)}</p></details><p>Revision self-report: ${esc(s.reflection || "not supplied")} · not an automatic correctness judgment.</p><form id="review-form"><label>Educator interpretation<select id="review-value">${[
        ["needs-follow-up", "Needs follow-up"],
        ["partly-supported", "Partly supported"],
        ["supported-in-this-response", "Supported in this response only"],
      ]
        .map(
          ([v, l]) =>
            `<option value="${v}" ${s.review?.value === v ? "selected" : ""}>${l}</option>`,
        )
        .join(
          "",
        )}</select></label><label>Evidence for this interpretation<textarea id="review-note" maxlength="1000">${esc(s.review?.note || "")}</textarea></label><button class="secondary">Save educator annotation</button></form><p class="small">Identity is not authenticated. Latest annotation replaces the prior note; this is not a research audit trail.</p>${s.parked.length ? "<h3>Questions saved for later—not automatic next tasks</h3>" + s.parked.map((q) => `<p>${esc(q.text)}</p>`).join("") : ""}<div class="actions"><button id="export-summary" class="primary">Export summary</button><button id="export-full" class="secondary">Export full evidence</button></div><details><summary>Prepare another lesson</summary><p>Export this work first. Replacing it removes the current local inquiry; the fixed science demo is unaffected.</p><button id="new-inquiry" class="danger">Replace current inquiry</button></details></section>`
    );
  const prompt = {
    recall: s.plan.recall,
    investigate: s.plan.investigate,
    question:
      "Write one question that would improve your understanding of this objective. Explain what evidence, example or investigation could answer it.",
    revise:
      "Synthesize what the inquiry revealed. Compare your first explanation with the source and criteria. Write a corrected or better-supported explanation, identify which questions changed your reasoning, and state what remains uncertain.",
    transfer: s.plan.transfer,
  }[s.phase];
  return (
    top +
    `<section class="card"><h1>${labels[s.phase]}</h1>${s.phase === "investigate" ? `<p class="small">Perspective: ${perspectives[s.plan.perspective].title}. ${esc(s.plan.reason)}</p>` : ""}<p class="lead">${esc(prompt)}</p>${s.phase === "transfer" ? '<p class="notice">No hints or source in this step. Outside assistance is self-reported. If you need support, pause and ask the educator to interpret this attempt accordingly.</p>' : ""}${
      s.phase === "revise"
        ? `<details><summary>Your original recall</summary><p class="response">${esc(s.responses.recall.text)}</p></details><p><strong>Review criteria</strong></p><p>${esc(s.plan.rubric)}</p><label>What changed? (self-report)<select id="reflection"><option value="">Choose if useful</option>${[
            ["corrected-error", "I corrected an error"],
            ["added-evidence", "I added evidence or detail"],
            ["still-unsure", "I remain unsure"],
          ]
            .map(
              ([v, l]) =>
                `<option value="${v}" ${s.reflection === v ? "selected" : ""}>${l}</option>`,
            )
            .join("")}</select></label>`
        : ""
    }${s.phase === "revise" ? breadthEvidence(s) : ""}<form id="response-form"><label>Your response<textarea id="response" maxlength="1800" minlength="12" required>${esc(s.drafts[s.phase] || "")}</textarea></label><p class="small">12–1800 characters. No names or personal information. Draft saves as you write. Submitted responses stay separate and cannot be edited.</p><button class="primary">Save this response and continue</button></form><div class="actions"><button id="pause" class="secondary">Pause and keep my place</button>${["recall", "investigate", "question"].includes(s.phase) ? '<button id="source-support" class="secondary">I need source support</button>' : ""}</div>${s.sourceViews[s.phase] ? '<p class="notice">Source support was used in this step. This response will not be labelled independent.</p>' : ""}${s.phase !== "transfer" ? `<details><summary>Stuck or following another question?</summary><p>Identify the missing term or prerequisite. Use source support or ask your educator for clarification. If the question is interesting but not needed for this objective, save it for later. You decide; AI does not classify your attention or emotions.</p><label>Question for later<input id="later-question" maxlength="400"></label><button id="park" class="secondary" ${s.parked.length >= 3 ? "disabled" : ""}>Save for later and return to this objective</button><p class="small">${s.parked.length}/3 saved. These are side questions, separate from the full required inquiry set.</p></details>` : ""}</section>${s.phase === "revise" || showSource ? source() : ""}`
  );
}
function breadthScreen(s) {
  const b = s.breadth,
    q = s.plan.questions[b.index];
  const stage =
    inquirySessions.find(
      (item) =>
        b.index >= item.start &&
        b.index < Math.min(item.end, s.plan.questions.length),
    ) || inquirySessions.at(-1);
  const guidance = questionGuidance(q);
  const glossaryMarkup = guidance.glossary.length
    ? `<details><summary>Plain-language terms</summary><dl>${guidance.glossary.map(({ term, meaning }) => `<dt>${esc(term)}</dt><dd>${esc(meaning)}</dd>`).join("")}</dl></details>`
    : "";
  const stageEnd = Math.min(stage.end, s.plan.questions.length);
  const checkpoint = inquirySessions.find(
    (item) => item.id > 1 && b.index === item.start,
  );
  const checkpointMarkup = checkpoint
    ? `<p class="notice"><strong>Checkpoint reached:</strong> the previous session is complete. You can pause now and return to Session ${checkpoint.id}, or continue when ready. The remaining inquiry questions stay required.</p>`
    : "";
  return `<section class="card"><p class="eyebrow">Question ${b.index + 1} of ${s.plan.questions.length}</p><p class="small">Session ${stage.id} of 3 · ${esc(stage.title)} · questions ${stage.start + 1}–${stageEnd}. A natural stopping point is after question ${stageEnd}; pause there if useful.</p>${checkpointMarkup}<h1>${questionTypes[q.type].title}</h1><p class="lead">${esc(q.prompt)}</p><p class="notice">${q.kind === "source" ? "Source-connected question. Request the passage if needed; its use is recorded for this question." : "Investigation question: distinguish your reasoning from established facts. Identify additional evidence needed rather than inventing an answer."}</p><p class="notice"><strong>Response shape:</strong> ${esc(guidance.responseContract)} You may use short sentences, a labelled list or a table when it helps; show the same reasoning.</p>${glossaryMarkup}<form id="response-form"><label>Your response<textarea id="response" minlength="12" maxlength="1800" required>${esc(s.drafts.investigate || "")}</textarea></label><p class="small">You may explain a precise uncertainty or missing prerequisite. Recorded does not mean correct. Draft saves as you write.</p><button class="primary">Save this response and continue</button></form><div class="actions"><button id="pause" class="secondary">Pause and keep my place</button><button id="source-support" class="secondary">I need source support</button></div>${b.sourceViews.includes(b.index) ? '<p class="notice">Source support used for this question.</p>' : ""}<details><summary>Keep a related question for later</summary><p>The full inquiry set remains required. This space is only for side questions.</p><label>Question for later<input id="later-question" maxlength="400"></label><button id="park" class="secondary" ${s.parked.length >= 3 ? "disabled" : ""}>Save for later and return to this objective</button></details><details><summary>Inquiry coverage map</summary><ol>${s.plan.questions.map((q, i) => `<li>${questionTypes[q.type].title} — ${i < b.index ? "response recorded" : i === b.index ? "current" : "still to explore"}</li>`).join("")}</ol></details></section>${showSource ? source() : ""}`;
}
function breadthEvidence(s) {
  if (!s.breadth) return "";
  return `<details class="card"><summary>All ${s.breadth.answers.length} inquiry responses and criteria</summary>${s.breadth.answers
    .map((a, i) => {
      const q = s.plan.questions[i];
      return `<details><summary>${i + 1}. ${questionTypes[q.type].title} · ${esc(a.condition)}</summary><p>${esc(q.prompt)}</p><p class="small">${esc(q.kind)} · source page ${q.page} · draft ${esc(q.origin)}</p><p class="response">${esc(a.text)}</p><h3>Response contract</h3><p>${esc(questionGuidance(q).responseContract)}</p><h3>Review criteria</h3><p>${esc(q.criterion)}</p><p>Source anchor: ${esc(q.anchor)}</p></details>`;
    })
    .join("")}</details>`;
}
function capturePlan() {
  if (!setup.plan || !$("#plan-recall")) return;
  for (const k of ["reason", "recall", "investigate", "transfer", "rubric"])
    setup.plan[k] = $("#plan-" + k).value;
  setup.plan.perspective = $("#perspective").value;
  setup.mode = $("#mode").value;
  if (setup.plan.questions && $("#question-prompt")) {
    const q = setup.plan.questions[editingQuestion];
    for (const key of [
      "prompt",
      "relevance",
      "criterion",
      "responseContract",
      "anchor",
    ])
      q[key] = $("#question-" + key).value;
    q.type = $("#question-type").value;
    q.kind = $("#question-kind").value;
    q.reviewed = $("#question-reviewed").checked;
  }
}
function render() {
  document.documentElement.lang = "en";
  $("#app").innerHTML =
    header() +
    `<main>${message ? `<p role="status" class="notice">${esc(message)}</p>` : ""}${session ? activeScreen() : setupScreen()}${footer()}</main>`;
  bind();
  bindAssessment();
  if (error || !owns) {
    const panel = document.createElement("section");
    panel.className = "card";
    panel.innerHTML = `<h2>Preserve your work</h2><p>${esc(error || "Another Nua window holds the editing lock. Close it and reload. A modern browser with Web Locks is required.")}</p><button id="recover">Download recovery copy</button><button id="reload">Reload saved work</button>`;
    document
      .querySelectorAll("main button,main input,main textarea,main select")
      .forEach((el) => (el.disabled = true));
    $("main").prepend(panel);
    $("#recover").onclick = () =>
      download(
        {
          session,
          setup,
          unparsedSession: expected,
          unparsedPreparation: prepExpected,
        },
        "nua-mangal-recovery.json",
      );
    $("#reload").onclick = () => location.reload();
  }
  if (busy)
    document
      .querySelectorAll("main button,main input,main textarea,main select")
      .forEach((el) => (el.disabled = true));
}
function feedbackScreen(s) {
  if (!s.breadth) return "";
  if (feedbackQuestionIndex === null) {
    const flagged = s.breadth.answers.findIndex((a) =>
      ["need-help", "unsure"].includes(a.learnerSignal),
    );
    feedbackQuestionIndex = flagged < 0 ? 0 : flagged;
  }
  const index = Math.min(feedbackQuestionIndex, s.breadth.answers.length - 1);
  feedbackQuestionIndex = index;
  const a = s.breadth.answers[index],
    q = s.plan.questions[index];
  const d = s.feedbackDrafts?.[a.id] || {
    interpretation: "needs-clarification",
    evidence: "",
    nextStep: "",
    successCriterion: "",
  };
  const entries = s.feedback?.entries || [],
    latest = entries.at(-1);
  const followup =
    latest && s.feedback.followups.find((r) => r.feedbackId === latest.id);
  const helpCount = s.breadth.answers.filter((a) =>
    ["need-help", "unsure"].includes(a.learnerSignal),
  ).length;
  return `<section class="card"><h1>Your next learning step</h1>${latest ? `<p>Linked to question ${s.plan.questions.findIndex((q) => q.id === latest.questionId) + 1}. Educator interpretation: ${esc(interpretations[latest.interpretation])}.</p><p><strong>Evidence in your response</strong></p><blockquote>${esc(latest.evidence)}</blockquote><p class="lead">${esc(latest.nextStep)}</p><p><strong>How to check it:</strong> ${esc(latest.successCriterion)}</p>${followup ? `<h3>Your follow-up</h3><p class="response">${esc(followup.text)}</p><p>${followup.signal === "still-need-help" ? "You indicated that more help is needed." : "You indicated that you can now explain it."} Ask your educator to check this response against the stated criterion.</p>` : `<form id="followup-form"><label>Your response after trying this step<textarea id="followup-text" minlength="12" maxlength="1800" required></textarea></label><label>How does it feel now?<select id="followup-signal"><option value="still-need-help">I still need help</option><option value="can-explain">I can explain my reasoning</option></select></label><p class="small">Save this response before leaving. It will be recorded separately from the independent application.</p><button class="primary">Record my follow-up</button></form>`}` : "<p>An educator can select a response below and connect it to one specific next step.</p>"}<p class="small">${helpCount} question(s) marked unsure or needing help by the learner. These are self-reports to guide review.</p><details><summary>Educator: connect evidence to a next step</summary><label>Choose a response<select id="feedback-question">${s.breadth.answers.map((a, i) => `<option value="${i}" ${i === index ? "selected" : ""}>${i + 1}. ${questionTypes[a.type].title}${["need-help", "unsure"].includes(a.learnerSignal) ? " · learner requested review" : ""}</option>`).join("")}</select></label><p>${esc(q.prompt)}</p><p class="small">${esc(a.condition)} · ${esc(q.kind)}</p><p class="response">${esc(a.text)}</p><h3>Response contract</h3><p>${esc(questionGuidance(q).responseContract)}</p><h3>Question criteria</h3><p>${esc(q.criterion)}</p><p>Source anchor: ${esc(q.anchor)}</p><form id="feedback-form"><label>Interpretation<select id="feedback-interpretation">${Object.entries(
    interpretations,
  )
    .map(
      ([v, label]) =>
        `<option value="${v}" ${d.interpretation === v ? "selected" : ""}>${label}</option>`,
    )
    .join(
      "",
    )}</select></label><label>Exact evidence from this response<textarea id="feedback-evidence" minlength="12" maxlength="500" required>${esc(d.evidence)}</textarea></label><label>One concrete next teaching or practice step<textarea id="feedback-next" minlength="12" maxlength="1000" required>${esc(d.nextStep)}</textarea></label><label>What the learner should show afterwards<textarea id="feedback-check" minlength="12" maxlength="1000" required>${esc(d.successCriterion)}</textarea></label><p class="small">Draft saves as you type. Saving feedback preserves previous entries. Review a useful subset; every question does not need a separate annotation.</p><button class="primary">Save evidence-linked next step</button></form></details><details><summary>Feedback history (${entries.length})</summary>${entries
    .map(
      (e) =>
        `<p>Question ${s.plan.questions.findIndex((q) => q.id === e.questionId) + 1} · ${esc(interpretations[e.interpretation])}</p><blockquote>${esc(e.evidence)}</blockquote><p>${esc(e.nextStep)}</p><p>Check: ${esc(e.successCriterion)}</p>${s.feedback.followups
          .filter((r) => r.feedbackId === e.id)
          .map((r) => `<p class="response">Follow-up: ${esc(r.text)}</p>`)
          .join("")}`,
    )
    .join("")}</details></section>`;
}
function bindAssessment() {
  if (session?.breadth && session.phase === "complete") {
    const legacyForm = $("#review-form");
    const disclaimer = legacyForm.nextElementSibling;
    const details = document.createElement("details");
    details.innerHTML =
      "<summary>Legacy overall annotation (optional)</summary><p>Use the evidence-linked next step above for new feedback. This older note remains available for compatibility.</p>";
    legacyForm.before(details);
    details.append(legacyForm, disclaimer);
    const identityNote = document.createElement("p");
    identityNote.className = "small";
    identityNote.textContent =
      "Educator identity is not authenticated. Interpretations require human review and apply to this response, not general mastery.";
    $("#feedback-form").before(identityNote);
  }
  if (session?.phase === "investigate" && session.breadth && !session.paused) {
    const label = document.createElement("label");
    label.innerHTML = `How is this question going? (optional)<select id="learner-signal">${Object.entries(
      learnerSignals,
    )
      .map(
        ([v, t]) =>
          `<option value="${v}" ${v === (session.breadth.signalDraft || "not-recorded") ? "selected" : ""}>${esc(t)}</option>`,
      )
      .join("")}</select>`;
    $("#response-form button").before(label);
    $("#learner-signal").onchange = (e) => {
      session.breadth.signalDraft = e.target.value;
      persist();
    };
  }
  $("#feedback-question")?.addEventListener("change", (e) => {
    feedbackQuestionIndex = Number(e.target.value);
    render();
  });
  const draft = () => ({
    interpretation: $("#feedback-interpretation").value,
    evidence: $("#feedback-evidence").value,
    nextStep: $("#feedback-next").value,
    successCriterion: $("#feedback-check").value,
  });
  $("#feedback-form")?.addEventListener("input", () => {
    try {
      saveFeedbackDraft(
        session,
        session.breadth.answers[feedbackQuestionIndex].id,
        draft(),
      );
      persist();
    } catch (e) {
      message = e.message;
      render();
    }
  });
  $("#feedback-form")?.addEventListener("submit", (e) => {
    e.preventDefault();
    change(() =>
      addFeedback(session, {
        questionId: session.breadth.answers[feedbackQuestionIndex].id,
        ...draft(),
      }),
    );
  });
  $("#followup-form")?.addEventListener("submit", (e) => {
    e.preventDefault();
    change(() =>
      addFollowup(
        session,
        session.feedback.entries.at(-1).id,
        $("#followup-text").value,
        $("#followup-signal").value,
      ),
    );
  });
}
function bind() {
  $("#title")?.addEventListener("input", (e) => {
    setup.title = e.target.value;
    persist();
  });
  $("#use-paste")?.addEventListener("click", () =>
    change(() => {
      const text = $("#pasted").value;
      setup.title = $("#title").value.trim() || "Pasted lesson";
      setup.pages = normalizePages([{ text }]);
      setup.warnings = [
        "Pasted text is one section. Check it against the original lesson.",
      ];
      setup.page = 1;
      setup.excerpt = setup.pages[0].text.slice(0, 6000);
    }),
  );
  $("#material-file")?.addEventListener("change", async (e) => {
    if (!owns || error || busy) return;
    const file = e.target.files[0];
    if (!file) return;
    busy = true;
    message = "Extracting text locally. No upload to a cloud service.";
    render();
    try {
      const result = await readMaterial(file);
      setup = {
        ...setup,
        ...result,
        title: setup.title || file.name.slice(0, 160),
        page: 1,
        excerpt: result.pages[0].text.slice(0, 6000),
      };
      persist();
      message =
        "Extraction finished. Inspect the relevant pages before choosing the objective.";
    } catch (e) {
      message = "Could not extract this file: " + e.message;
    } finally {
      busy = false;
      render();
    }
  });
  $("#page")?.addEventListener("change", (e) =>
    change(() => {
      setup.page = Number(e.target.value);
      setup.excerpt = setup.pages[setup.page - 1].text.slice(0, 6000);
    }),
  );
  for (const id of ["objective", "excerpt"])
    $("#" + id)?.addEventListener("input", (e) => {
      setup[id] = e.target.value;
      persist();
    });
  $("#draft-plan")?.addEventListener("click", () =>
    change(() => {
      if (setup.materialReview && (setup.materialReview.input.objective !== setup.objective || setup.materialReview.input.excerpt !== setup.excerpt || setup.materialReview.input.level !== (setup.level || ''))) throw Error('Reassess the changed material, objective or learner level before preparing questions.');
      setup.plan = templatePlan(setup.objective, setup.excerpt, setup.page);
      if (setup.coverageReason?.trim().length >= 12) {
        setup.plan.selectionPolicy='objective-coverage/1';
        setup.plan.coverageReason=setup.coverageReason.trim();
        setup.plan.questions=setup.plan.questions.slice(0,setup.questionCount || 20);
      }
      setup.plan.materialSnapshot={review:setup.materialReview || null,supplements:structuredClone(setup.supplements || []),level:setup.level || '',at:Date.now()};
      validatePlan(setup.plan, setup.pages);
    }),
  );
  $("#approve-form")?.addEventListener("input", (e) => {
    if (
      e.target.id.startsWith("question-") &&
      e.target.id !== "question-reviewed"
    )
      $("#question-reviewed").checked = false;
    capturePlan();
    persist();
    if ($("#review-count"))
      $("#review-count").textContent = setup.plan.questions.filter(
        (q) => q.reviewed,
      ).length;
  });
  $("#edit-question")?.addEventListener("change", (e) =>
    change(() => {
      capturePlan();
      editingQuestion = Number(e.target.value);
    }),
  );
  $("#previous-question")?.addEventListener("click", () =>
    change(() => {
      capturePlan();
      editingQuestion--;
    }),
  );
  $("#next-question")?.addEventListener("click", () =>
    change(() => {
      capturePlan();
      editingQuestion++;
    }),
  );
  $("#add-question")?.addEventListener("click", () =>
    change(() => {
      capturePlan();
      if (setup.plan.questions.length >= MAX_QUESTIONS)
        throw Error("This set supports up to 40 questions.");
      const type =
        Object.keys(questionTypes).find(
          (type) => !setup.plan.questions.some((q) => q.type === type),
        ) || "inquiry";
      setup.plan.questions.push(makeQuestion(type, setup.plan));
      editingQuestion = setup.plan.questions.length - 1;
    }),
  );
  $("#remove-question")?.addEventListener("click", () =>
    change(() => {
      capturePlan();
      const minimum = setup.plan.selectionPolicy === "objective-coverage/1" ? 1 : 20;
      if (setup.plan.questions.length <= minimum)
        throw Error(`Keep at least ${minimum} question${minimum === 1 ? "" : "s"}.`);
      setup.plan.questions.splice(editingQuestion, 1);
      editingQuestion = Math.min(
        editingQuestion,
        setup.plan.questions.length - 1,
      );
    }),
  );
  $("#expand-legacy")?.addEventListener("click", () =>
    change(() => {
      capturePlan();
      setup.plan = expandPlan(setup.plan);
      editingQuestion = 0;
    }),
  );
  $("#approve-form")?.addEventListener("submit", (e) => {
    e.preventDefault();
    change(() => {
      capturePlan();
      if (!$("#checked-source").checked)
        throw Error("Review the source and criteria first.");
      validateQuestionSet(setup.plan, { reviewed: true });
      session = createInquiry({
        title: setup.title,
        pages: setup.pages,
        plan: setup.plan,
        mode: setup.mode,
        warnings: setup.warnings,
      });
    });
  });
  $("#edit-source")?.addEventListener("click", () =>
    change(() => {
      setup.plan = null;
      editingQuestion = 0;
    }),
  );
  $("#ai-plan")?.addEventListener("click", draftWithAI);
  $("#export-plan")?.addEventListener("click", () => {
    capturePlan();
    try {
      validatePlan(setup.plan, setup.pages);
      download(
        {
          schema: setup.plan.questions
            ? "nua-mangal-lesson/2"
            : "nua-mangal-lesson/1",
          title: setup.title,
          pages: setup.pages,
          plan: setup.plan,
        },
        "nua-mangal-lesson.json",
      );
    } catch (e) {
      message = e.message;
      render();
    }
  });
  $("#begin-recall")?.addEventListener("click", () =>
    change(() => beginRecall(session)),
  );
  $("#response")?.addEventListener("input", (e) => {
    session.drafts[session.phase] = e.target.value;
    session.updatedAt = Date.now();
    persist();
  });
  $("#response-form")?.addEventListener("submit", (e) => {
    e.preventDefault();
    change(() => submit(session, $("#response").value));
  });
  $("#source-support")?.addEventListener("click", () => {
    change(() => exposeSource(session));
    if (!error) {
      showSource = true;
      render();
    }
  });
  $("#pause")?.addEventListener("click", () =>
    change(() => {
      session.paused = true;
    }),
  );
  $("#resume")?.addEventListener("click", () =>
    change(() => {
      session.paused = false;
    }),
  );
  $("#park")?.addEventListener("click", () =>
    change(() => parkQuestion(session, $("#later-question").value)),
  );
  $("#reflection")?.addEventListener("change", (e) => {
    if (e.target.value) change(() => saveReflection(session, e.target.value));
  });
  $("#return-transfer")?.addEventListener("click", () =>
    change(() => returnForTransfer(session)),
  );
  $("#check-due")?.addEventListener("click", render);
  $("#demo-transfer")?.addEventListener("click", () => {
    if (
      confirm("Run immediate demo? This is not evidence of delayed retention.")
    )
      change(() => returnForTransfer(session, true));
  });
  $("#review-form")?.addEventListener("submit", (e) => {
    e.preventDefault();
    change(() =>
      saveReview(session, $("#review-value").value, $("#review-note").value),
    );
  });
  $("#export-summary")?.addEventListener("click", () =>
    download(inquiryReport(session), "nua-mangal-summary.json"),
  );
  $("#export-full")?.addEventListener("click", () => {
    if (
      confirm(
        "Export source material, learner responses, saved questions and educator notes? Review the file before sharing.",
      )
    )
      download(inquiryReport(session, true), "nua-mangal-evidence.json");
  });
  $("#new-inquiry")?.addEventListener("click", () => {
    if (
      !confirm(
        "Have you exported this inquiry? Replace its local copy with a new preparation?",
      )
    )
      return;
    if (!owns || error) return;
    try {
      if (localStorage.getItem(KEY) !== expected)
        throw Error("Session changed; reload before replacing.");
      localStorage.removeItem(KEY);
      expected = null;
      session = null;
      setup = {
        title: "",
        pages: [],
        warnings: [],
        objective: "",
        page: 1,
        excerpt: "",
        plan: null,
        mode: "teacher-reviewed",
      };
      persist();
      render();
    } catch (e) {
      error = e.message;
      render();
    }
  });
}
async function draftWithAI() {
  if (!owns || busy || error) return;
  capturePlan();
  if (!setup.plan.questions) {
    message = "Expand the legacy plan to 20 inquiry types first.";
    render();
    return;
  }
  const tried = setup.aiTypesTried || [];
  const batch = setup.plan.questions
    .filter((q) => !q.reviewed && !tried.includes(q.id))
    .slice(0, Math.min(4, 40 - tried.length));
  if (!batch.length || tried.length >= 40) {
    message =
      "No unreviewed, unattempted questions remain within the 40-question preparation budget. Edit and review the existing drafts.";
    render();
    return;
  }
  if (new Set(batch.map((q) => q.type)).size !== batch.length) {
    message =
      "Choose distinct question types in this batch before AI tailoring.";
    render();
    return;
  }
  setup.aiTypesTried = [...tried, ...batch.map((q) => q.id)];
  if (!persist()) return;
  busy = true;
  message = `Tailoring ${batch.length} questions on this computer. No learner responses are sent. Review is required.`;
  render();
  try {
    const r = await fetch("/api/inquiry-questions", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        objective: setup.objective,
        excerpt: setup.excerpt,
        page: setup.page,
        types: batch.map((q) => q.type),
      }),
      signal: AbortSignal.timeout(70000),
    });
    const data = await r.json();
    if (!r.ok) throw Error(data.error || "Local drafting failed.");
    if (
      !Array.isArray(data.questions) ||
      data.questions.length !== batch.length ||
      new Set(data.questions.map((q) => q.type)).size !== batch.length
    )
      throw Error("Incomplete AI batch.");
    const next = structuredClone(setup.plan);
    for (const previous of batch) {
      const replacement = data.questions.find((q) => q.type === previous.type);
      if (!replacement) throw Error("AI changed a requested question type.");
      const index = next.questions.findIndex((q) => q.id === previous.id);
      next.questions[index] = {
        ...replacement,
        id: previous.id,
        responseContract:
          replacement.responseContract ||
          previous.responseContract ||
          questionGuidance(previous).responseContract,
        glossaryTerms: replacement.glossaryTerms || previous.glossaryTerms || [],
        reviewed: false,
      };
    }
    validatePlan(next, setup.pages);
    setup.plan = next;
    editingQuestion = setup.plan.questions.findIndex(
      (q) => q.id === batch[0].id,
    );
    if (persist())
      message =
        "Question batch ready for review. Check distinct reasoning, relevance and evidence sufficiency. Existing reviewed questions were preserved.";
  } catch (e) {
    message = e.message + " Your existing editable draft is retained.";
  } finally {
    busy = false;
    render();
  }
}
// Plan import is available before any learner attempt, never mid-assessment.
function addPlanImport() {
  if (session || setup.pages.length || error || !owns || busy) return;
  const box = document.createElement("section");
  box.className = "card";
  box.innerHTML =
    '<h2>Reuse a prepared lesson</h2><p>Import a Nua lesson JSON, then review it again on this device. This does not restore learner attempts.</p><input id="lesson-file" type="file" accept=".json" aria-label="Import prepared lesson">';
  $("main").append(box);
  $("#lesson-file").onchange = async (e) => {
    if (!owns || busy || error) return;
    const file = e.target.files[0];
    if (!file) return;
    try {
      if (file.size > 1200000) throw Error("Lesson file too large.");
      const d = JSON.parse(await file.text());
      if (
        !["nua-mangal-lesson/1", "nua-mangal-lesson/2"].includes(d.schema) ||
        typeof d.title !== "string" ||
        !d.title.trim() ||
        d.title.length > 160
      )
        throw Error("Not a supported lesson plan.");
      const pages = normalizePages(d.pages);
      validatePlan(d.plan, pages);
      if (d.schema === "nua-mangal-lesson/2") validateQuestionSet(d.plan);
      if (d.plan.questions)
        d.plan.questions.forEach((q) => {
          q.reviewed = false;
        });
      editingQuestion = 0;
      setup = {
        title: d.title,
        pages,
        warnings: [
          "Imported plan: review source accuracy, relevance and language again.",
        ],
        objective: d.plan.objective,
        page: d.plan.page,
        excerpt: d.plan.quote,
        plan: d.plan,
        mode: "teacher-reviewed",
      };
      persist();
      render();
    } catch (e) {
      message = e.message;
      render();
    }
  };
}
const renderBase = render;
render = function () {
  renderBase();
  addPlanImport();
  materialReviewPanel();
};
function materialReviewPanel() {
  if (session || !setup.pages.length || setup.plan || !owns || error) return;
  const panel = document.createElement('section');
  panel.className = 'card';
  const review = setup.materialReview;
  const current = review && review.input.objective === setup.objective && review.input.excerpt === setup.excerpt && review.input.level === (setup.level || '');
  panel.innerHTML = `<h2>Does this material support your goal?</h2><p>Set the objective above, then review the selected passage. Other pages and factual accuracy need separate checking.</p><label>Learner level and prior knowledge<input id="review-level" maxlength="200" value="${esc(setup.level || '')}"></label><button id="review-material" ${busy || window.NuaNative ? 'disabled' : ''}>Assess passage with local AI</button><p>Review each requirement and its evidence. Missing information may call for a supplement or a narrower goal. Local AI review requires the desktop server and Ollama.</p>${review ? `<p>${current ? 'Review matches the current passage and goal.' : 'Outdated review: the passage, goal or learner level changed. Reassess before using these findings.'}</p>${review.findings.map(f => `<article><h3>${esc(f.requirement)} — ${esc(f.status)}</h3><blockquote>${esc(f.quote || 'No supporting quotation supplied')}</blockquote><p>${esc(f.reason)}</p><p>Suggested action: ${esc(f.remedy)}</p><a href="${esc(searchLink(f.search))}" target="_blank" rel="noopener noreferrer">Search for resources to address this requirement</a><p class="small">External search; results have not been verified or endorsed by Nua. Opening sends the search query to Google.</p></article>`).join('')}` : ''}<details><summary>Add an approved resource to your learning pack</summary><p>Your original pages remain intact. Check the resource before adding text you have permission to use. The supplement becomes a separate selectable section; choose or combine checked text for the next review.</p><label>Resource title<input id="supp-title" maxlength="160"></label><label>Source URL<input id="supp-url" type="url"></label><label>Checked supplementary text<textarea id="supp-text" maxlength="6000"></textarea></label><button id="approve-supplement">Approve and add supplement</button></details>`;
  $('#draft-plan').before(panel);
  const coverage=document.createElement('div');
  coverage.innerHTML=`<h3>Plan the inquiry breadth</h3><p>Twenty is a starting suggestion. Choose a smaller initial set when justified, then review and change its reasoning types in preparation. Counts do not establish depth or coverage.</p><label>Initial questions (1–20)<input id="inquiry-count" type="number" min="1" max="20" value="${setup.questionCount || 20}"></label><label>Why will this selection cover the learning objective?<textarea id="coverage-reason" maxlength="1000">${esc(setup.coverageReason || '')}</textarea></label><p>Explain the required perspectives and any exclusions. Without a rationale, the existing 20-type default is used. You can add questions during review, up to 40.</p>`;
  panel.append(coverage);
  $('#inquiry-count').onchange=e=>{setup.questionCount=Math.max(1,Math.min(20,Number(e.target.value)||20));persist();};
  $('#coverage-reason').oninput=e=>{setup.coverageReason=e.target.value;persist();};
  $('#review-level').oninput = e => { setup.level=e.target.value; persist(); };
  $('#review-material').onclick = async () => {
    if (busy) return;
    try {
      const input = reviewInput({objective:setup.objective,level:setup.level || '',excerpt:setup.excerpt});
      busy=true; render();
      const response = await fetch('/api/material-review',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(input),signal:AbortSignal.timeout(70000)});
      const data = await response.json();
      if (!response.ok) throw Error(data.error || 'Review failed.');
      setup.materialReview=data.review; persist(); message='Review ready. Check its judgments against your source.';
    } catch(e) { message=e.message; } finally { busy=false; render(); }
  };
  $('#approve-supplement').onclick = () => change(() => {
    const addition=approveSupplement(setup.pages,$('#supp-title').value,$('#supp-text').value,$('#supp-url').value);
    setup.supplements=[...(setup.supplements || []),addition];
    setup.pages.push({page:addition.page,text:addition.text});
    setup.page=addition.page; setup.excerpt=addition.text;
  });
  if (busy) panel.querySelectorAll('input,textarea,button').forEach(el=>el.disabled=true);
}
$("#app").textContent = "Opening your focused learning workspace…";
withSessionEditor(navigator.locks, window.NuaNative, (granted) => {
  owns = granted;
  try {
    expected = localStorage.getItem(KEY);
    prepExpected = localStorage.getItem(PREP);
    if (expected) session = validateInquiry(JSON.parse(expected));
    else if (prepExpected) {
      setup = validatePreparation(JSON.parse(prepExpected));
    } else {
      // Read legacy work without deleting or overwriting its original storage.
      const oldSession = localStorage.getItem("nua-mangal-session-v1");
      const oldPreparation = localStorage.getItem("nua-mangal-preparation-v1");
      if (oldSession) session = validateInquiry(JSON.parse(oldSession));
      else if (oldPreparation)
        setup = validatePreparation(JSON.parse(oldPreparation));
    }
  } catch (e) {
    error = e.message;
  }
  editingQuestion = setup.questionIndex || 0;
  render();
}).catch((e) => {
  error = e.message;
  render();
});
window.addEventListener("storage", (e) => {
  if (
    e.key === null ||
    [KEY, PREP, "nua-mangal-session-v1", "nua-mangal-preparation-v1"].includes(
      e.key,
    )
  ) {
    error = "Work changed in another window. Preserve a copy and reload.";
    render();
  }
});
document.addEventListener("visibilitychange", () => {
  if (!document.hidden && session?.phase === "waiting") render();
});
if (!window.NuaNative && "serviceWorker" in navigator)
  navigator.serviceWorker.register("./sw.js").catch(() => {});
