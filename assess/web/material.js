import { normalizePages } from "./inquiry-core.js";
export async function readMaterial(file) {
  if (!file || file.size > 12 * 1024 * 1024)
    throw Error("Choose a file no larger than 12 MB.");
  if (/\.(txt|md)$/i.test(file.name))
    return {
      pages: normalizePages([{ text: await file.text() }]),
      warnings: [
        "Plain text is treated as one section. Original page layout is not available.",
      ],
    };
  if (!/\.pdf$/i.test(file.name))
    throw Error(
      "This release supports text-based PDF, .txt, .md and pasted text. Export presentations to a readable PDF.",
    );
  const pdfjs = await import("./vendor/pdf.min.js");
  pdfjs.GlobalWorkerOptions.workerSrc = new URL(
    "./vendor/pdf.worker.min.js",
    import.meta.url,
  ).href;
  const loading = pdfjs.getDocument({
    data: new Uint8Array(await file.arrayBuffer()),
    isEvalSupported: false,
    useSystemFonts: true,
    disableFontFace: true,
    stopAtErrors: true,
  });
  let doc;
  try {
    doc = await loading.promise;
    if (doc.numPages > 80)
      throw Error("Split documents longer than 80 pages into lessons.");
    const pages = [],
      warnings = [
        "Text extraction only: diagrams, handwriting, formula meaning, tables and reading order are not verified. Review each relevant page against the original. No OCR is performed.",
      ];
    let total = 0;
    for (let i = 1; i <= doc.numPages; i++) {
      let text = "";
      try {
        const page = await doc.getPage(i),
          content = await page.getTextContent();
        text = content.items
          .map((item) =>
            typeof item.str === "string"
              ? item.str + (item.hasEOL ? "\n" : " ")
              : "",
          )
          .join("")
          .trim();
        page.cleanup();
        if (text.length < 40)
          warnings.push(
            `Page ${i}: little or no text extracted; visual or scanned content may be missing.`,
          );
      } catch {
        warnings.push(
          `Page ${i}: extraction failed; do not assess this page without checked pasted text.`,
        );
      }
      total += text.length;
      if (total > 180000)
        throw Error(
          "Too much extracted text. Split this document into shorter lessons.",
        );
      pages.push({ text });
    }
    return { pages: normalizePages(pages), warnings };
  } finally {
    await loading.destroy();
  }
}
