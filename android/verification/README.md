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
- `build-info.json`: exact debug artifact SHA-256, signer fingerprint and build identity. These records describe the previous debug build. Final 3B branding is integrated by the design window and receives separate regression evidence.

Hardware acceptance, system installer interaction on Xiaomi, offline handwriting IME, real Release download, production certificate continuity and public feedback HTTPS remain separate checks.

Public logs use repository-relative paths; private SDK caches, local keys, feedback databases and APK binaries are excluded from Git. Screenshot fixtures contain generated test content.
