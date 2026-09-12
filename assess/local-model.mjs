import { hintPrompt } from "./web/core.js";

export const INFERENCE_TIMEOUT_MS = 60000;
// Production and evaluation must use the same prompt and generation settings.
export function modelRequest(model, question, language) {
  return {
    model,
    prompt: hintPrompt(question, language),
    stream: false,
    think: false,
    keep_alive: "5m",
    options: { temperature: 0, num_predict: 16, num_ctx: 1024 },
  };
}

export function extractRoute(response) {
  return String(response.response || "")
    .replace(/<think>[\s\S]*?<\/think>/g, "")
    .trim()
    .slice(0, 900);
}
