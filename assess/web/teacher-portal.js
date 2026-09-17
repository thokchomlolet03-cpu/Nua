import { questionTypes, questionGuidance, expandPlan } from "./inquiry-questions.js";
import { templatePlan, validatePlan } from "./mangal-core.js";

let currentPlan = null;
let currentStudentData = null;

function $(sel) {
  return document.querySelector(sel);
}

function showToast(msg) {
  const toast = $("#toast");
  if (!toast) return;
  toast.textContent = msg;
  toast.className = "notice";
  toast.style.display = "block";
  setTimeout(() => {
    toast.style.display = "none";
  }, 4000);
}

function switchTab(tabId) {
  $("#section-create").style.display = tabId === "create" ? "block" : "none";
  $("#section-review").style.display = tabId === "review" ? "block" : "none";
  $("#section-grading").style.display = tabId === "grading" ? "block" : "none";

  $("#nav-create").classList.toggle("active", tabId === "create");
  $("#nav-review").classList.toggle("active", tabId === "review");
  $("#nav-grading").classList.toggle("active", tabId === "grading");
}

async function checkStatus() {
  const keyInput = $("#gemini-key-input");
  const stored = sessionStorage.getItem("gemini_api_key");
  if (stored && keyInput) {
    keyInput.value = stored;
  }

  try {
    const res = await fetch("/api/gemini/status");
    const data = await res.json();
    $("#model-label").textContent = data.label || "Gemini 3.8 Flash Ready";
  } catch {
    $("#model-label").textContent = "Gemini 3.8 Flash · Local Server Active";
  }
}

function getApiKey() {
  const input = $("#gemini-key-input")?.value?.trim();
  if (input) return input;
  return sessionStorage.getItem("gemini_api_key") || "";
}

async function handleGenerateGemini() {
  const subject = $("#lesson-subject").value.trim();
  const gradeLevel = $("#lesson-grade").value.trim();
  const title = $("#lesson-title").value.trim() || "Science Lesson";
  const objective = $("#lesson-objective").value.trim();
  const passage = $("#lesson-passage").value.trim();
  const apiKey = getApiKey();

  if (!objective || objective.length < 5) {
    alert("Please provide a specific learning objective.");
    return;
  }
  if (!passage || passage.length < 20) {
    alert("Please provide at least 20 characters of curriculum text.");
    return;
  }

  const btn = $("#btn-generate-gemini");
  const originalText = btn.innerHTML;
  btn.disabled = true;
  btn.textContent = "Synthesizing 20 multifaceted questions with Gemini 3.8 Flash...";

  try {
    const headers = { "Content-Type": "application/json" };
    if (apiKey) {
      headers["Authorization"] = `Bearer ${apiKey}`;
    }

    const res = await fetch("/api/gemini/generate-questions", {
      method: "POST",
      headers,
      body: JSON.stringify({
        subject,
        gradeLevel,
        objective,
        excerpt: passage,
        page: 1,
        apiKey: apiKey || undefined,
      }),
    });

    const data = await res.json();
    if (!res.ok) {
      throw new Error(data.error || "Failed to generate questions.");
    }

    const questions = data.questions;
    currentPlan = {
      schema: "nua-mangal-lesson/2",
      title,
      pages: [{ page: 1, text: passage }],
      plan: {
        objective,
        page: 1,
        quote: passage,
        perspective: "causality",
        reason: `Examine ${subject} mechanisms and evidence.`,
        recall: `Without reopening the passage, explain what you remember that addresses this objective: ${objective}`,
        investigate: `Identify a proposed cause and an alternative explanation related to: ${objective}`,
        transfer: `Describe a different situation where the idea in this objective might apply: ${objective}`,
        rubric: "Check accuracy against the source; connection between claim and evidence; and observable reasoning.",
        origin: "gemini-ai-draft",
        model: data.model || "gemini-3.8-flash",
        policy: "focused-inquiry-1",
        breadthPolicy: "mangal-breadth/1",
        questions,
      },
    };

    renderReviewSection();
    switchTab("review");
    showToast("20 questions generated! Please review and verify each question below.");
  } catch (err) {
    alert("Generation error: " + err.message);
  } finally {
    btn.disabled = false;
    btn.innerHTML = originalText;
  }
}

function handleGenerateTemplate() {
  const title = $("#lesson-title").value.trim() || "Science Lesson";
  const objective = $("#lesson-objective").value.trim();
  const passage = $("#lesson-passage").value.trim();

  if (!objective || !passage) {
    alert("Please provide both an objective and curriculum text.");
    return;
  }

  const fullPlan = templatePlan(objective, passage, 1);

  currentPlan = {
    schema: "nua-mangal-lesson/2",
    title,
    pages: [{ page: 1, text: passage }],
    plan: fullPlan,
  };

  renderReviewSection();
  switchTab("review");
  showToast("Offline template plan created! Please review before exporting.");
}

function renderReviewSection() {
  if (!currentPlan?.plan?.questions) return;

  const list = $("#questions-list");
  list.innerHTML = "";

  const passage = currentPlan.pages[0].text;
  const questions = currentPlan.plan.questions;

  updateStats();

  questions.forEach((q, idx) => {
    const card = document.createElement("div");
    card.className = `q-card ${q.reviewed ? "approved" : "pending"}`;
    card.id = `q-card-${idx}`;

    const typeTitle = questionTypes[q.type]?.title || q.type;
    const isVerbatim = passage.includes(q.anchor);

    card.innerHTML = `
      <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:8px;">
        <div>
          <span class="badge ${q.kind === "source" ? "badge-source" : "badge-investigation"}">${q.kind.toUpperCase()}</span>
          <strong style="margin-left:8px; font-size:16px;">${idx + 1}. ${typeTitle}</strong>
        </div>
        <div>
          <span class="badge ${isVerbatim ? "badge-verified" : "badge-warning"}">
            ${isVerbatim ? "&check; Verbatim Source Match" : "&#9888; Anchor Paraphrased"}
          </span>
          <label style="margin-left:14px; font-weight:600;">
            <input type="checkbox" class="q-reviewed-checkbox" data-index="${idx}" ${q.reviewed ? "checked" : ""} />
            Approved
          </label>
        </div>
      </div>

      <div class="form-group">
        <label>Question Prompt</label>
        <textarea class="q-prompt-input" data-index="${idx}" rows="2">${esc(q.prompt)}</textarea>
      </div>

      <div class="form-row">
        <div class="form-group">
          <label>Observable Criterion (What to look for in student response)</label>
          <input class="q-criterion-input" data-index="${idx}" value="${esc(q.criterion)}" />
        </div>
        <div class="form-group">
          <label>Reasoning Relevance</label>
          <input class="q-relevance-input" data-index="${idx}" value="${esc(q.relevance)}" />
        </div>
      </div>

      <div class="form-group">
        <label>Source Passage Anchor Quote</label>
        <input class="q-anchor-input" data-index="${idx}" value="${esc(q.anchor)}" />
        <div class="verbatim-box">"${esc(q.anchor)}"</div>
      </div>
    `;

    list.appendChild(card);
  });

  // Bind live updates
  list.querySelectorAll(".q-reviewed-checkbox").forEach((cb) => {
    cb.addEventListener("change", (e) => {
      const idx = Number(e.target.dataset.index);
      currentPlan.plan.questions[idx].reviewed = e.target.checked;
      const card = $(`#q-card-${idx}`);
      card.className = `q-card ${e.target.checked ? "approved" : "pending"}`;
      updateStats();
    });
  });

  list.querySelectorAll(".q-prompt-input").forEach((el) => {
    el.addEventListener("input", (e) => {
      const idx = Number(e.target.dataset.index);
      currentPlan.plan.questions[idx].prompt = e.target.value;
      unreview(idx);
    });
  });

  list.querySelectorAll(".q-criterion-input").forEach((el) => {
    el.addEventListener("input", (e) => {
      const idx = Number(e.target.dataset.index);
      currentPlan.plan.questions[idx].criterion = e.target.value;
      unreview(idx);
    });
  });

  list.querySelectorAll(".q-anchor-input").forEach((el) => {
    el.addEventListener("input", (e) => {
      const idx = Number(e.target.dataset.index);
      currentPlan.plan.questions[idx].anchor = e.target.value;
      unreview(idx);
    });
  });
}

function unreview(idx) {
  if (currentPlan.plan.questions[idx].reviewed) {
    currentPlan.plan.questions[idx].reviewed = false;
    const cb = $(`#q-card-${idx} .q-reviewed-checkbox`);
    if (cb) cb.checked = false;
    const card = $(`#q-card-${idx}`);
    if (card) card.className = "q-card pending";
    updateStats();
  }
}

function updateStats() {
  const total = currentPlan?.plan?.questions?.length || 0;
  const approved = currentPlan?.plan?.questions?.filter((q) => q.reviewed).length || 0;
  $("#review-stats").textContent = `${approved} of ${total} questions approved by educator.`;
}

function handleApproveAll() {
  if (!currentPlan?.plan?.questions) return;
  currentPlan.plan.questions.forEach((q) => {
    q.reviewed = true;
  });
  renderReviewSection();
  showToast("All questions approved!");
}

function handleExportLesson() {
  if (!currentPlan) return;

  try {
    validatePlan(currentPlan.plan, currentPlan.pages);
  } catch (err) {
    alert("Cannot export: Lesson plan failed validation:\n" + err.message);
    return;
  }

  const unapproved = currentPlan.plan.questions.filter((q) => !q.reviewed);
  if (unapproved.length > 0) {
    if (!confirm(`Warning: ${unapproved.length} question(s) are not yet marked as approved. Export anyway?`)) {
      return;
    }
  }

  const jsonStr = JSON.stringify(currentPlan, null, 2);
  const blob = new Blob([jsonStr], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = `nua-mangal-lesson-${Date.now()}.json`;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
  showToast("Lesson exported! Distribute this JSON file to students for offline assessment.");
}

async function handleImportResponse(file) {
  try {
    const text = await file.text();
    const data = JSON.parse(text);
    currentStudentData = data;
    renderStudentResponses(data);
  } catch (err) {
    alert("Could not parse student response file: " + err.message);
  }
}

function renderStudentResponses(data) {
  const container = $("#student-responses-container");
  container.innerHTML = "";

  $("#student-submission-view").style.display = "block";
  $("#student-meta-title").textContent = `Student Assessment Submission · ${data.title || "Lesson"}`;
  $("#student-meta-info").textContent = `Format: ${data.schema || "nua-mangal-session"} | Completed: ${new Date(data.updatedAt || Date.now()).toLocaleString()}`;

  const answers = data.breadth?.answers || [];
  if (answers.length === 0) {
    container.innerHTML = "<p>No breadth question answers found in this export.</p>";
    return;
  }

  answers.forEach((ans, i) => {
    const q = data.plan?.questions?.[i] || {};
    const card = document.createElement("div");
    card.className = "q-card";
    card.innerHTML = `
      <div style="display:flex; justify-content:space-between; align-items:center;">
        <strong>Question ${i + 1}: ${questionTypes[ans.type]?.title || ans.type}</strong>
        <span class="badge ${ans.condition === "independent" ? "badge-verified" : "badge-warning"}">
          ${ans.condition || "unassisted"}
        </span>
      </div>
      <p class="small"><strong>Prompt:</strong> ${esc(q.prompt || "")}</p>
      <div class="verbatim-box" style="margin:8px 0; background:#f5f8f5;">
        <strong>Student Answer:</strong><br/>
        ${esc(ans.text || "")}
      </div>
      <p class="small"><strong>Expected Criterion:</strong> ${esc(q.criterion || "")}</p>

      <div style="margin-top:12px;">
        <button class="secondary btn-analyze-ai" data-index="${i}" style="font-size:13px;">
          &uarr; Diagnose with Gemini 3.8 Flash
        </button>
      </div>
      <div class="ai-analysis-result" id="ai-res-${i}" style="display:none; margin-top:10px; background:#fff; border:1px solid var(--line); border-radius:8px; padding:12px;">
      </div>
    `;

    container.appendChild(card);
  });

  container.querySelectorAll(".btn-analyze-ai").forEach((btn) => {
    btn.addEventListener("click", async (e) => {
      const idx = Number(e.target.dataset.index);
      const ans = answers[idx];
      const q = data.plan?.questions?.[idx] || {};
      const resBox = $(`#ai-res-${idx}`);

      e.target.disabled = true;
      e.target.textContent = "Analyzing...";

      try {
        const apiKey = getApiKey();
        const headers = { "Content-Type": "application/json" };
        if (apiKey) {
          headers["Authorization"] = `Bearer ${apiKey}`;
        }

        const res = await fetch("/api/gemini/analyze-response", {
          method: "POST",
          headers,
          body: JSON.stringify({
            questionPrompt: q.prompt,
            expectedCriterion: q.criterion,
            studentAnswer: ans.text,
            apiKey: apiKey || undefined,
          }),
        });
        const result = await res.json();
        if (!res.ok) throw new Error(result.error);

        const a = result.analysis;
        resBox.style.display = "block";
        resBox.innerHTML = `
          <p><strong>Criterion Met:</strong> ${a.criterion_met}</p>
          <p><strong>Strength:</strong> ${esc(a.strength || "None noted")}</p>
          <p><strong>Gap:</strong> ${esc(a.gap || "None noted")}</p>
          <p><strong>Suggested Actionable Next Step:</strong> ${esc(a.suggested_next_step || "")}</p>
        `;
      } catch (err) {
        alert("Analysis error: " + err.message);
      } finally {
        e.target.disabled = false;
        e.target.textContent = "Diagnose with Gemini 3.8 Flash";
      }
    });
  });
}

function esc(str) {
  if (!str) return "";
  return String(str)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

window.addEventListener("DOMContentLoaded", () => {
  checkStatus();

  $("#nav-create")?.addEventListener("click", () => switchTab("create"));
  $("#nav-review")?.addEventListener("click", () => switchTab("review"));
  $("#nav-grading")?.addEventListener("click", () => switchTab("grading"));

  $("#save-key-btn")?.addEventListener("click", () => {
    const val = $("#gemini-key-input").value.trim();
    if (val) {
      sessionStorage.setItem("gemini_api_key", val);
      showToast("API Key saved in browser session.");
    } else {
      sessionStorage.removeItem("gemini_api_key");
      showToast("API Key cleared.");
    }
  });

  $("#btn-generate-gemini")?.addEventListener("click", handleGenerateGemini);
  $("#btn-use-template")?.addEventListener("click", handleGenerateTemplate);
  $("#btn-approve-all")?.addEventListener("click", handleApproveAll);
  $("#btn-export-lesson")?.addEventListener("click", handleExportLesson);

  $("#file-import-response")?.addEventListener("change", (e) => {
    const file = e.target.files[0];
    if (file) handleImportResponse(file);
  });
});
