import Anthropic from "@anthropic-ai/sdk";
import { assert, assertEquals, assertMatch, assertThrows } from "@std/assert";
import {
  apiErrorReply,
  buildContent,
  type CallModel,
  extract,
  parseRequest,
  RequestError,
  ROWS_SCHEMA,
  sameSecret,
  settingsFromEnv,
  toWireRows,
} from "./extract.ts";
import { handle } from "./index.ts";

const vehicle = { unit: "Engine 2", type: "Engine", year: 2019, make: "Pierce", model: "Enforcer", engine: "Cummins X12", chassis: "Pierce", body: "" };
const pdfRequest = { vehicle, document: { kind: "pdf", pages: [212, 213], data: "JVBERi0xLjQK" }, dense: false };
const settings = settingsFromEnv(() => undefined);

/** A finished message the way the API returns one, with [text] as the structured output. */
function message(text: string, stop: Anthropic.Beta.BetaMessage["stop_reason"] = "end_turn"): Anthropic.Beta.BetaMessage {
  return {
    id: "msg_1", type: "message", role: "assistant", model: "claude-sonnet-5-5", stop_reason: stop, stop_sequence: null,
    content: [{ type: "text", text, citations: null }],
    usage: { input_tokens: 1200, output_tokens: 300 },
  } as unknown as Anthropic.Beta.BetaMessage;
}

const modelRows = {
  rows: [
    { kind: "spec", sheet: 1, category: "TORQUE", system: "", component: "Lug nuts", name: "Torque", value: "450", unit: "ft-lb", oem_number: "", cross_references: [], quantity: 0, notes: "", evidence: "Wheel nuts 450 ft-lb" },
    { kind: "part", sheet: 2, category: "", system: "ENGINE", component: "Oil filter", name: "", value: "", unit: "", oem_number: "LF9009", cross_references: ["NAPA 1792", " "], quantity: 1, notes: "", evidence: "LF9009" },
    { kind: "spec", sheet: 3, category: "FLUID", system: "", component: "Ghost page", name: "Type", value: "x", unit: "", oem_number: "", cross_references: [], quantity: 0, notes: "", evidence: "" },
    { kind: "spec", sheet: 1, category: "MADE_UP", system: "", component: "Coolant", name: "Type", value: "ELC", unit: "", oem_number: "", cross_references: [], quantity: 0, notes: "", evidence: "" },
    { kind: "part", sheet: 1, category: "", system: "PUMP", component: "Seal kit", name: "", value: "", unit: "", oem_number: "", cross_references: [], quantity: 0, notes: "no number printed", evidence: "" },
  ],
};

Deno.test("requests are validated and normalized", () => {
  const ok = parseRequest(pdfRequest);
  assertEquals(ok.document.kind, "pdf");
  assertEquals(ok.vehicle.make, "Pierce");
  assertThrows(() => parseRequest({ vehicle, document: { kind: "pdf", pages: [], data: "x" } }), RequestError);
  assertThrows(() => parseRequest({ vehicle, document: { kind: "pdf", pages: [1, 2, 3, 4, 5, 6, 7], data: "x" } }), RequestError);
  assertThrows(() => parseRequest({ vehicle, document: { kind: "pdf", pages: [0], data: "x" } }), RequestError);
  assertThrows(() => parseRequest({ vehicle, document: { kind: "images", images: [{ page: 1, mediaType: "image/gif", data: "x" }] } }), RequestError);
  assertThrows(() => parseRequest({ vehicle, document: { kind: "word" } }), RequestError);
  assertThrows(() => parseRequest("nope"), RequestError);
  const big = "A".repeat(13 * 1024 * 1024 * 4 / 3);
  assertThrows(() => parseRequest({ vehicle, document: { kind: "pdf", pages: [1], data: big } }), RequestError);
});

Deno.test("the pages come first, then what they are", () => {
  const blocks = buildContent(parseRequest(pdfRequest));
  assertEquals(blocks[0].type, "document");
  const text = blocks[1] as Anthropic.Beta.BetaTextBlockParam;
  assertMatch(text.text, /sheet 1 is page 212, sheet 2 is page 213/);
  assertMatch(text.text, /Engine 2 \(Engine\), 2019 Pierce Enforcer/);
  assertMatch(text.text, /Engine: Cummins X12/);

  const photo = buildContent(parseRequest({ vehicle, document: { kind: "images", images: [{ page: 1, mediaType: "image/jpeg", data: "/9j/" }] } }));
  assertEquals(photo[0].type, "image");
  assertMatch((photo[1] as Anthropic.Beta.BetaTextBlockParam).text, /pictures are 1 page of the document: sheet 1 is page 1/);
});

Deno.test("model rows become wire rows on their real pages", () => {
  const rows = toWireRows(modelRows, [212, 213]);
  assertEquals(rows.map((r) => [r.component, r.page]), [["Lug nuts", 212], ["Oil filter", 213], ["Coolant", 212]]);
  assertEquals(rows[1].crossReferences, ["NAPA 1792"]);
  assertEquals(rows[1].quantity, 1);
  assertEquals(rows[2].category, "");
  assertEquals(toWireRows({ nope: 1 }, [1]), []);
});

Deno.test("the request asks for strict JSON, the right model, and the refusal fallback", async () => {
  let seen: Anthropic.Beta.MessageCreateParamsStreaming | undefined;
  const call: CallModel = (p) => {
    seen = p;
    return Promise.resolve(message(JSON.stringify(modelRows)));
  };
  const r = await extract(parseRequest(pdfRequest), settings, call);
  assertEquals(r.status, 200);
  assertEquals((r.body.rows as unknown[]).length, 3);
  assertEquals(seen?.model, "claude-sonnet-5-5");
  assertEquals(seen?.fallbacks, "default");
  assertEquals(seen?.betas, ["server-side-fallback-2026-07-01"]);
  assertEquals(seen?.output_config?.effort, "high");
  assertEquals((seen?.output_config?.format as { schema: unknown }).schema, ROWS_SCHEMA);

  await extract(parseRequest({ ...pdfRequest, dense: true }), settings, call);
  assertEquals(seen?.model, "claude-opus-5-5");
});

Deno.test("stops and errors map to what the app can act on", async () => {
  const req = parseRequest(pdfRequest);
  assertEquals((await extract(req, settings, () => Promise.resolve(message("", "refusal")))).body.code, "refused");
  const long = await extract(req, settings, () => Promise.resolve(message("{\"rows\":[", "max_tokens")));
  assertEquals([long.status, long.body.code], [422, "too_much_output"]);
  assertEquals((await extract(req, settings, () => Promise.resolve(message("not json")))).status, 502);

  const busy = new Anthropic.RateLimitError(429, {}, "rate limited", new Headers());
  assertEquals(apiErrorReply(busy).status, 429);
  const down = new Anthropic.InternalServerError(529, {}, "overloaded", new Headers());
  assertEquals(apiErrorReply(down).status, 503);
  const badKey = new Anthropic.AuthenticationError(401, {}, "bad key", new Headers());
  assertEquals(apiErrorReply(badKey).body.code, "server_config");
  assertEquals(apiErrorReply(new Anthropic.APIConnectionError({ message: "dns" })).status, 503);
  assertEquals((await extract(req, settings, () => Promise.reject(busy))).status, 429);
});

Deno.test("settings come from the environment with safe defaults", () => {
  const env: Record<string, string> = { EXTRACT_MODEL: "claude-haiku-4-5", EXTRACT_EFFORT: "medium", EXTRACT_MAX_TOKENS: "8000" };
  const s = settingsFromEnv((k) => env[k]);
  assertEquals([s.model, s.denseModel, s.effort, s.maxTokens], ["claude-haiku-4-5", "claude-opus-5-5", "medium", 8000]);
  assertEquals(settingsFromEnv((k) => (k === "EXTRACT_EFFORT" ? "turbo" : undefined)).effort, "high");
});

Deno.test("the HTTP handler: method, passphrase, ping and missing key", async () => {
  const post = (body: unknown, headers: Record<string, string> = { "x-rigspecs-secret": "hose" }) =>
    new Request("http://localhost/extract-specs", { method: "POST", body: JSON.stringify(body), headers });
  const fake: CallModel = () => Promise.resolve(message(JSON.stringify(modelRows)));
  const env = (vars: Record<string, string>) => (k: string) => vars[k];
  const locked = env({ IMPORT_SHARED_SECRET: "hose" });

  assertEquals((await handle(new Request("http://localhost/", { method: "GET" }), locked, fake)).status, 405);

  // No passphrase configured: the function refuses everything rather than running open.
  const open = await handle(post({ ping: true }), env({}), fake);
  assertEquals([open.status, (await open.json()).code], [500, "server_config"]);

  assertEquals((await handle(post({ ping: true }, {}), locked, fake)).status, 401);
  assertEquals((await handle(post({ ping: true }, { "x-rigspecs-secret": "hosE" }), locked, fake)).status, 401);
  const unlocked = await handle(post({ ping: true }), locked, fake);
  assertEquals(await unlocked.json(), { ok: true, message: "Ready: claude-sonnet-5-5, dense pages claude-opus-5-5" });

  assertEquals((await (await handle(post({ ping: true }), locked, null)).json()).ok, false);
  const noKey = await handle(post(pdfRequest), locked, null);
  assertEquals([noKey.status, (await noKey.json()).code], [500, "server_config"]);

  const bad = await handle(post({ vehicle, document: { kind: "pdf", pages: [] } }), locked, fake);
  assertEquals(bad.status, 400);
  const good = await handle(post(pdfRequest), locked, fake);
  assertEquals(good.status, 200);
  assert(((await good.json()).rows as unknown[]).length === 3);
});

Deno.test("passphrase comparison", () => {
  assert(sameSecret("hose", "hose"));
  assert(!sameSecret("hose", "hosE"));
  assert(!sameSecret("hose", "hose2"));
  assert(!sameSecret("", "hose"));
});
