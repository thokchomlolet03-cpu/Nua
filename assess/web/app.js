import { tasks, lesson, hints, VERSION } from "./content.js";
import {
  createSession,
  validateSession,
  submitResponse,
  startGuided,
  startTransfer,
  report,
  score,
  nextStep,
  event,
  hintRequest,
  hintPrompt,
  HINT_POLICY_VERSION,
  recordHintFeedback,
  resolveGuidance,
  guidanceCounts,
} from "./core.js";
import {
  SESSION_KEY as KEY,
  saveSession,
  recoverInterruptedHint,
  deleteSession,
  withSessionEditor,
} from "./storage.js";
let ownsEditor = false;
let lastSaved = null;
let session = null,
  view = "home",
  busy = false,
  aiStatus = { available: false, label: "Checking local AI…" },
  storageError = "",
  statusTimer;
const $ = (s) => document.querySelector(s);
const esc = (s) =>
  String(s ?? "").replace(
    /[&<>"']/g,
    (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        c
      ],
  );
const t = (o) => (typeof o === "string" ? o : o[session?.language || "en"]);
const date = (n) => new Date(n).toLocaleString();
function initialize(canEdit) {
  ownsEditor = canEdit;
  try {
    lastSaved = localStorage.getItem(KEY);
    if (lastSaved) {
      session = validateSession(JSON.parse(lastSaved));
      if (canEdit && recoverInterruptedHint(session))
        lastSaved = saveSession(localStorage, session, lastSaved);
    }
  } catch (e) {
    storageError = e.message;
  }
  if (!canEdit)
    storageError =
      "Editing is locked. Close the other Nua window and reload. If none is open, use a browser with Web Locks support.";
  render();
  checkAI();
}
function persist() {
  try {
    if (!ownsEditor) throw Error("Editing is locked by another window.");
    validateSession(session);
    lastSaved = saveSession(localStorage, session, lastSaved);
    storageError = "";
    return true;
  } catch (e) {
    storageError = e.message.includes("another window")
      ? e.message
      : "Storage is unavailable or full. Progress is not saved. Download a recovery copy before closing.";
    toast(storageError);
    return false;
  }
}
function toast(message) {
  $("#toast").textContent = message;
  $("#toast").style.display = "block";
  clearTimeout(statusTimer);
  statusTimer = setTimeout(() => ($("#toast").style.display = "none"), 6000);
}
const nativePending = new Map();
window.nuaReply = (id, response) => {
  const p = nativePending.get(id);
  if (p) {
    clearTimeout(p.timer);
    nativePending.delete(id);
    response.error ? p.reject(Error(response.error)) : p.resolve(response);
  }
};
function native(method, payload = {}) {
  return new Promise((resolve, reject) => {
    const id = crypto.randomUUID();
    const timer = setTimeout(
      () => {
        nativePending.delete(id);
        reject(
          Error("The device did not finish in time. Use a built-in hint."),
        );
      },
      method === "import" || method === "export" ? 600000 : 90000,
    );
    nativePending.set(id, { resolve, reject, timer });
    window.NuaNative.request(id, method, JSON.stringify(payload));
  });
}
async function checkAI() {
  try {
    aiStatus = window.NuaNative
      ? await native("status")
      : await fetch("/api/status", { signal: AbortSignal.timeout(5000) }).then(
          (r) => r.json(),
        );
  } catch {
    aiStatus = {
      available: false,
      label: "AI unavailable · built-in hints ready",
    };
  }
  if (view === "settings" || view === "home") render();
  else if ($("#ai-status")) $("#ai-status").textContent = aiStatus.label;
}
const art = `<div class="art" aria-hidden="true"><svg viewBox="0 0 420 320"><defs><pattern id="dots" width="24" height="24" patternUnits="userSpaceOnUse"><circle cx="3" cy="3" r="1" fill="#b5c4a1"/></pattern></defs><rect width="420" height="320" fill="url(#dots)"/><ellipse cx="215" cy="261" rx="139" ry="15" fill="#bdcba8"/><path d="M110 197h88l-13 60h-62z" fill="#bd744d"/><path d="M154 201v-87" stroke="#35623e" stroke-width="5"/><path d="M154 165c-44-1-58-31-46-45 32-2 49 18 46 45M154 138c0-34 23-49 47-39 0 27-19 44-47 39" fill="#63874a"/><path d="M257 211h68l-10 46h-48z" fill="#d39c71"/><path d="M291 214v-52" stroke="#35623e" stroke-width="4"/><path d="M290 189c-32-2-39-18-36-28 20-5 37 10 36 28M290 176c2-26 19-36 34-26-2 20-16 27-34 26" fill="#78935e"/><circle cx="311" cy="68" r="23" fill="#f4d573"/><path d="M61 262h298" stroke="#4b684a" stroke-width="2"/><text x="151" y="286" text-anchor="middle" font-family="sans-serif" font-size="12" fill="#3c5a3b">A</text><text x="290" y="286" text-anchor="middle" font-family="sans-serif" font-size="12" fill="#3c5a3b">B</text></svg><div class="art-label">Observe. Question. Investigate.</div></div>`;
function header() {
  return `<header><div class="brand">nua<span>assess / field notes</span></div><nav aria-label="Main navigation"><button data-view="home">Overview</button><button data-view="report">Teacher report</button><button data-view="settings">Settings</button></nav></header>`;
}
function footer() {
  return `<footer><span>Nua Assess · MVP 0.2.1 · Fair tests</span><span>Stored on this device · No cloud AI · Educational validation pending</span></footer>`;
}
function home() {
  return `<section class="hero"><div><span class="pill">SCIENCE / LOWER SECONDARY</span><h1>Learning,<br>made visible.</h1><p class="lead">A good answer is only the beginning. Investigate a claim, explain your reasoning, and discover what you can do on your own.</p><div class="actions"><button class="primary" id="begin">${session ? "Continue investigation →" : "Start an investigation →"}</button><span class="small">About 15 minutes + a next-day check</span></div></div>${art}</section><div class="three"><div class="tile"><div class="num">01</div><div><h3>Try independently</h3><p>Your first explanation stays intact. No hints, no grades revealed.</p></div></div><div class="tile"><div class="num">02</div><div><h3>Investigate with support</h3><p>Use purposeful hints, compare evidence and reconsider a claim.</p></div></div><div class="tile"><div class="num">03</div><div><h3>Apply it somewhere new</h3><p>Return after 24 hours to test the same idea in a different setting.</p></div></div></div><div class="card tinted" style="margin-top:25px"><div class="row"><div><h3>A small experiment in scientific reasoning</h3><p class="small">Three scenarios · Nine structured items · Written explanations · English / Hindi</p></div><span class="pill" id="ai-status">${esc(aiStatus.label)}</span></div><p class="small">Development content, including Hindi text, awaits educator review. Use fictional or consented responses only. This app does not establish a student's general intelligence or diagnose learning difficulties.</p>${session ? `<p class="small">Session ${esc(session.id.slice(0, 8))} · ${esc(session.stage)} · saved ${date(session.updatedAt)}</p>` : ""}</div>`;
}
function stepper() {
  return `<div class="steps">${[
    ["baseline", "1 · Independent"],
    ["lesson", "2 · Learn"],
    ["guided", "3 · Investigate"],
    ["waiting", "4 · Return later"],
    ["transfer", "5 · Transfer"],
    ["report", "6 · Evidence"],
  ]
    .map(
      ([s, label]) =>
        `<span class="step ${session.stage === s ? "active" : ""}">${label}</span>`,
    )
    .join("")}</div>`;
}
function taskScreen() {
  const stage = session.stage,
    task = tasks[stage],
    draft = session.drafts[stage] || { choices: {} };
  return `${stepper()}<div class="grid"><section class="card"><div class="row"><span class="eyebrow">${stage === "guided" ? "Guided investigation" : stage === "transfer" ? "Transfer task · no hints" : "Independent first attempt"}</span><label class="small">Language <select id="language"><option value="en" ${session.language === "en" ? "selected" : ""}>English</option><option value="hi" ${session.language === "hi" ? "selected" : ""}>हिन्दी</option></select></label></div><h2>${esc(t(task.title))}</h2><p>${esc(t(task.context))}</p><div class="table-wrap"><table><thead><tr>${task.columns.map((c) => `<th>${esc(t(c))}</th>`).join("")}</tr></thead><tbody>${task.rows.map((r) => `<tr>${r.map((c) => `<td>${esc(c)}</td>`).join("")}</tr>`).join("")}</tbody></table></div><blockquote>${esc(t(task.claim))}</blockquote><form id="response-form">${task.questions.map((q, i) => `<fieldset><legend>${i + 1}. ${esc(t(q.prompt))}</legend>${q.options.map((o, j) => `<label class="option"><input type="radio" name="${q.id}" value="${j}" ${draft.choices[q.id] === j ? "checked" : ""} required><span>${esc(t(o))}</span></label>`).join("")}</fieldset>`).join("")}<label for="explanation"><strong>${session.language === "hi" ? "अपने निर्णय का कारण evidence के साथ बताएँ।" : "Explain your decision using the evidence."}</strong></label><textarea id="explanation" name="explanation" maxlength="1200" minlength="12" required placeholder="What did you notice? Why does it matter?">${esc(draft.explanation || "")}</textarea><div class="small">12–1200 characters. Do not include names or personal information.</div><fieldset><legend>How confident are you?</legend>${["Still unsure", "Somewhat sure", "Very sure"].map((v, i) => `<label class="option"><input type="radio" name="confidence" value="${i + 1}" ${draft.confidence === i + 1 ? "checked" : ""} required><span>${v}</span></label>`).join("")}</fieldset><p id="form-error" class="error" role="alert"></p><button class="primary" type="submit" ${busy ? "disabled" : ""}>Save & lock this attempt →</button><p class="small">Your submitted response cannot be edited. Drafts save automatically.</p></form></section><aside class="aside"><div class="card tinted"><span class="eyebrow">Assessment condition</span><h3 style="margin-top:14px">${stage === "guided" ? "Support is available" : "Your own reasoning"}</h3><p class="small">${stage === "guided" ? "Built-in prompts are always available. Optional local AI can ask a follow-up question. AI output is experimental and may be wrong." : "Complete this activity without a chatbot, notes or outside help. This is a self-reported condition; the app cannot verify outside assistance."}</p>${stage === "transfer" && session.transferMode === "immediate-demo" ? '<p class="notice">Immediate demo. This is not evidence of delayed retention.</p>' : ""}</div>${stage === "guided" ? hintPanel() : ""}</aside></div>`;
}
function hintPanel() {
  return `<section class="card"><h3>A nudge, not a solution</h3><button id="hint" class="secondary" ${session.hintCount >= 3 ? "disabled" : ""}>${session.hintCount >= 3 ? "All prompts shown" : "Show a built-in prompt"}</button>${hints
    .slice(0, session.hintCount)
    .map(
      (h) =>
        `<div class="hint"><span class="badge">Built-in · not AI</span><p>${esc(t(h))}</p></div>`,
    )
    .join(
      "",
    )}<hr><span class="pill" id="ai-status">${esc(aiStatus.label)}</span><label for="ai-question"><p>What are you stuck on?</p></label><textarea id="ai-question" maxlength="400" placeholder="Ask about comparing evidence…"></textarea><button id="ask-ai" class="secondary" ${busy || session.metrics.aiCalls >= 3 ? "disabled" : ""}>${busy ? "Generating locally…" : "Ask local AI"}</button><p class="small">Up to 3 calls per session. No paid API calls.</p><div id="ai-response" aria-live="polite">${session.events
    .filter((e) => e.type === "ai_hint")
    .map(
      (e) =>
        `<div class="hint"><span class="badge">${e.guidanceMode === "ai-selected-authored" ? "AI-selected authored guidance" : e.guidanceMode === "authored-fallback" ? "Authored fallback · AI selection unavailable" : "Earlier experimental AI output"}</span><p>${esc(e.text)}</p><small>${esc(e.model)} · ${Math.round(e.latencyMs / 1000)} s · educator review pending</small><p class="small">Did this help you reason?</p><button class="secondary" data-hint-seq="${e.seq}" data-helpful="true">Helpful</button> <button class="secondary" data-hint-seq="${e.seq}" data-helpful="false">Not helpful</button>${session.events.some((r) => r.type === "hint_feedback" && r.hintSeq === e.seq) ? '<p class="small">Your feedback is saved on this device.</p>' : ""}</div>`,
    )
    .join("")}</div></section>`;
}
function learning() {
  return `${stepper()}<div class="hero"><div><span class="eyebrow">A moment to learn</span><h1>What makes<br>a test fair?</h1><p class="lead">${esc(t(lesson))}</p><p class="notice">Your first attempt is locked. We will keep it separate from the work you do with support.</p><button id="guided" class="primary">Try a guided investigation →</button></div>${art}</div>`;
}
function waiting() {
  const due = session.transferDueAt,
    ready = Date.now() >= due;
  return `${stepper()}<section class="card"><span class="eyebrow">Investigation saved</span><h1>Let it settle.</h1><p class="lead">The next activity uses a different scenario. Return after a day to see what you can apply without help.</p><p>Transfer task available: <strong>${date(due)}</strong></p><p class="notice">Keep this browser's data or the Android app installed. There is no account or automatic reminder. Clearing app data removes your session.</p><div class="actions"><button id="transfer" class="primary" ${ready ? "" : "disabled"}>Start next-day task →</button><button data-view="report" class="secondary">View evidence so far</button></div><hr><details><summary>Testing the MVP today?</summary><p class="small">An immediate demo lets you test the full workflow now. The report will explicitly label it as immediate, not delayed retention. This choice cannot be changed for this session.</p><button id="demo" class="secondary">Run immediate demo</button></details></section>`;
}
function reportScreen() {
  if (!session)
    return '<section class="card"><h2>No evidence yet.</h2><p>Start an investigation to create a local session.</p><button data-view="home" class="primary">Go to overview</button></section>';
  if (session.stage !== "report")
    return `<section class="card"><h2>Your progress is saved.</h2><p>${Object.keys(session.responses).length} of 3 attempts completed.</p><p>Answer feedback and teacher review become available after the transfer task, so earlier feedback does not influence that attempt.</p><button data-view="activity" class="primary">Continue investigation</button></section>`;
  return `<div class="row"><div><span class="eyebrow">Teacher workspace · local only</span><h1>Evidence, in context.</h1></div><span class="pill">Session ${esc(session.id.slice(0, 8))}</span></div><p class="notice">Formative prototype. Structured choices are checked against author-defined answer keys. Written reasoning needs educator review. Teacher mode is not access-controlled: use supervised or test devices. Results are not validated grades.</p><div class="three" style="margin:24px 0">${[
    "baseline",
    "guided",
    "transfer",
  ]
    .map((stage) => {
      const r = session.responses[stage];
      return `<div class="tile"><div><span class="eyebrow">${stage}</span><div class="stat">${r ? `${score(stage, r).filter((x) => x.correct).length} / 3` : "—"}</div><p>Structured items matched</p><span class="badge ${stage === "guided" ? "warn" : ""}">${stage === "guided" ? "Assisted" : stage === "transfer" ? (session.transferMode === "immediate-demo" ? "Immediate demo" : r ? "Delayed · unassisted" : "Not attempted") : "Unassisted"}</span></div></div>`;
    })
    .join(
      "",
    )}</div><div class="grid"><section><div class="card"><h3>Suggested instructional next step</h3><p>${esc(nextStep(session))}</p><p class="small">Rule-based suggestion. Confirm against the student's explanation before using it.</p></div>${Object.entries(
    session.responses,
  )
    .map(
      ([stage, r]) =>
        `<section class="card"><div class="row"><h2>${stage[0].toUpperCase() + stage.slice(1)} evidence</h2><span class="badge warn">${session.reviews[stage] ? "Teacher annotation saved" : "Explanation needs review"}</span></div><p class="small">${date(r.submittedAt)} · Built-in prompts: ${r.hintCount} · AI hints: ${stage === "guided" ? session.events.filter((e) => e.type === "ai_hint").length : 0} · Confidence: ${r.confidence}/3</p>${tasks[stage].questions.map((q, i) => `<div class="report-item"><strong>${esc(t(q.prompt))}</strong><p>${esc(t(q.options[r.choices[q.id]]))}</p><span class="badge ${score(stage, r)[i].correct ? "" : "warn"}">${score(stage, r)[i].correct ? "Matches structured key" : "Different from structured key"}</span></div>`).join("")}<h3 style="margin-top:24px">Student explanation</h3><div class="response">${esc(r.explanation)}</div><details class="review"><summary>Review explanation against the rubric</summary><form class="review-form" data-stage="${stage}">${[
          ["evidence", "Uses relevant evidence"],
          ["investigation", "Proposes a controlled, repeatable test"],
          ["reasoning", "Limits conclusion to the evidence"],
        ]
          .map(
            ([id, label]) =>
              `<label>${label}<select name="${id}">${[
                ["unreviewed", "Not reviewed"],
                ["not-yet", "Not yet demonstrated"],
                ["partial", "Partly demonstrated"],
                ["demonstrated", "Demonstrated in this response"],
              ]
                .map(
                  ([v, l]) =>
                    `<option value="${v}" ${session.reviews[stage]?.[id] === v ? "selected" : ""}>${l}</option>`,
                )
                .join("")}</select></label>`,
          )
          .join(
            "",
          )}<label>Review note<textarea name="note" maxlength="1000" placeholder="Cite a specific part of the response. Avoid personal information.">${esc(session.reviews[stage]?.note || "")}</textarea></label><button class="secondary">Save teacher annotation</button></form></details></section>`,
    )
    .join(
      "",
    )}</section><aside class="aside"><div class="card"><h3>Export for review</h3><p class="small">Summary exports exclude written answers, teacher notes and event logs. Full evidence includes free text; review it before sharing.</p><button id="export-summary" class="primary">Export summary JSON</button><div class="actions"><button id="export-evidence" class="secondary">Export full evidence</button></div></div><div class="card tinted"><h3>Runtime & cost</h3><p>AI requests: ${session.metrics.aiCalls}<br>AI failures: ${session.metrics.aiFailures}<br>Recorded AI time: ${Math.round(session.metrics.aiLatencyMs / 1000)} s<br>Cloud/API charge: $0</p><p class="small">Electricity, device, content and teacher costs are not measured. On Android, inference needs a compatible imported model.</p><p class="small">Content ${VERSION}<br>${session.transferMode === "immediate-demo" ? "Immediate transfer; no retention claim." : session.responses.transfer ? "Delayed transfer completed. This alone does not establish a learning effect." : "Delayed transfer not yet completed."}</p></div></aside></div>`;
}
function settings() {
  return `<span class="eyebrow">Local configuration</span><h1>Your device.<br>Your evidence.</h1><div class="grid"><div class="card"><h2>Local AI</h2><span class="pill">${esc(aiStatus.label)}</span><p>${window.NuaNative ? "Import a compatible .litertlm model using the Android file picker. It stays in app-private storage. Model licenses and device requirements vary." : "The desktop preview uses Ollama on this Mac. The server connects only to 127.0.0.1:11434; there is no cloud fallback."}</p><button id="refresh-ai" class="secondary">Check availability</button>${window.NuaNative ? '<button id="import-model" class="primary">Import local model</button>' : ""}<p class="small">Without a working model, built-in prompts remain available and are explicitly labeled as not AI. AI is disabled for independent and transfer tasks.</p></div><div class="card"><h2>Session storage</h2><p>One anonymous session on this device. No names, sign-in, advertising or analytics.</p><p class="small">Export evidence before deleting. This prototype is for supervised feasibility testing; it has no teacher authentication or multi-student roster.</p><button id="clear-session" class="danger">Delete session & start fresh</button></div></div>`;
}
function draftFromForm() {
  const f = $("#response-form");
  if (!f) return;
  const data = new FormData(f);
  session.drafts[session.stage] = {
    choices: Object.fromEntries(
      tasks[session.stage].questions
        .filter((q) => data.has(q.id))
        .map((q) => [q.id, Number(data.get(q.id))]),
    ),
    explanation: String(data.get("explanation") || ""),
    confidence: data.has("confidence")
      ? Number(data.get("confidence"))
      : undefined,
  };
  session.updatedAt = Date.now();
  if (!persist()) render();
}
function confirmAction(title, description, action) {
  const d = document.createElement("dialog");
  d.innerHTML = `<h2>${esc(title)}</h2><p>${esc(description)}</p><div class="actions"><button class="primary" id="dialog-confirm">Confirm</button><button class="secondary" id="dialog-cancel">Cancel</button></div>`;
  document.body.append(d);
  d.showModal();
  d.querySelector("#dialog-cancel").onclick = () => {
    d.close();
    d.remove();
  };
  d.querySelector("#dialog-confirm").onclick = () => {
    d.close();
    d.remove();
    if (!ownsEditor || (storageError && session)) {
      toast("Resolve the saved-data problem before making changes.");
      return;
    }
    action();
  };
  d.addEventListener("cancel", () => d.remove());
}
async function download(full) {
  const data = report(session, full),
    name = `nua-${session.id.slice(0, 8)}-${full ? "evidence" : "summary"}.json`;
  await downloadData(data, name);
}
async function downloadData(data, name) {
  try {
    if (window.NuaNative) {
      await native("export", { name, text: JSON.stringify(data, null, 2) });
      toast("Export saved.");
    } else {
      const url = URL.createObjectURL(
        new Blob([JSON.stringify(data, null, 2)], { type: "application/json" }),
      );
      const a = document.createElement("a");
      a.href = url;
      a.download = name;
      a.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
      toast("Export downloaded.");
    }
  } catch (e) {
    toast(e.message);
  }
}
async function askAI() {
  if (busy || storageError || !ownsEditor) return;
  const question = $("#ai-question").value;
  let payload;
  try {
    payload = hintRequest(session, question);
  } catch (e) {
    toast(e.message);
    return;
  }
  const currentId = session.id;
  busy = true;
  session.metrics.aiCalls++;
  event(session, "ai_requested", {
    question,
    language: session.language,
    policyVersion: HINT_POLICY_VERSION,
  });
  if (!persist()) {
    busy = false;
    render();
    return;
  }
  render();
  const started = Date.now();
  try {
    const data = window.NuaNative
      ? await native("hint", {
          ...payload,
          prompt: hintPrompt(payload.question, payload.language),
        })
      : await fetch("/api/hint", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(payload),
          signal: AbortSignal.timeout(70000),
        }).then(async (r) => {
          const d = await r.json();
          if (!r.ok) throw Error(d.error || "Local inference failed.");
          return d;
        });
    if (session?.id !== currentId) return;
    const latencyMs = Date.now() - started;
    session.metrics.aiLatencyMs += latencyMs;
    event(session, "ai_hint", {
      ...resolveGuidance(data.text, payload.language),
      model: data.model,
      source: data.source,
      language: payload.language,
      latencyMs,
    });
  } catch (e) {
    if (session?.id === currentId) {
      session.metrics.aiFailures++;
      const latencyMs = Date.now() - started;
      session.metrics.aiLatencyMs += latencyMs;
      event(session, "ai_failed", { reason: e.message, latencyMs });
      toast(e.message + " Built-in prompts are still available.");
    }
  } finally {
    busy = false;
    if (session?.id === currentId) {
      persist();
      render();
    }
  }
}
function bind() {
  document.querySelectorAll("[data-hint-seq]").forEach(
    (button) =>
      (button.onclick = () => {
        draftFromForm();
        if (storageError) return;
        recordHintFeedback(
          session,
          Number(button.dataset.hintSeq),
          button.dataset.helpful === "true",
        );
        persist();
        render();
      }),
  );
  $("#recovery-download")?.addEventListener("click", () =>
    downloadData(session || { unparsed: lastSaved }, "nua-recovery.json"),
  );
  $("#reload-session")?.addEventListener("click", () => location.reload());
  document.querySelectorAll("[data-view]").forEach(
    (b) =>
      (b.onclick = () => {
        if (busy) {
          toast("Wait for the local hint to finish.");
          return;
        }
        draftFromForm();
        if (storageError && session) {
          render();
          return;
        }
        view = b.dataset.view;
        render();
        window.scrollTo(0, 0);
      }),
  );
  $("#begin")?.addEventListener("click", () => {
    if (storageError && !session) {
      toast("Resolve the saved-data problem in Settings first.");
      return;
    }
    if (!session) {
      session = createSession();
      event(session, "session_started");
      persist();
    }
    view = "activity";
    render();
  });
  $("#language")?.addEventListener("change", (e) => {
    draftFromForm();
    if (storageError) return;
    session.language = e.target.value;
    event(session, "language_changed", { language: session.language });
    persist();
    render();
  });
  $("#response-form")?.addEventListener("input", draftFromForm);
  $("#response-form")?.addEventListener("submit", (e) => {
    e.preventDefault();
    if (busy) return;
    draftFromForm();
    if (storageError) {
      render();
      return;
    }
    try {
      submitResponse(session, session.drafts[session.stage]);
      persist();
      render();
      window.scrollTo(0, 0);
    } catch (err) {
      $("#form-error").textContent = err.message;
    }
  });
  $("#guided")?.addEventListener("click", () => {
    startGuided(session);
    persist();
    render();
  });
  $("#transfer")?.addEventListener("click", () => {
    try {
      startTransfer(session);
      persist();
      render();
    } catch (e) {
      toast(e.message);
    }
  });
  $("#demo")?.addEventListener("click", () =>
    confirmAction(
      "Run immediate transfer?",
      "This permanently labels this session as an immediate demo, not a delayed retention check.",
      () => {
        startTransfer(session, true);
        persist();
        render();
      },
    ),
  );
  $("#hint")?.addEventListener("click", () => {
    draftFromForm();
    if (storageError) return;
    if (session.stage !== "guided" || session.hintCount >= 3) return;
    session.hintCount++;
    event(session, "builtin_hint", { level: session.hintCount });
    persist();
    render();
  });
  $("#ask-ai")?.addEventListener("click", () => {
    draftFromForm();
    askAI();
  });
  $("#export-summary")?.addEventListener("click", () => download(false));
  $("#export-evidence")?.addEventListener("click", () =>
    confirmAction(
      "Export full evidence?",
      "This file includes written student answers, AI questions and responses, and teacher notes. Review it before sharing. No upload happens automatically.",
      () => download(true),
    ),
  );
  document.querySelectorAll(".review-form").forEach(
    (f) =>
      (f.onsubmit = (e) => {
        e.preventDefault();
        const data = Object.fromEntries(new FormData(f));
        session.reviews[f.dataset.stage] = {
          ...data,
          status: "teacher-annotated",
          reviewedAt: Date.now(),
        };
        event(session, "teacher_annotation", { stage: f.dataset.stage });
        const saved = persist();
        render();
        if (saved) toast("Teacher annotation saved.");
      }),
  );
  $("#refresh-ai")?.addEventListener("click", checkAI);
  $("#import-model")?.addEventListener("click", async () => {
    try {
      toast("Choose a compatible model file. Loading may take a minute.");
      await native("import");
      await checkAI();
    } catch (e) {
      toast(e.message);
    }
  });
  $("#clear-session")?.addEventListener("click", () =>
    confirmAction(
      "Delete local session?",
      "All answers, hints and teacher notes for this session will be removed. Export first if you need a copy.",
      () => {
        try {
          deleteSession(localStorage, lastSaved);
          lastSaved = null;
          session = null;
          storageError = "";
          view = "home";
          render();
        } catch (e) {
          storageError = e.message;
          render();
          toast("Could not delete the session: " + e.message);
        }
      },
    ),
  );
}
function render() {
  document.documentElement.lang = session?.language || "en";
  let body =
    view === "home"
      ? home()
      : view === "settings"
        ? settings()
        : view === "report"
          ? reportScreen()
          : !session
            ? home()
            : tasks[session.stage]
              ? taskScreen()
              : session.stage === "lesson"
                ? learning()
                : session.stage === "waiting"
                  ? waiting()
                  : reportScreen();
  $("#app").innerHTML =
    header() +
    `<main>${storageError ? `<p class="notice">${esc(storageError)}</p>` : ""}${body}${footer()}</main>`;
  bind();
  if ($("#export-summary")) {
    const counts = guidanceCounts(session);
    const note = document.createElement("p");
    note.className = "notice";
    note.textContent = `Guidance returned: ${counts.aiSelectedAuthored} AI-selected authored prompts; ${counts.authoredFallback} authored fallbacks; ${counts.historicalExperimental} historical experimental hints. Tasks are not equated: score differences are not measured learning gains. Delayed timing uses the unverified device clock.`;
    $("main").prepend(note);
  }
  const askButton = $("#ask-ai");
  if (askButton && !busy)
    askButton.textContent = "Find relevant guidance with AI";
  const explanation = $("#explanation");
  if (explanation) {
    const guidance = document.createElement("p");
    guidance.id = "reasoning-guidance";
    guidance.className = "small";
    guidance.textContent =
      session.language === "hi"
        ? "डेटा से प्रमाण दें, एक नियंत्रित और दोहराने योग्य अगला परीक्षण सुझाएँ, और बताएँ कि निष्कर्ष की सीमा क्या है।"
        : "Cite evidence from the data, propose a controlled test that can be repeated, and explain the limits of your conclusion.";
    explanation.before(guidance);
    explanation.setAttribute("aria-describedby", guidance.id);
  }
  if (storageError) {
    const panel = document.createElement("section");
    panel.className = "card";
    panel.innerHTML =
      '<h2>Preserve your work</h2><p>Save a recovery copy before reloading or clearing this session.</p><button id="recovery-download" class="secondary">Download recovery copy</button><button id="reload-session" class="secondary">Reload saved session</button>';
    document.querySelector("main").prepend(panel);
    if (session || !ownsEditor)
      document
        .querySelectorAll(
          "main button, main input, main textarea, main select, nav button",
        )
        .forEach((el) => {
          el.disabled = true;
        });
    panel.querySelectorAll("button").forEach((el) => {
      el.disabled = false;
    });
    panel.querySelector("#recovery-download").onclick = () =>
      downloadData(session || { unparsed: lastSaved }, "nua-recovery.json");
    panel.querySelector("#reload-session").onclick = () => location.reload();
  }
}
$("#app").textContent = "Opening saved assessment…";
withSessionEditor(navigator.locks, window.NuaNative, initialize).catch(() =>
  initialize(false),
);
if (!window.NuaNative && "serviceWorker" in navigator)
  navigator.serviceWorker.register("/sw.js").catch(() => {});
document.addEventListener("visibilitychange", () => {
  if (!document.hidden && session?.stage === "waiting" && view === "activity")
    render();
});
window.addEventListener("storage", (e) => {
  if (
    (e.key === KEY && e.newValue !== lastSaved) ||
    (e.key === null && lastSaved !== null)
  ) {
    storageError =
      "This session changed in another window. Save a recovery copy, then reload the latest saved session.";
    render();
  }
});
