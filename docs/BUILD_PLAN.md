# Rig Specs App: Build Plan

Oct 5, 2026 · @Jeremy

## Overview

Build an offline-first Android app where each rig has a profile with its specs, torque values, fluids, part numbers and manuals, and the whole department shares one copy of that data.

- **Users:** firefighters and EMTs who need to look things up fast, plus a few mechanics or officers who keep the data current.
- **Fleet:** fire apparatus (Pierce, E-One and similar), ambulances (box bodies on Ford or Freightliner chassis) and a few passenger cars.
- **The core bet:** no API sells this data for custom apparatus, so the department curates it. The app's job is to make entering it painless and finding it instant.
- **Automation where it exists:** the free NHTSA vPIC API decodes a VIN into make, model, year and engine, and that prefills each new rig.

## Features

The MVP is add a rig, fill its profile, find any spec in two taps, and work with no signal.

**MVP**

- [ ] Add a vehicle by VIN (typed or scanned from the barcode) with vPIC prefill, or add it manually
- [ ] Vehicle profile tabs: Overview, Fluids, Torque, Parts, Docs
- [ ] Global search across every rig ("oil filter Medic 3", "lug nut torque")
- [ ] Rig-type templates that pre-create the usual fields (oil, coolant, DEF, lug nuts, filters, belts, wipers) so mechanics fill blanks instead of starting from scratch
- [ ] Attach PDFs and photos: owner's manuals, build sheets, a photo of the filter box
- [ ] CSV import and export per rig
- [ ] Offline use, with sync when signal returns
- [ ] Roles: viewer and editor

**Later**

- [ ] Copy a profile to a sister rig (same build, new VIN and unit number)
- [ ] Service log and next-service due by miles, engine hours or date
- [ ] Parts inventory per station with low-stock flags
- [ ] Edit history and an approval step for spec changes
- [ ] QR sticker on each rig that opens its profile
- [ ] OCR a manual page or label photo into fields
- [ ] Import from Fleetio or another fleet system if the department uses one
- [ ] Licensed data (MOTOR) to auto-fill passenger cars and chassis

## Tech stack and architecture

Use native Kotlin with Jetpack Compose and Room, which is the stack you already know, and back it with Supabase so the whole department shares one dataset.

- **UI:** Jetpack Compose, Material 3, Navigation Compose
- **Architecture:** MVVM, Hilt for dependency injection, Kotlin coroutines and Flow
- **Local data:** Room with FTS for search, the offline source of truth
- **Network:** Ktor client or Retrofit for vPIC, plus the supabase-kt client
- **Background:** WorkManager for sync and document downloads
- **Camera:** CameraX with ML Kit barcode scanning for the VIN sticker
- **Files:** Android's built-in PdfRenderer, or a PDF viewer library, for manuals

&#91;embedded content: app architecture · phone, backend, VIN decode\]

Screens read only from Room, so the app behaves the same with or without signal; sync runs in the background.

## Windows and iOS

Build all three apps from one Kotlin codebase with Kotlin Multiplatform and Compose Multiplatform. Android, iOS and Windows then share the UI, data layer and sync, and only a thin layer differs per platform.

| Piece | Android plan | Multiplatform swap |
| --- | --- | --- |
| UI | Jetpack Compose | Compose Multiplatform, same code on all three |
| Local database | Room | Room (supports Android, iOS and desktop JVM) |
| Dependency injection | Hilt | Koin (Hilt is Android-only) |
| Network and backend | Retrofit, supabase-kt | Ktor and supabase-kt, both multiplatform |
| Background sync | WorkManager | WorkManager on Android, BGTaskScheduler on iOS, a timer on Windows |
| VIN scan | CameraX with ML Kit | Native camera scan on iOS; Windows types the VIN |
| PDF viewing | PdfRenderer | Each platform's own viewer behind one interface |

- **Roughly 80 to 90% shared code** (estimate). Platform-specific bits go behind `expect`/`actual` interfaces.
- **Windows** ships as an MSI installer from Compose Desktop. It suits the shop or office PC where someone does bulk data entry and CSV imports.
- **iOS** needs a Mac to build and an Apple Developer account (about $99 a year). Distribute through TestFlight or Apple's private custom-app route, not the public App Store.
- **Extra time:** about 2 to 3 weeks on top of the Android MVP (rough guess). The cheapest order is to set up the shared project from day one, ship Android first, then turn on iOS and Windows.
- **Alternative:** if Windows only needs viewing and data entry, a web admin page on the same Supabase backend could replace the Windows app.

## Data model

Use one flexible spec table instead of a column per spec, because every rig is built differently and mechanics will keep finding new things to record. The same tables exist in Room on the phone and in Postgres on the server.

| Table | Key fields | Notes |
| --- | --- | --- |
| Vehicle | id, unitNumber ("Engine 2"), type (engine, ladder, ambulance, car), vin, year, make, model, chassisMake, bodyBuilder, builderJobNumber, engine, station, photo | The job or serial number is what Pierce, E-One and ambulance builders need to look up parts |
| SpecItem | id, vehicleId, category (fluid, torque, capacity, electrical, tire), component, name, value, unit, notes, sourceDocId | "Lug nuts, torque, 450, ft-lb" or "Engine oil, capacity, 24, qt, 15W-40 CK-4" |
| Part | id, vehicleId, system (engine, brakes, pump, body), component, oemNumber, aftermarketNumbers, supplier, qtyPerVehicle, notes, sourceDocId | Cross-references let a crew grab the NAPA equivalent at 2 a.m. |
| Document | id, vehicleId, title, kind (manual, build sheet, invoice, photo), fileUrl, pageHint | Every spec and part can point back to the page it came from |
| Template | id, vehicleType, items | The starter list of fields a new rig gets |
| ChangeLog | id, entityId, field, oldValue, newValue, userId, timestamp | Who changed the torque spec and when |

Every row also carries `updatedAt`, `updatedBy` and a `deleted` flag so sync can merge changes.

## Screens

Six screens cover the MVP. Use big touch targets and a high-contrast dark theme, since people will use it with gloves on in a dim bay.

1. **Fleet list:** rig cards with unit number, type icon and station, filter chips by type and station, and search pinned at the top.
2. **Add vehicle:** scan or type the VIN, show the vPIC result to confirm, then pick the rig type to apply a template.
3. **Vehicle profile:** a header with photo, unit number and VIN, then tabs for Overview, Fluids, Torque, Parts and Docs. Tap any value to copy it.
4. **Edit item:** one form for a spec or a part, with a "source" picker that links a manual page.
5. **Global search:** results grouped by rig, matching specs, parts and part numbers.
6. **Import and settings:** CSV import with a preview and error rows, user management for editors, and sync status.

## Backend, sync, auth and roles

Use Supabase as the backend: Postgres, auth, file storage and row-level security in one service, with a Kotlin client library, and no server code to babysit.

- **Tenancy:** every table carries a `departmentId`. Row-level security limits each user to their own department, so a second department could use the app later without a rewrite.
- **Auth:** email magic link or Google sign-in. An admin invites people and sets each one to viewer or editor.
- **Sync:** Room is the source of truth on the phone. Edits are written locally first, then a WorkManager job pushes them and pulls rows with `updatedAt` newer than the last sync. Conflicts use last write wins per row, and ChangeLog keeps the loser so nothing is silently lost.
- **Files:** manuals and photos go to Supabase Storage. Each rig's documents can be pinned for offline use, because a 300-page PDF shouldn't download on every phone by default.
- **VIN decode:** call vPIC straight from the app (`DecodeVinValues`), no key needed, and cache the result on the vehicle.
- **Cost:** the free tier likely covers one department. The Pro plan is about $25 a month if storage or uptime needs grow (approximate, check current pricing).
- **Distribution:** a private app through managed Google Play, or the Play Console's internal testing track, to start.

## Build milestones

The app is usable on the floor after milestone 3, and each milestone ends with something the crew can test. Durations are rough guesses for one developer working full time.

1. **Local skeleton (about 1 week):** Compose project, Hilt, Room entities from the data model, fleet list, add vehicle by hand, profile tabs with read and edit.
2. **VIN and templates (about 1 week):** vPIC decode, ML Kit barcode scan for the VIN, rig-type templates, tap to copy.
3. **Real data on 3 rigs (about 1 week):** sit with a mechanic, enter one engine, one ambulance and one car by hand, and fix whatever the forms get wrong. This milestone matters more than any code.
4. **Backend and sync (about 2 weeks):** Supabase schema with row-level security, auth and roles, WorkManager sync, file uploads, offline document pinning.
5. **Search and import (about 1 week):** global search over specs and parts (Room FTS), CSV import with preview, CSV export.
6. **Pilot (2 to 4 weeks of use):** one station uses it daily, bugs get fixed, then it rolls out department-wide.

After the pilot, pick from the Later list based on what the crews actually ask for.

## Risks and open questions

The biggest risk is a wrong torque value or part number, so every spec should show its source and who last changed it.

- **Wrong data:** add a "verify against the manual" note on safety-critical specs (brakes, wheels, aerial), require a source document on those, and keep the edit history.
- **Nobody keeps it current:** the app only works if someone owns data entry. Name that person before writing code.
- **Licensing:** don't scrape or copy commercial repair databases into the app. Data from the department's own manuals and build sheets is fine for internal use.
- **Device policy:** department phones and tablets may be locked down, which decides how the app gets installed.

**Questions for the department**

- [ ] Who will enter and maintain the data?
- [ ] Do they already use fleet software (Fleetio, FASTER, AssetWorks) or a spreadsheet?
- [ ] Do they have the delivery manuals and build sheets for each apparatus and ambulance?
- [ ] How many rigs of each type, and how many stations?
- [ ] Department phones and tablets, personal phones, or both?
