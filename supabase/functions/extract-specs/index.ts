// extract-specs: the app sends a few pages of a manual (or a photo); this function asks the
// extraction model for the specs and part numbers on them and returns rows for review.
// The model API key lives here as a secret and never reaches a phone.

import Anthropic from "@anthropic-ai/sdk";
import { type CallModel, extract, parseRequest, type Reply, RequestError, sameSecret, settingsFromEnv } from "./extract.ts";

export async function handle(request: Request, env: (name: string) => string | undefined, callModel: CallModel | null): Promise<Response> {
  if (request.method !== "POST") return reply({ status: 405, body: { error: "Use POST." } });

  // The function runs without the platform's JWT check (new projects' public keys aren't JWTs),
  // so the passphrase is the lock: without one set, it refuses everything.
  const secret = env("IMPORT_SHARED_SECRET");
  if (!secret) {
    return reply({ status: 500, body: { error: "The server's import passphrase isn't set (IMPORT_SHARED_SECRET).", code: "server_config" } });
  }
  if (!sameSecret(request.headers.get("x-rigspecs-secret") ?? "", secret)) {
    return reply({ status: 401, body: { error: "Wrong import passphrase. Check Settings on the device.", code: "server_config" } });
  }

  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return reply({ status: 400, body: { error: "The request isn't JSON." } });
  }

  const settings = settingsFromEnv(env);
  if ((body as { ping?: unknown })?.ping === true) {
    return callModel
      ? reply({ status: 200, body: { ok: true, message: `Ready: ${settings.model}, dense pages ${settings.denseModel}` } })
      : reply({ status: 200, body: { ok: false, message: "The model API key isn't set on the server." } });
  }
  if (!callModel) {
    return reply({ status: 500, body: { error: "The model API key isn't set on the server.", code: "server_config" } });
  }

  try {
    return reply(await extract(parseRequest(body), settings, callModel));
  } catch (e) {
    if (e instanceof RequestError) return reply({ status: 400, body: { error: e.message } });
    throw e;
  }
}

function reply(r: Reply): Response {
  return new Response(JSON.stringify(r.body), { status: r.status, headers: { "Content-Type": "application/json" } });
}

/** Streams the answer (dense pages can be long) and hands back the finished message. */
function modelCaller(apiKey: string | undefined): CallModel | null {
  if (!apiKey) return null;
  const client = new Anthropic({ apiKey, maxRetries: 1 });
  return async (params) => {
    const { stream: _, ...rest } = params;
    return await client.beta.messages.stream(rest).finalMessage();
  };
}

if (import.meta.main) {
  const callModel = modelCaller(Deno.env.get("ANTHROPIC_API_KEY"));
  Deno.serve((request) => handle(request, (name) => Deno.env.get(name), callModel));
}
