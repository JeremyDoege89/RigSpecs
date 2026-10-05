# Server: assisted import

`functions/extract-specs` is the one piece of server code assisted import needs. The app sends it
a few pages of a manual (as a small PDF, so the text layer comes along) or a photo; it asks the
extraction model for the specs and part numbers printed on them and returns rows. The app stages
those rows for review: nothing reaches a rig's profile until an editor confirms it.

The model API key is a secret on the function and never reaches a phone. The function keeps no
data: it holds nothing between calls.

## Set it up (once per department)

1. Create a Supabase project (the free tier is enough to start) and note its project ref, the
   `<ref>` in `https://<ref>.supabase.co`.
2. Install the Supabase CLI and sign in: `supabase login`.
3. From this repository's root, link the project: `supabase link --project-ref <ref>`.
   If the CLI asks for a config file, run `supabase init` first and keep the `functions` folder.
4. Set the secrets:

   ```sh
   supabase secrets set ANTHROPIC_API_KEY=<model API key> IMPORT_SHARED_SECRET=<a passphrase you choose>
   ```

   `IMPORT_SHARED_SECRET` is required: the function refuses every request until it's set. It is
   the function's lock, because the function is deployed without Supabase's JWT check (new
   projects' public "publishable" keys aren't JWTs, and the gateway only verifies JWTs).
5. Deploy (no Docker needed): `supabase functions deploy extract-specs --no-verify-jwt --use-api`.
6. On each device that imports: Settings → Assisted import. Enter `https://<ref>.supabase.co`,
   the project's public key (**publishable** or **anon**, under Project Settings → API keys) and the
   passphrase, then tap **Check connection**. It should say `Ready`.

## Optional settings

Set with `supabase secrets set NAME=value`:

| Name | Default | What it does |
| --- | --- | --- |
| `EXTRACT_MODEL` | `claude-sonnet-5-5` | Model for ordinary pages |
| `EXTRACT_MODEL_DENSE` | `claude-opus-5-5` | Model when "Dense parts tables" is on |
| `EXTRACT_EFFORT` | `high` | `low`, `medium`, `high`, `xhigh` or `max` |
| `EXTRACT_MAX_TOKENS` | `32000` | Longest answer per call; the app splits pages that need more |

Which model to use for which pages is meant to be settled in the pilot: measure how many rows the
mechanic kept versus fixed (the app records `edited` on every confirmed row).

## How a call works

- The app sends at most 3 pages per call (2 for dense tables) and halves that when an answer would
  be too long, so each call finishes well inside the function's time limit.
- The answer is constrained to a JSON schema, so every row parses and every category or system is
  one the app knows. Each row cites its page and quotes the words it was read from.
- On a safety decline the API retries on its recommended fallback model (`fallbacks: "default"`);
  a request that's still declined comes back as "declined" and the app stops that import.
- Errors tell the app what to do: 429 and 503 are retried, `server_config` (a wrong key or
  passphrase, or none set) and `refused` are not.

## Develop and test

With [Deno](https://deno.com) installed:

```sh
cd supabase/functions/extract-specs
deno task check      # type-check against the SDK
deno task test       # unit tests; no network, no API key

# Run it locally; the app (or curl) can talk to http://localhost:8000
ANTHROPIC_API_KEY=... IMPORT_SHARED_SECRET=hose deno run --allow-net --allow-env --allow-read index.ts
```

`RIGSPECS_FN_URL=http://localhost:8000 RIGSPECS_FN_SECRET=hose ./gradlew :composeApp:desktopTest --tests '*LiveFunctionTest*'`
checks that the app and a running function agree (it expects an invalid model key, so it costs nothing).
