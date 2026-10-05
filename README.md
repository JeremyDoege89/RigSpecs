# Rig Specs

Offline-first app for a fire/EMS department: every rig gets a profile with its specs, torque
values, fluids, part numbers and manuals. The plan is in [docs/BUILD_PLAN.md](docs/BUILD_PLAN.md).

## Layout

Kotlin Multiplatform + Compose Multiplatform, one codebase:

- `composeApp/` — shared UI, Room database, repository and domain logic.
  - `commonMain` — everything that isn't platform-specific.
  - `androidMain` / `desktopMain` — database file location, clipboard, desktop `main()`.
  - iOS targets get added here once a Mac is available to build them.
- `androidApp/` — the thin Android shell (AGP 9 keeps the application plugin out of KMP modules).

## Build and run

```sh
./gradlew :androidApp:assembleDebug        # Android APK
./gradlew :composeApp:run                  # desktop app (Windows MSI: :composeApp:packageMsi on Windows)
./gradlew :composeApp:desktopTest          # unit, repository and UI-flow tests (JVM, no device)
```

The UI-flow test saves screenshots to `composeApp/build/screenshots/`.

## Status

Milestone 1 (local skeleton): Room schema for the whole data model, fleet list with search and
type/station filters, add/edit/delete a rig, profile tabs (Overview, Fluids, Torque, Parts, Docs),
one form for specs and parts with a source-document picker, safety-critical specs that require a
source, tap-to-copy, and a change log written on every edit.

Milestone 2 (VIN and templates): NHTSA vPIC decode with a confirm card that fills only blank fields
(custom apparatus such as Pierce, which vPIC returns without a make, falls back to the
manufacturer), the decode stored on the rig and reused as a cache, VIN barcode scanning on Android
(CameraX + bundled ML Kit, works without signal), and rig-type templates that pre-create the usual
blank specs and parts. Tapping a blank goes straight to filling it in.

Sign-in, sync and file attachments arrive in milestone 4; until then everything belongs to one
local department.

`VPIC_LIVE=1 ./gradlew :composeApp:desktopTest --tests '*LiveVpicTest*'` checks the real NHTSA service.
