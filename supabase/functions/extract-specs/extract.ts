// Turns pages of a maintenance document into rows of specs and part numbers for review.
// Everything here is plain logic; index.ts wires it to the model API and the HTTP server.

import Anthropic from "@anthropic-ai/sdk";

/** Limits that keep one call well inside the function's time limit and the API's size limits. */
export const MAX_PAGES = 6;
const MAX_PDF_BYTES = 12 * 1024 * 1024;
const MAX_IMAGE_BYTES = 5 * 1024 * 1024;
const IMAGE_TYPES = ["image/jpeg", "image/png"] as const;

export const SPEC_CATEGORIES = ["FLUID", "CAPACITY", "TORQUE", "ELECTRICAL", "TIRE", "OTHER"] as const;
export const PART_SYSTEMS = [
  "ENGINE", "TRANSMISSION", "BRAKES", "SUSPENSION", "ELECTRICAL", "HVAC", "PUMP", "AERIAL", "BODY", "CHASSIS", "OTHER",
] as const;

export interface KnownField {
  kind: "spec" | "part";
  component: string;
  name: string;
}

export interface Vehicle {
  unit: string;
  type: string;
  year?: number | null;
  make: string;
  model: string;
  engine: string;
  chassis: string;
  body: string;
  /** Fields the rig's profile already has; rows reuse their names so they fill template blanks. */
  fields: KnownField[];
}

const MAX_FIELDS = 200;

export type DocumentInput =
  | { kind: "pdf"; pages: number[]; data: string }
  | { kind: "images"; images: { page: number; mediaType: (typeof IMAGE_TYPES)[number]; data: string }[] };

export interface ExtractRequest {
  vehicle: Vehicle;
  document: DocumentInput;
  dense: boolean;
}

/** One row as the app receives it. Field names match the app's WireRow. */
export interface WireRow {
  kind: "spec" | "part";
  page: number;
  category: string;
  system: string;
  component: string;
  name: string;
  value: string;
  unit: string;
  oemNumber: string;
  crossReferences: string[];
  quantity: number;
  notes: string;
  evidence: string;
}

/** The rows the model must return: strict, so every row parses and every enum is one the app knows. */
export const ROWS_SCHEMA = {
  type: "object",
  properties: {
    rows: {
      type: "array",
      items: {
        type: "object",
        properties: {
          kind: { type: "string", enum: ["spec", "part"] },
          sheet: { type: "integer" },
          category: { type: "string", enum: [...SPEC_CATEGORIES, ""] },
          system: { type: "string", enum: [...PART_SYSTEMS, ""] },
          component: { type: "string" },
          name: { type: "string" },
          value: { type: "string" },
          unit: { type: "string" },
          oem_number: { type: "string" },
          cross_references: { type: "array", items: { type: "string" } },
          quantity: { type: "integer" },
          notes: { type: "string" },
          evidence: { type: "string" },
        },
        required: [
          "kind", "sheet", "category", "system", "component", "name", "value", "unit",
          "oem_number", "cross_references", "quantity", "notes", "evidence",
        ],
        additionalProperties: false,
      },
    },
  },
  required: ["rows"],
  additionalProperties: false,
} as const;

export const SYSTEM_PROMPT =
  `You read pages from maintenance documents for fire apparatus, ambulances and other fleet vehicles (delivery manuals, operator manuals, parts lists, build sheets, lubrication charts, labels) and copy out the specifications and part numbers printed on them, for a fire department's maintenance records. Mechanics service emergency vehicles from these values, and a person checks every row against the page before it is used.

Record only what is printed on the attached pages:
- Never supply values from general knowledge, other vehicles or typical specs. If a page has nothing relevant, return no rows for it.
- Copy numbers, units and part numbers exactly as printed: keep letters, dashes, leading zeros and the spacing inside part numbers. Don't convert units or round.
- When a table gives different values for different configurations (engine options, model years, axle ratings), record each one and put the configuration in notes instead of choosing one.
- If a value is hard to read, record what you can read and say so in notes.

How to fill a row:
- kind "spec" is a value: a capacity, fluid type or grade, torque, pressure, electrical rating or tire size. component is the thing it belongs to (Engine oil, Lug nuts, Front axle, Fire pump); name is the property (Capacity, Type, Torque, Pressure); value is the number or grade; unit is the unit as printed (qt, gal, L, ft-lb, N·m, psi). category is FLUID for fluid types and grades, CAPACITY for amounts, TORQUE, ELECTRICAL, TIRE, or OTHER. Leave system, oem_number and cross_references empty and quantity 0.
- kind "part" is a part number: component is what the part is (Oil filter, Fuel filter, primary); oem_number is the number listed for this vehicle, exactly as printed but without the brand name (LF9009, not Fleetguard LF9009), and the brand goes in notes; cross_references are other brands' equivalents printed on the page, each with its brand ("NAPA 1792"); quantity is how many the vehicle uses, 0 when not stated; system is where it belongs. Leave category, name, value and unit empty.
- evidence is the shortest exact excerpt from the page that shows the value, at most about 15 words.
- sheet is which attached page the row came from: 1 for the first attached page, 2 for the second, and so on. It is not the page number printed on the paper.`;

export class RequestError extends Error {}

/** Checks and normalizes a request body. Throws RequestError with a sentence for the app to show. */
export function parseRequest(body: unknown): ExtractRequest {
  if (typeof body !== "object" || body === null) throw new RequestError("The request isn't JSON.");
  const b = body as Record<string, unknown>;
  const v = (b.vehicle ?? {}) as Record<string, unknown>;
  const text = (x: unknown) => (typeof x === "string" ? x.slice(0, 120) : "");
  const fields: KnownField[] = (Array.isArray(v.fields) ? v.fields : []).slice(0, MAX_FIELDS).flatMap((raw) => {
    const f = raw as Record<string, unknown>;
    const kind = f.kind === "part" ? "part" : f.kind === "spec" ? "spec" : null;
    const component = text(f.component).slice(0, 80).trim();
    return kind && component ? [{ kind, component, name: kind === "spec" ? text(f.name).slice(0, 80).trim() : "" }] : [];
  });
  const vehicle: Vehicle = {
    unit: text(v.unit), type: text(v.type), year: typeof v.year === "number" ? v.year : null,
    make: text(v.make), model: text(v.model), engine: text(v.engine), chassis: text(v.chassis), body: text(v.body), fields,
  };
  const d = b.document as Record<string, unknown> | undefined;
  if (!d) throw new RequestError("No document in the request.");
  const pageNumber = (p: unknown) => typeof p === "number" && Number.isInteger(p) && p >= 1 && p <= 100_000;
  let document: DocumentInput;
  if (d.kind === "pdf") {
    const pages = Array.isArray(d.pages) ? d.pages : [];
    if (pages.length < 1 || pages.length > MAX_PAGES || !pages.every(pageNumber)) {
      throw new RequestError(`Send between 1 and ${MAX_PAGES} pages per request.`);
    }
    if (typeof d.data !== "string" || d.data.length === 0) throw new RequestError("The PDF is missing.");
    if (base64Bytes(d.data) > MAX_PDF_BYTES) throw new RequestError("Those pages are too large; send fewer at a time.");
    document = { kind: "pdf", pages: pages as number[], data: d.data };
  } else if (d.kind === "images") {
    const images = Array.isArray(d.images) ? d.images : [];
    if (images.length < 1 || images.length > MAX_PAGES) throw new RequestError(`Send between 1 and ${MAX_PAGES} pictures per request.`);
    document = {
      kind: "images",
      images: images.map((raw) => {
        const i = raw as Record<string, unknown>;
        if (!pageNumber(i.page)) throw new RequestError("Each picture needs a page number.");
        if (!IMAGE_TYPES.includes(i.mediaType as (typeof IMAGE_TYPES)[number])) throw new RequestError("Pictures must be JPEG or PNG.");
        if (typeof i.data !== "string" || i.data.length === 0) throw new RequestError("A picture is missing.");
        if (base64Bytes(i.data) > MAX_IMAGE_BYTES) throw new RequestError("A picture is over 5 MB.");
        return { page: i.page as number, mediaType: i.mediaType as (typeof IMAGE_TYPES)[number], data: i.data };
      }),
    };
  } else {
    throw new RequestError("The document must be a PDF or pictures.");
  }
  return { vehicle, document, dense: b.dense === true };
}

function base64Bytes(b64: string): number {
  return Math.floor((b64.length * 3) / 4);
}

/** The original page number of each attached sheet, in order. */
export function sheetPages(doc: DocumentInput): number[] {
  return doc.kind === "pdf" ? doc.pages : doc.images.map((i) => i.page);
}

/** The user turn: the pages first, then what they are and which rig they belong to. */
export function buildContent(req: ExtractRequest): Anthropic.Beta.BetaContentBlockParam[] {
  const v = req.vehicle;
  const rig = [v.year?.toString(), v.make, v.model].filter(Boolean).join(" ");
  const facts = [
    `Rig: ${v.unit || "unnamed"} (${v.type || "vehicle"})${rig ? `, ${rig}` : ""}`,
    v.engine && `Engine: ${v.engine}`,
    v.chassis && `Chassis: ${v.chassis}`,
    v.body && `Body builder: ${v.body}`,
  ].filter(Boolean).join("\n");
  const pages = sheetPages(req.document);
  const order = pages.map((p, i) => `sheet ${i + 1} is page ${p}`).join(", ");
  const blocks: Anthropic.Beta.BetaContentBlockParam[] = req.document.kind === "pdf"
    ? [{ type: "document", source: { type: "base64", media_type: "application/pdf", data: req.document.data } }]
    : req.document.images.map((i) => ({ type: "image", source: { type: "base64", media_type: i.mediaType, data: i.data } }));
  const specFields = v.fields.filter((f) => f.kind === "spec").map((f) => `- ${f.component} · ${f.name}`);
  const partFields = v.fields.filter((f) => f.kind === "part").map((f) => `- ${f.component}`);
  const known = specFields.length + partFields.length === 0 ? "" : [
    "\n\nThe rig's profile already has these fields. When a value on these pages is one of them, use exactly that component and name, and put details such as sizes, models or conditions in notes:",
    ...(specFields.length ? ["Specs:", ...specFields] : []),
    ...(partFields.length ? ["Parts:", ...partFields] : []),
  ].join("\n");
  blocks.push({
    type: "text",
    text: `${facts}${known}\n\nThe attached ${req.document.kind === "pdf" ? "PDF holds" : "pictures are"} ${pages.length} ${
      pages.length === 1 ? "page" : "pages"
    } of the document: ${order}.\n\nRecord every specification and part number printed on these pages.`,
  });
  return blocks;
}

interface ModelRow {
  kind: string;
  sheet: number;
  category: string;
  system: string;
  component: string;
  name: string;
  value: string;
  unit: string;
  oem_number: string;
  cross_references: string[];
  quantity: number;
  notes: string;
  evidence: string;
}

/**
 * Model rows to wire rows: sheet becomes the original page, enums the app doesn't know are
 * dropped to blank, and rows without a usable value are left out.
 */
export function toWireRows(parsed: unknown, pages: number[]): WireRow[] {
  const rows = (parsed as { rows?: unknown })?.rows;
  if (!Array.isArray(rows)) return [];
  const clip = (s: unknown, n = 200) => (typeof s === "string" ? s.trim().slice(0, n) : "");
  const out: WireRow[] = [];
  for (const raw of rows as ModelRow[]) {
    const page = pages[(raw?.sheet ?? 0) - 1];
    if (page === undefined) continue;
    const kind = raw.kind === "part" ? "part" : raw.kind === "spec" ? "spec" : null;
    if (!kind) continue;
    const row: WireRow = {
      kind,
      page,
      category: kind === "spec" && (SPEC_CATEGORIES as readonly string[]).includes(raw.category) ? raw.category : "",
      system: kind === "part" && (PART_SYSTEMS as readonly string[]).includes(raw.system) ? raw.system : "",
      component: clip(raw.component, 120),
      name: kind === "spec" ? clip(raw.name, 120) : "",
      value: kind === "spec" ? clip(raw.value, 120) : "",
      unit: kind === "spec" ? clip(raw.unit, 40) : "",
      oemNumber: kind === "part" ? clip(raw.oem_number, 80) : "",
      crossReferences: kind === "part" && Array.isArray(raw.cross_references)
        ? raw.cross_references.map((c) => clip(c, 80)).filter(Boolean).slice(0, 10)
        : [],
      quantity: kind === "part" && Number.isInteger(raw.quantity) && raw.quantity > 0 ? raw.quantity : 0,
      notes: clip(raw.notes, 300),
      evidence: clip(raw.evidence, 300),
    };
    const usable = row.component !== "" &&
      (kind === "spec" ? row.name !== "" && row.value !== "" : row.oemNumber !== "" || row.crossReferences.length > 0);
    if (usable) out.push(row);
  }
  return out;
}

export interface Settings {
  model: string;
  denseModel: string;
  effort: "low" | "medium" | "high" | "xhigh" | "max";
  maxTokens: number;
}

export function settingsFromEnv(env: (name: string) => string | undefined): Settings {
  const effort = env("EXTRACT_EFFORT");
  return {
    model: env("EXTRACT_MODEL") || "claude-sonnet-5-5",
    denseModel: env("EXTRACT_MODEL_DENSE") || "claude-opus-5-5",
    effort: effort === "low" || effort === "medium" || effort === "xhigh" || effort === "max" ? effort : "high",
    maxTokens: Number(env("EXTRACT_MAX_TOKENS")) || 32_000,
  };
}

/** What the function answers: a status and a JSON body the app's ServerExtractionService reads. */
export interface Reply {
  status: number;
  body: Record<string, unknown>;
}

/** The part of the API the function uses, so tests can stand in for it. */
export type CallModel = (params: Anthropic.Beta.MessageCreateParamsStreaming) => Promise<Anthropic.Beta.BetaMessage>;

export async function extract(req: ExtractRequest, settings: Settings, callModel: CallModel): Promise<Reply> {
  const model = req.dense ? settings.denseModel : settings.model;
  let message: Anthropic.Beta.BetaMessage;
  try {
    message = await callModel({
      model,
      max_tokens: settings.maxTokens,
      stream: true,
      // On a safety decline, the API retries on its recommended fallback model instead of refusing.
      betas: ["server-side-fallback-2026-07-01"],
      fallbacks: "default",
      system: SYSTEM_PROMPT,
      output_config: { effort: settings.effort, format: { type: "json_schema", schema: ROWS_SCHEMA } },
      messages: [{ role: "user", content: buildContent(req) }],
    });
  } catch (e) {
    return apiErrorReply(e);
  }
  if (message.stop_reason === "refusal") {
    return { status: 422, body: { error: "The model declined to read these pages.", code: "refused" } };
  }
  if (message.stop_reason === "max_tokens") {
    return { status: 422, body: { error: "Too much on these pages for one pass.", code: "too_much_output" } };
  }
  const text = message.content.flatMap((b) => (b.type === "text" ? [b.text] : [])).join("");
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    return { status: 502, body: { error: "The model's answer wasn't readable. Try again.", code: "bad_output" } };
  }
  return {
    status: 200,
    body: {
      rows: toWireRows(parsed, sheetPages(req.document)),
      model: message.model,
      usage: { input: message.usage.input_tokens, output: message.usage.output_tokens },
    },
  };
}

/** Rate limits and outages are worth retrying (503, 429); a bad key or request isn't. */
export function apiErrorReply(e: unknown): Reply {
  if (e instanceof Anthropic.RateLimitError) {
    return { status: 429, body: { error: "The model service is busy. The import will retry.", code: "rate_limited" } };
  }
  if (e instanceof Anthropic.AuthenticationError || e instanceof Anthropic.PermissionDeniedError) {
    return { status: 500, body: { error: "The server's model API key was rejected.", code: "server_config" } };
  }
  if (e instanceof Anthropic.BadRequestError) {
    return { status: 400, body: { error: `The model service rejected the pages: ${e.message}`, code: "rejected" } };
  }
  if (e instanceof Anthropic.APIError && (e.status ?? 0) >= 500) {
    return { status: 503, body: { error: "The model service is having trouble. The import will retry.", code: "upstream" } };
  }
  if (e instanceof Anthropic.APIConnectionError) {
    return { status: 503, body: { error: "The server couldn't reach the model service.", code: "upstream" } };
  }
  return { status: 500, body: { error: `Extraction failed: ${e instanceof Error ? e.message : String(e)}`, code: "internal" } };
}

/** Equal-time comparison, so the passphrase can't be guessed from response timing. */
export function sameSecret(a: string, b: string): boolean {
  const x = new TextEncoder().encode(a);
  const y = new TextEncoder().encode(b);
  let diff = x.length ^ y.length;
  for (let i = 0; i < Math.max(x.length, y.length); i++) diff |= (x[i] ?? 0) ^ (y[i] ?? 0);
  return diff === 0;
}
