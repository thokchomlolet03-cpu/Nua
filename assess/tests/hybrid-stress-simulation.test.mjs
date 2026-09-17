import test from "node:test";
import assert from "node:assert/strict";
import * as core from "../web/mangal-core.js";
import { addFeedback, addFollowup } from "../web/assessment-feedback.js";
import {
  generateInquiryQuestionsGemini,
  analyzeResponseGemini,
  GEMINI_MODEL,
} from "../gemini-client.mjs";

// ============================================================================
// CURRICULUM FIXTURES (Manipur Pilot Scenarios)
// ============================================================================

const ZOOLOGY_CURRICULUM = {
  subject: "Zoology",
  gradeLevel: "Higher Secondary (Class 11)",
  title: "Comparative Gas Exchange in Aquatic and Terrestrial Organisms",
  objective: "Explain how countercurrent exchange maintains respiratory efficiency across gill lamellae.",
  passage: `Gills and lungs represent two distinct structural solutions for gas exchange under different physical constraints. In aquatic respiration, water flows unidirectionally across gill lamellae where blood flows in the opposite direction. This countercurrent exchange mechanism maintains a concentration gradient along the entire capillary bed, allowing fish to extract up to 80% of dissolved oxygen from water. In contrast, terrestrial organisms rely on tidal ventilation in lungs with millions of microscopic alveoli, providing a massive surface area while minimizing evaporative water loss. However, tidal flow results in residual volume where fresh air mixes with stale air, capping extraction efficiency at approximately 25%. If ambient temperature rises, dissolved oxygen drops sharply while metabolic demand increases, exposing the physical limits of aquatic respiration.`,
};

const PHYSICS_CURRICULUM = {
  subject: "Physics",
  gradeLevel: "Higher Secondary (Class 12)",
  title: "Wave Optics and Two-Slit Interference",
  objective: "Determine how slit separation and wavelength affect interference fringe spacing on a distant screen.",
  passage: `When monochromatic light with wavelength λ passes through two narrow parallel slits separated by distance d, it produces an interference pattern on a screen located at distance D. The condition for constructive interference at angle θ is given by d sin(θ) = mλ, where m is an integer representing the fringe order. For small angles, the linear separation between adjacent bright fringes is given by β = λD/d. Decreasing the slit separation d increases the fringe width, spreading the pattern across the screen. If white light is used instead of monochromatic light, the central fringe remains white while surrounding fringes appear colored, with violet fringes positioned closer to the central axis than red fringes due to violet's shorter wavelength.`,
};

const LOKTAK_CURRICULUM = {
  subject: "Environmental Science",
  gradeLevel: "Secondary (Class 10)",
  title: "Loktak Lake Phumdi Dynamics and Wetland Conservation in Manipur",
  objective: "Analyze how seasonal water level fluctuations sustain the floating phumdi ecosystem of Keibul Lamjao.",
  passage: `Loktak Lake in Manipur is renowned for its heterogeneous floating masses of vegetation, soil, and organic matter called phumdis. Keibul Lamjao National Park, located in the southern portion of the lake, is the world's only floating wildlife sanctuary and the exclusive natural habitat of the endangered Eld's deer or Sangai (Rucervus eldii eldii). Historically, the phumdi ecosystem survived through a natural hydrological cycle: during the rainy season, phumdis floated on high water levels, and during the dry winter season, water levels receded, allowing the dense roots to touch the lake bed and absorb essential nutrients from the substrate. The construction of the Ithai Barrage for hydroelectric power created a permanent reservoir, preventing the natural dry-season sinking and causing the phumdis to thin and disintegrate over time.`,
};

// ============================================================================
// TEST SUITE: MULTI-AGENT TEACHER & STUDENT STRESS SIMULATION
// ============================================================================

test("Teacher Agent 1 (Manipur Zoology Educator) synthesizes, validates, and approves 20-question lesson", async () => {
  const { subject, gradeLevel, title, objective, passage } = ZOOLOGY_CURRICULUM;

  // 1. Synthesize 20 multifaceted inquiry questions with Gemini client (mock/test mode)
  const questions = await generateInquiryQuestionsGemini("mock", {
    subject,
    gradeLevel,
    objective,
    excerpt: passage,
    page: 1,
  });

  assert.equal(questions.length, 20);
  assert.equal(new Set(questions.map((q) => q.type)).size, 20);

  // 2. Teacher validates that all anchors are verbatim substrings of the source passage
  for (const q of questions) {
    assert.ok(
      passage.includes(q.anchor),
      `Question ${q.id} anchor must be verbatim substring: "${q.anchor}"`,
    );
    assert.ok(q.prompt.length >= 12, `Question ${q.id} prompt too short`);
    assert.ok(q.criterion.length >= 12, `Question ${q.id} criterion too short`);
    assert.equal(q.page, 1);
  }

  // 3. Teacher conducts editorial review: customizes prompt of question 1 and 3
  questions[0].prompt = "Describe the exact geometric arrangement of water and blood flow in fish gills.";
  questions[2].prompt = "Explain how rising water temperature impacts dissolved oxygen availability.";

  // 4. Teacher marks all questions as approved
  questions.forEach((q) => {
    q.reviewed = true;
  });

  // 5. Construct lesson plan object
  const lessonPlan = {
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
      model: GEMINI_MODEL,
      policy: "focused-inquiry-1",
      breadthPolicy: "mangal-breadth/1",
      questions,
    },
  };

  // 6. Pre-export validation must pass with zero errors
  assert.doesNotThrow(() => {
    core.validatePlan(lessonPlan.plan, lessonPlan.pages);
  });
});

test("Teacher Agent 2 (Physics Educator) generates 100% offline rule-based lesson with zero network", () => {
  const { title, objective, passage } = PHYSICS_CURRICULUM;

  // 1. Generate offline template
  const plan = core.templatePlan(objective, passage, 1);
  assert.equal(plan.questions.length, 20);
  assert.equal(plan.breadthPolicy, "mangal-breadth/1");
  assert.equal(plan.origin, "rule-based-template");

  // 2. Validate all questions conform to 20 distinct cognitive inquiry types
  const types = plan.questions.map((q) => q.type);
  assert.equal(new Set(types).size, 20);

  // 3. Approve all questions
  plan.questions.forEach((q) => {
    q.reviewed = true;
  });

  const lessonPlan = {
    schema: "nua-mangal-lesson/2",
    title,
    pages: [{ page: 1, text: passage }],
    plan,
  };

  // 4. Validate exported structure
  assert.doesNotThrow(() => {
    core.validatePlan(lessonPlan.plan, lessonPlan.pages);
  });
});

test("Student Agent 1 ('Imphal Scientific Achiever') completes 4-phase inquiry on Zoology lesson", async () => {
  const { title, objective, passage } = ZOOLOGY_CURRICULUM;
  const questions = await generateInquiryQuestionsGemini("mock", {
    objective,
    excerpt: passage,
    page: 1,
  });
  questions.forEach((q) => (q.reviewed = true));

  const lessonPlan = {
    schema: "nua-mangal-lesson/2",
    title,
    pages: [{ page: 1, text: passage }],
    plan: {
      objective,
      page: 1,
      quote: passage,
      perspective: "causality",
      reason: "Comparative respiration mechanics.",
      recall: "Recall objective: " + objective,
      investigate: "Investigate: " + objective,
      transfer: "Transfer: " + objective,
      rubric: "Observable criterion check.",
      origin: "gemini-ai-draft",
      model: GEMINI_MODEL,
      policy: "focused-inquiry-1",
      breadthPolicy: "mangal-breadth/1",
      questions,
    },
  };

  // 1. Create Student Inquiry Session
  const session = core.createInquiry(
    {
      title: "Student 1 Assessment",
      pages: lessonPlan.pages,
      plan: lessonPlan.plan,
      mode: "self-study-provisional",
      id: "student-agent-1",
    },
    1000,
  );

  assert.equal(session.phase, "prepare");

  // Phase 1: Unassisted Recall
  core.beginRecall(session, 1010);
  assert.equal(session.phase, "recall");
  core.submit(
    session,
    "Countercurrent exchange allows fish to extract up to 80% oxygen by maintaining a continuous concentration gradient where water and blood flow in opposite directions across gill lamellae.",
    1020,
  );
  assert.equal(session.phase, "investigate");

  // Phase 2: 20-Question Breadth Workflow
  for (let i = 0; i < 20; i++) {
    const q = session.plan.questions[i];
    assert.equal(session.breadth.index, i);

    // Deep scientific response addressing the cognitive prompt
    const studentText = `In analyzing ${q.type}, the evidence demonstrates that opposing flow directions prevent equilibrium, maintaining oxygen diffusion across the whole capillary length.`;
    core.submit(session, studentText, 1030 + i * 10);
  }

  // After 20 breadth questions, phase transitions to "question" (synthesis check)
  assert.equal(session.phase, "question");
  core.submit(
    session,
    "How does temperature rise reduce oxygen diffusion capacity in aquatic species compared to terrestrial air breathers?",
    1250,
  );

  // Phase 3: Source Exposed Revision
  assert.equal(session.phase, "revise");
  core.submit(
    session,
    "After reviewing the source text, tidal ventilation in mammalian lungs introduces a residual volume of stale air, capping oxygen extraction at approximately 25%, unlike the unidirectional countercurrent gill system.",
    1300,
  );

  // Phase 4: 24-Hour Transfer (exercising immediate-demo override)
  assert.equal(session.phase, "waiting");
  core.returnForTransfer(session, "immediate-demo", 1310);
  assert.equal(session.phase, "transfer");
  core.submit(
    session,
    "In industrial chemical engineering, countercurrent heat exchangers apply the identical principle: hot and cold fluids flow oppositely to maximize thermal transfer efficiency.",
    1320,
  );

  assert.equal(session.phase, "complete");

  // 2. Export and generate report
  const report = core.inquiryReport(session);
  assert.equal(report.phase, "complete");
  assert.equal(report.breadth.completed, 20);
  assert.equal(session.breadth.answers.length, 20);

  // 3. Teacher Diagnostic Evaluation on Student 1's answers
  const diagnosis = await analyzeResponseGemini("mock", {
    questionPrompt: session.plan.questions[0].prompt,
    expectedCriterion: session.plan.questions[0].criterion,
    studentAnswer: session.breadth.answers[0].text,
  });

  assert.ok(["true", "false", "partially"].includes(diagnosis.criterion_met) || typeof diagnosis.criterion_met === "boolean");
  assert.ok(diagnosis.strength);
  assert.ok(diagnosis.suggested_next_step);
});

test("Student Agent 2 ('Assisted / Struggling Learner') utilizes source assistance on complex questions", async () => {
  const { title, objective, passage } = ZOOLOGY_CURRICULUM;
  const questions = await generateInquiryQuestionsGemini("mock", {
    objective,
    excerpt: passage,
    page: 1,
  });
  questions.forEach((q) => (q.reviewed = true));

  const lessonPlan = {
    schema: "nua-mangal-lesson/2",
    title,
    pages: [{ page: 1, text: passage }],
    plan: {
      objective,
      page: 1,
      quote: passage,
      perspective: "causality",
      reason: "Comparative respiration.",
      recall: "Recall: " + objective,
      investigate: "Investigate: " + objective,
      transfer: "Transfer: " + objective,
      rubric: "Observable criteria.",
      origin: "gemini-ai-draft",
      model: GEMINI_MODEL,
      policy: "focused-inquiry-1",
      breadthPolicy: "mangal-breadth/1",
      questions,
    },
  };

  const session = core.createInquiry(
    {
      title: "Student 2 Assisted",
      pages: lessonPlan.pages,
      plan: lessonPlan.plan,
      mode: "self-study-provisional",
      id: "student-agent-2",
    },
    2000,
  );

  core.beginRecall(session, 2010);
  core.submit(session, "Fish breathe underwater using gills while humans use lungs on land.", 2020);

  // Student needs source assistance on questions 4, 8, 12, 16
  const assistedIndices = [4, 8, 12, 16];
  for (let i = 0; i < 20; i++) {
    if (assistedIndices.includes(i)) {
      // Expose source passage to provide scaffolding
      core.exposeSource(session, 2030 + i * 10);
      assert.ok(session.breadth.sourceViews.includes(i));
    }

    core.submit(
      session,
      `Student answer for question ${i + 1}: The passage states that water flows across lamellae.`,
      2035 + i * 10,
    );

    const condition = session.breadth.answers[i].condition;
    if (assistedIndices.includes(i)) {
      assert.equal(condition, "source-assisted");
    } else {
      assert.equal(condition, "perspective-prompted");
    }
  }

  // Phase transition: question -> revise -> waiting -> transfer -> complete
  core.submit(session, "Can gills function in air if moisture is maintained?", 2250);
  core.submit(session, "Revision: Countercurrent exchange prevents equilibrium across capillaries.", 2300);
  core.returnForTransfer(session, "immediate-demo", 2310);
  core.submit(session, "Transfer: Renal nephrons in kidneys also use countercurrent multiplication to concentrate urine.", 2320);

  assert.equal(session.phase, "complete");

  // Verify educator feedback can be recorded on assisted questions
  addFeedback(
    session,
    {
      questionId: session.plan.questions[4].id,
      interpretation: "needs-clarification",
      evidence: "The passage states that water flows across lamellae.",
      nextStep: "Contrast concurrent flow with countercurrent flow explicitly.",
      successCriterion: "States why 50% is the theoretical limit of concurrent exchange.",
    },
    2330,
  );

  assert.equal(session.feedback.entries.length, 1);
  assert.equal(session.feedback.entries[0].questionId, "q5");
});

test("Student Agent 3 ('Minimalist') and Agent 4 ('Lengthy') test boundary and edge conditions", () => {
  const { title, objective, passage } = LOKTAK_CURRICULUM;
  const plan = core.templatePlan(objective, passage, 1);
  plan.questions.forEach((q) => (q.reviewed = true));

  // --- Student 3: Boundary minimal responses (exact 12-char threshold) ---
  const sessionMin = core.createInquiry(
    {
      title: "Student 3 Minimal",
      pages: [{ page: 1, text: passage }],
      plan,
      mode: "self-study-provisional",
      id: "student-min",
    },
    3000,
  );

  core.beginRecall(sessionMin, 3010);
  core.submit(sessionMin, "Phumdis float during rain.", 3020); // 26 chars > 12

  for (let i = 0; i < 20; i++) {
    // 13 characters: exactly passes >= 12 char limit
    core.submit(sessionMin, "Valid answer!", 3030 + i * 10);
  }

  core.submit(sessionMin, "Minimal question prompt text?", 3240);
  core.submit(sessionMin, "Winter recess roots touch bed.", 3250);
  core.returnForTransfer(sessionMin, "immediate-demo", 3260);
  core.submit(sessionMin, "Hydroelectric dams change river floodplains.", 3270);
  assert.equal(sessionMin.phase, "complete");

  // --- Student 4: Long, complex Manipuri context responses ---
  const sessionMax = core.createInquiry(
    {
      title: "Student 4 Elaborate",
      pages: [{ page: 1, text: passage }],
      plan,
      mode: "self-study-provisional",
      id: "student-max",
    },
    4000,
  );

  core.beginRecall(sessionMax, 4010);
  const elaborateRecall =
    "In the Loktak Ramsar wetland ecosystem of Manipur, the Eld's deer or Sangai inhabits Keibul Lamjao. The seasonal hydrological equilibrium is disrupted by the Ithai Barrage, which holds water continuously high, starving phumdi biomass of benthic nutrient replenishment from the lake sediment substrate.";
  core.submit(sessionMax, elaborateRecall, 4020);

  for (let i = 0; i < 20; i++) {
    const elaborateAnswer = `Concerning inquiry type ${plan.questions[i].type}: Ecological data indicates that permanent inundation prevents roots from grounding during dry winters. Consequently, nutrient absorption ceases and anthropogenic pollutants compound eutrophication.`;
    core.submit(sessionMax, elaborateAnswer, 4030 + i * 10);
  }

  core.submit(sessionMax, "What ecological engineering interventions can restore seasonal drawdown?", 4250);
  core.submit(sessionMax, elaborateRecall, 4300);
  core.returnForTransfer(sessionMax, "immediate-demo", 4310);
  core.submit(
    sessionMax,
    "Analogously, in the Everglades wetland system in Florida, artificial water regulation structures have similarly interrupted historical wet-dry cycles necessary for wading bird nesting and peat soil maintenance.",
    4320,
  );

  assert.equal(sessionMax.phase, "complete");
  assert.equal(sessionMax.breadth.answers.length, 20);
});

test("Adversarial Tampering Stress Tests: system rigorously rejects corrupted inputs", () => {
  const { objective, passage } = ZOOLOGY_CURRICULUM;
  const plan = core.templatePlan(objective, passage, 1);
  plan.questions.forEach((q) => (q.reviewed = true));

  // 1. TAMPERING: Question anchor quote modified to non-existent text
  const corruptedPlan = structuredClone(plan);
  corruptedPlan.questions[0].anchor = "THIS TEXT DEFINITELY DOES NOT EXIST IN THE PASSAGE AT ALL.";
  assert.throws(
    () => core.validatePlan(corruptedPlan, [{ page: 1, text: passage }]),
    /anchor must occur in the selected source passage/,
    "Corrupted anchor quote must be caught by validatePlan",
  );

  // 2. TAMPERING: Less than 20 questions
  const shortPlan = structuredClone(plan);
  shortPlan.questions = shortPlan.questions.slice(0, 15);
  assert.throws(
    () => core.validatePlan(shortPlan, [{ page: 1, text: passage }]),
    /Prepare 20–40 questions/,
    "Under-length question set must be rejected",
  );

  // 3. TAMPERING: Duplicate question IDs
  const duplicateIdPlan = structuredClone(plan);
  duplicateIdPlan.questions[1].id = duplicateIdPlan.questions[0].id;
  assert.throws(
    () => core.validatePlan(duplicateIdPlan, [{ page: 1, text: passage }]),
    /Question IDs must be unique/,
    "Duplicate IDs must be rejected",
  );

  // 4. TAMPERING: Attempting to submit empty or 3-char text
  const session = core.createInquiry(
    {
      title: "Tamper Test",
      pages: [{ page: 1, text: passage }],
      plan,
      mode: "self-study-provisional",
      id: "tamper-test",
    },
    5000,
  );
  core.beginRecall(session, 5010);
  assert.throws(
    () => core.submit(session, "bad", 5020),
    /12/,
    "Answers under 12 characters must be blocked",
  );

  // 5. TAMPERING: 24-hour gate enforcement (transfer before 24h without demo flag)
  core.submit(session, "Valid recall answer greater than twelve chars.", 5030);
  for (let i = 0; i < 20; i++) {
    core.submit(session, "Valid breadth answer here for question " + i, 5040 + i * 5);
  }
  core.submit(session, "Valid synthesis question here over twelve chars.", 5150);
  core.submit(session, "Valid synthesis revise answer here over twelve characters.", 5200);

  // Session is now in 'waiting' phase with dueAt = 5200 + 86400000
  assert.equal(session.phase, "waiting");

  // Attempting delayed application within 5 minutes without immediate-demo flag
  assert.throws(
    () => core.returnForTransfer(session, false, 5205),
    /delayed return is not due yet/,
    "Immediate return under delayed-application mode must be rejected by device clock",
  );
});

test("Concurrency & Parallel Load Stress: multiple educators generating lessons simultaneously", async () => {
  const educators = [
    { name: "Zoology-1", curriculum: ZOOLOGY_CURRICULUM },
    { name: "Physics-2", curriculum: PHYSICS_CURRICULUM },
    { name: "Loktak-3", curriculum: LOKTAK_CURRICULUM },
    { name: "Zoology-4", curriculum: ZOOLOGY_CURRICULUM },
  ];

  // Fire 4 parallel generation pipelines
  const results = await Promise.all(
    educators.map(async ({ name, curriculum }) => {
      const questions = await generateInquiryQuestionsGemini("mock", {
        subject: curriculum.subject,
        gradeLevel: curriculum.gradeLevel,
        objective: curriculum.objective,
        excerpt: curriculum.passage,
        page: 1,
      });
      assert.equal(questions.length, 20);
      return { name, count: questions.length };
    }),
  );

  assert.equal(results.length, 4);
  results.forEach((r) => assert.equal(r.count, 20));
});
