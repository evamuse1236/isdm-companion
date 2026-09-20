/** No SDK or runtime globals: usable by Deno Edge Functions and Node regression tests. */
export class JsonInputError extends Error {
  constructor(code, status = 400) {
    super(code);
    this.name = "JsonInputError";
    this.status = status;
  }
}

export function betaBodyLimit(request) {
  const route = new URL(request.url).pathname.split("/").filter(Boolean).at(-1);
  if (route === "report") return 30 * 1024 * 1024; // Four 5 MiB images, base64 encoded, plus JSON.
  if (route === "enroll" || route === "profile") return 64 * 1024;
  return 1024 * 1024;
}

/**
 * Bound bytes WHILE reading, not after request.json() has allocated the whole body.
 * Content-Length is only an early rejection hint; streamed bytes are authoritative.
 * @param {Request} request
 * @param {{maxBytes?: number, maxDepth?: number, maxNodes?: number}} options
 * @returns {Promise<Record<string, unknown>>}
 */
export async function readJsonObject(request, { maxBytes = 64 * 1024, maxDepth = 24, maxNodes = 20000 } = {}) {
  for (const value of [maxBytes, maxDepth, maxNodes]) {
    if (!Number.isSafeInteger(value) || value <= 0) throw new RangeError("JSON limits must be positive integers.");
  }
  const declared = request.headers.get("content-length");
  if (declared !== null) {
    if (!/^\d+$/.test(declared)) throw new JsonInputError("invalid_content_length");
    if (Number(declared) > maxBytes) throw new JsonInputError("body_too_large", 413);
  }
  if (!request.body) throw new JsonInputError("invalid_json");
  const reader = request.body.getReader();
  const chunks = [];
  let length = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > maxBytes) {
        await reader.cancel().catch(() => {});
        throw new JsonInputError("body_too_large", 413);
      }
      chunks.push(value);
    }
  } catch (error) {
    if (error instanceof JsonInputError) throw error;
    throw new JsonInputError("invalid_json");
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  let parsed;
  try { parsed = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes)); }
  catch { throw new JsonInputError("invalid_json"); }
  if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new JsonInputError("json_object_required");
  }
  // Iterative traversal also prevents the downstream recursive redactor overflowing.
  const stack = [[parsed, 1]];
  let visited = 0;
  while (stack.length) {
    const [value, depth] = stack.pop();
    visited += 1;
    if (visited > maxNodes || depth > maxDepth) throw new JsonInputError("json_structure_too_complex", 413);
    if (value !== null && typeof value === "object") {
      const children = Object.values(value);
      if (visited + stack.length + children.length > maxNodes) {
        throw new JsonInputError("json_structure_too_complex", 413);
      }
      for (const child of children) stack.push([child, depth + 1]);
    }
  }
  return parsed;
}
