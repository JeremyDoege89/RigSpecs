# Data sourcing: what's built

Implements the Data Sourcing Feature Spec (Oct 5, 2026), layered on milestone 2. This file
records what was built, the decisions made along the way, and what is still open.

## The trust model (spec step 1): done

- `spec_item` and `part` carry `status` (`VERIFIED` / `UNVERIFIED`), `sourceKind` (`MANUAL`,
  `VPIC`, `OPENLABOR`, `IMPORT`), `sourceUrl`, `verifiedBy`, `verifiedAt`, and a per-row
  `pageHint`. `vehicle` gained `engineSerial`. Schema v3, with an auto-migration that attributes
  every value typed before v3 to whoever last saved it.
- What an editor types or changes is verified by them. An unverified row keeps its badge until
  an editor taps **Confirm** ("Save without confirming" keeps it). Changing an API-sourced value
  turns it into a manual entry and drops the source URL; correcting an imported value keeps the
  page citation.
- The profile shows "N to review" under the header on every tab, rows carry an "Unverified ·
  from …" badge, and fleet cards show a count. Safety-critical rows (brakes, wheels, aerial,
  pump) keep their verify-against-the-manual warning after confirmation.
- Global search (build plan milestone 5) badges unverified hits, and rows imported from CSV land
  unverified with the source "from CSV". Review's "Confirm all I've checked" skips safety-critical
  rows, which are confirmed one at a time.

## Deep links (spec step 2): done

- Settings holds a URL template per portal (Cummins QuickServe, Pierceparts, Freightliner
  Excelerator), department-wide, with `{esn}`, `{job}`, `{vin}`, `{make}`, `{model}`, `{year}`
  placeholders. Only `https://`/`http://` templates are accepted.
- The profile's "Look up in" row shows the portals that fit the rig (Cummins engine, Pierce
  build, Freightliner or Western Star chassis). Tapping one copies the identifier to the
  clipboard and opens the portal in the browser.
- None of the three portals documents a pre-filled search URL, and all three need a login, so
  the defaults are their front pages and the identifier is pasted. If someone finds a working
  search URL in their portal, it goes in Settings; no app update needed.

## Open Labor Project (spec step 3): interface built, adapter waiting

- Built: the `SpecSource` interface (`lookup(vehicle): List<CandidateRow>`) and
  `RigRepository.addSourcedRows`, which writes a source's rows as UNVERIFIED with their source
  URL: filling matching template blanks, skipping values already there, and reporting (not
  writing) values that disagree with what an editor entered.
- Not built: the adapter itself. What's known: a REST API at
  `https://openlaborproject.com/api/v1/…` (`torque-specs`, `fluid-specs`, battery, labor, DTC,
  vehicles), `x-api-key` header, a free tier of 10 requests a day with attribution, paid tiers
  from $49 a month. The response format is behind a login wall, so mapping it waits for a key
  and one real response.
- Check before paying: their tier list puts "local caching rights" on the $149 Business tier.
  Storing their values as rows in our database is local caching, so the $49 tier may not allow
  this feature as specced. Ask them.
- The spec puts the key on the server, so the adapter belongs in an Edge Function next to
  `extract-specs`, with the app calling it.

## Assisted import (spec step 4): built, needs the department's server to run

- Docs tab: add a PDF or picture (or photograph a page on a phone), then **Import specs**. Pick
  the pages (most rigs need only the capacities, torque and parts pages), optionally "Dense
  parts tables" for the more careful model, and start.
- The app sends 3 pages per call (2 dense) as a small PDF cut from the manual, so the model gets
  the text layer and the page image. Photos go as JPEGs. Progress is saved per batch: a job
  stopped by lost signal or a closed app resumes where it stopped. Temporary errors retry twice;
  answers too long for one pass are split.
- Extracted rows are staged, not written to the profile ("nothing goes into the profile
  unreviewed"). The review screen shows each row with its page (tap to see the page), the words
  it was read from, and what confirming does: fill a blank, add a row, or, when the profile
  already has a different value, **Replace** or **Keep profile**. Confirmed rows are VERIFIED,
  `sourceKind = IMPORT`, citing the document and page.
- Re-running a document replaces rows still waiting; confirmed rows are never overwritten,
  rows that repeat a confirmed one are hidden, and rows rejected before stay rejected.
- The review screen also lists the profile's unverified rows (from API sources), so everything
  funnels into one review screen as the spec's flow describes.
- Server: `supabase/functions/extract-specs` (TypeScript, Deno, the official SDK). Strict JSON
  schema output, the faster model for ordinary pages and the more careful one for dense pages
  (both set on the server, per the spec's "decided during the pilot"), server-side fallback on safety
  declines, an optional passphrase so the public project key alone can't run imports. Setup in
  `supabase/README.md`.
- Pilot metric: every confirmed import row records `edited`, and each job counts rows extracted
  and confirmed (kept versus fixed).

## Not verified yet

- A real extraction: it needs the department's Supabase project and model API key. The app ↔
  function contract was tested against the real function code with an invalid key (no charge).
- The camera VIN scan and "photograph a page" on a real phone.
- Release builds on a device.
