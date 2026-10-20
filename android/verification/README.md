# Verification evidence

All Android evidence here is from a local API 36 ARM64 emulator configured as a tablet at density 240. It is not Xiaomi Pad or stylus hardware evidence.

- `instrumentation-api36.txt`: 16 tests covering generated arbitrary PDF rendering/text, encrypted/corrupt/cancelled inputs, vector text retention, original + user page rotation, rendered image/ink coordinates, persistent library/undo/copy/trash/seed deletion, 96,000-point snapshots, complete dictionaries and spelling candidates, backup restore/path traversal rejection, atomic failure, synthetic pressure/palm/cancel/eraser behavior, signed APK validation and tamper rejection.
- `design-instrumentation-api36.txt`: corrected build regression suite, including native controls measured against `reference-layout.json` from the actual design source.
- `design-library-blue.png`, `design-settings-blue.png`, `design-feedback-blue.png` and portrait equivalents: corrected native UI in the same theme as the user reference. Older `library-landscape.png` etc. are retained functional-test history and are **not design acceptance images**.
- `design-upgrade-data-retention.json`: actual upgrade from the original 0.1.0-dev build to the corrected 0.1.1-dev artifact; all 29 private library/database files retained identical hashes.
- `upgrade-data-retention.json`: earlier actual `adb install -r` from versionCode 1 to same-signed versionCode 2; all 51 private library/database files retained identical hashes. Version 2 is a local fixture, not a published release.
- `feedback-gunicorn.json`: actual temporary loopback Gunicorn 25.3.0, two workers; 201 receipt then idempotent 200 receipt. No public HTTPS endpoint was deployed.
- `lint-summary.txt`: Android lint with 0 errors; warnings remain for KTX suggestions, custom View constructors, explicit left/right placement, draw-time allocations, SharedPreferences commit used for durable drafts, dependency update suggestions and unused third-party TLS helper classes. Network code uses the platform's default HTTPS trust and hostname verification.
- PNGs show actual native emulator UI: first launch, system-picked imported PDF, landscape/portrait reader/library and a complete offline dictionary entry. The imported test PDF is a generated fixture with a rotated first page, not the sample paper.
- `build-info-0.1.1-dev.json`, `build-info-0.1.2-dev.json`: previous debug artifact identities; `build-info-0.1.3-dev.json` records the prior reader build; `build-info.json` records the current 0.1.4-dev artifact. Final 3B branding has its original integration evidence below.

Hardware acceptance, system installer interaction on Xiaomi, offline handwriting IME, stable Release installation, production certificate continuity and public feedback HTTPS remain separate checks.

Public logs use repository-relative paths; private SDK caches, local keys, feedback databases and APK binaries are excluded from Git. Screenshot fixtures contain generated test content.

## 0.1.2-dev integration

- `integration-instrumentation-api36.txt`: all 19 tests passed, including three first-launch/retry/deletion/guide-only upgrade tests and the staged same-key v4 APK signature fixture.
- `integration-upgrade.json`: an actual `install -r` from 0.1.1-dev (2) to 0.1.2-dev (3) preserved all 8 existing emulator fixture file hashes before first new-version launch. This is a different sample from the earlier 29-file check.
- `integration-library-*.png` and `integration-paper.png`: final branding, two bundled initial documents and actual paper rendering in airplane mode. These do not establish Xiaomi hardware acceptance.
- `integration-build.json`: historical 0.1.2-dev artifact identity, source hashes and validation limits. APK binaries remain local; no production Release exists.

## 0.1.3-dev reader and lifecycle

- `reader-polish-build.json`: final versionCode 4 debug APK identity and source hashes; incremental build, not a clean build. Same development certificate as before. The APK remains local in ignored `android/dist/`.
- `reader-polish-instrumentation-api36.txt`: all 24 tests passed with no skips. Includes 12 paper/background combinations, ten pages and 100,000 persisted points, streaming library metadata, complete backup restore, real cover orientation/white margins, bottom chrome and attached reader eviction/rebuild. The same-key v5 signature fixture was staged for both update tests.
- `reader-polish-flow.json`: real offline UI creation and drawn ink, background/foreground, cold process restart, system SAF PDF save, sample deletion, actual same-key v4 to v5 upgrade, and emulator reboot. Saved note state remained equal and samples stayed in Trash. The v5 fixture is not a Release. The OS rejected the manual trim request due to process state; the attached Activity regression separately verifies actual trim callbacks.
- `reader-polish-export.pdf`: actual system-saved black/grid test note, independently reopened and rendered. It contains generated test ink only.
- `reader-polish-library-landscape.png`, `reader-polish-library-portrait.png`: fresh library containing exactly paper and landscape guide, genuine first-page covers, all-black homepage wordmark.
- `reader-polish-paper.png`, `reader-polish-guide.png`: actual final reader, no app page label or themed navigation band at the bottom; navigation bars hidden by default. Page numbers printed within a source PDF remain part of the document.
- `reader-polish-paper-chooser.png`, `reader-polish-black-grid.png`: real paper selection and resulting grid/black note.
- `reader-polish-lint-summary.txt`: 0 errors / 54 warnings, repository-relative paths. Local feedback service tests also passed (5); this does not establish public HTTPS availability.

The attached reader's page cache held 10,141,600 bytes and word cache an estimated 30,760 bytes, both zero after trim and rebuilt on resume. Short UI-flow PSS samples were 135,094 KiB foreground and 135,496 KiB background: cache release does not prove an immediate fall in total process PSS. Xiaomi hardware, long-running large-file pressure and production update installation remain pending; see [acceptance notes](../../docs/READER-ACCEPTANCE.md).

## 0.1.4-dev English UI and delivery

- `english-build.json`: final development APK identity and production-source hashes; same debug certificate as prior builds, not production signed.
- `english-instrumentation-api36.txt`: 28 tests passed, no skips. Includes the higher same-key v6 fixture, English language/draft persistence, 30 measured text-layout cases and six rendered selected-theme outline cases.
- `english-border-before.txt` / `english-border-after.txt`: the rendered-pixel regression fails on the original clipped right outline and passes after containing the outline within the preview bounds.
- `english-*.png`: actual final landscape/portrait English screens, tools/dictionary/error and Chinese update entry. Document names and the authored Chinese guide retain their original languages.
- `english-lint-summary.txt`: 0 errors / 54 warnings.
- The **602-second / 316-cycle / 31 Activity-recreation** candidate-build record moved to [log](../../log/2026-10-21-emulator-soak/README.md), including raw output, CSV, PNG/SVG curves and tested artifact/source hashes. Subsequent SettingsContent outline/tagline edits change the final APK hash; reader/storage/cache code is unchanged. This is not a second final-artifact soak or Xiaomi hardware evidence.

See [acceptance and download checks](../../docs/ENGLISH-APK-ACCEPTANCE.md) for what the stable updater does and what remains unverified.

- `english-apk-local-check.json`: final artifact loopback transfer/archive/assets/signature/install/cold-start check.
- `english-apk-public-check.json`: anonymous public GitHub APK download of the same SHA-256, then installation and successful cold launch of that downloaded copy. Single debug-emulator `am start -W` time is an operational observation, not a performance benchmark.
- `english-release-public.json`: uploaded development Pre-release assets, GitHub digests, public checksum match and actual stable-latest 404. Production signing and real higher-version stable in-app installation remain pending.
- `english-update-live.json` / `english-update-no-stable.png`: actual in-app GitHub check after publication shows the English no-stable-release error; this does not test a production installer upgrade.
