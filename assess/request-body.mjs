// Decode after all bytes arrive: a UTF-8 character may span TCP chunks.
export async function readJSONBody(stream, limit = 4096) {
  const chunks = [];
  let size = 0;
  for await (const chunk of stream) {
    const bytes = Buffer.from(chunk);
    size += bytes.length;
    if (size > limit)
      throw Object.assign(Error("Request too large."), { status: 413 });
    chunks.push(bytes);
  }
  try {
    return JSON.parse(
      new TextDecoder("utf-8", { fatal: true }).decode(Buffer.concat(chunks)),
    );
  } catch {
    throw Object.assign(Error("Invalid UTF-8 JSON."), { status: 400 });
  }
}
