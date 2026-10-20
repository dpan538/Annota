# Annota Android development build

Native Kotlin application ID: `io.github.dpan538.annota`. Minimum Android 10 / API 29, compile / target API 36. Debug version 0.1.4-dev (5). The earlier 0.1.0-dev build used a simplified functional-test interface and did not meet the approved design. This folder contains the native implementation; it does not publish GitHub Releases or change the design sources.

## Build

Install a **full JDK 17 or 21**, Android SDK platform 36 and build-tools 35.0.0. Set `JAVA_HOME` and `ANDROID_HOME`, or create untracked `local.properties` with `sdk.dir=/absolute/sdk/path`.

```sh
./gradlew assembleDebug assembleDebugAndroidTest lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class io.github.dpan538.annota.CoreTest,io.github.dpan538.annota.DesignUiTest,io.github.dpan538.annota.EnglishUiTest,io.github.dpan538.annota.InputTest,io.github.dpan538.annota.ReaderPolishTest,io.github.dpan538.annota.RenderLifecycleTest,io.github.dpan538.annota.SeedTest,io.github.dpan538.annota.UpdateTest io.github.dpan538.annota.test/androidx.test.runner.AndroidJUnitRunner
```

The two `UpdateTest` cases require a local same-signed APK built with `-PannotaVersionCode=6 -PannotaVersionName=0.1.5-update-fixture`, staged in the target application's `cache/update-test.apk` (push to `/data/local/tmp/` then copy using `adb shell run-as io.github.dpan538.annota`). Keep the installed target at versionCode 5. Without the fixture these two tests explicitly skip. Run instrumentation only on a disposable emulator because storage fixtures reset its library.

The checked-in wrapper pins Gradle 8.13 and verifies its distribution SHA-256. `.tooling/` holds only ignored local downloads and SDK/emulator files. No SDK, build cache or private key should be committed. `scripts/sync-assets.py` can re-copy the dictionaries from the read-only `../design/dictionary/` source. The actual database SHA-256 values are checked again at installation. The original authored five-page guide is bundled as `assets/guide.pdf`; the owner-supplied original paper is bundled as a second initial document with an independent rights notice.

## Storage and offline behavior

The library uses private SQLite transactions. Immutable source PDFs/images and compressed, content-addressed annotation snapshots are committed with AtomicFile before database references change. A bounded 50-action history survives restart; unreferenced state snapshots are collected periodically and at startup. Library progress, favorites, folders and trash live in the private database. Source files are immutable and safely shared by duplicates. Deleting an application or clearing its data removes these files. Android cloud backup and device transfer are disabled.

Settings → Export backup writes `.annota.zip` with complete current library documents, folders, page order, rotation, ink, images and SHA-256. Undo history, theme preferences and feedback contact drafts are not included. Restore verifies assets and merges with new IDs; it does not overwrite existing documents. The SAF provider is responsible for the final destination; interrupted output is deleted where its provider supports deletion. This is not a cloud backup.

PDF rendering uses Android PdfRenderer. PDFBox-Android extracts text and composes exports while retaining source vector pages/text. New paper backgrounds are rendered locally; ink/images are overlaid into the export. Scanned pages need OCR outside this version. A source PDF is capped at 512 MiB / 10,000 pages; render bitmaps are capped to 2,500 pixels. These limits do not guarantee every PDF feature, font or color space. PDF parsing is cancellable between pages and operations; a currently running native render or PDFBox parse must return before cancellation can complete.

The dictionaries are ECDICT core (57,841 entries) and CC-CEDICT (125,301 entries), not sentence translation. SQLite queries run in a worker with bound arguments and stale-request isolation. Source data and license texts are included offline. No remote font, analytics, login, sync or automatic update checks are used.

## Controls and hardware

The library and full four-tab settings screen now use the approved design coordinate system, native MiSans 400/500 fonts, original Lucide icons, navigation selection, action-card subtitles and thumbnail/title/date structure. Source provenance is recorded in `verification/design-source.json`. The two UI regression tests compare native control rectangles to measurements from the original HTML, for landscape/portrait and Pine/Blue Note themes. The native reader retains the four palettes, three groups of 13 compact top icons, adjacent two-page spreads and page thumbnails. More includes Undo/Redo, page movement, fit, export/share and document information. Long press a thumbnail for page copy/rotation/template/clear/delete/reorder. Drawing tools support pen/pencil/fountain/highlighter, whole/local eraser, shapes, system image selection and a draggable/rotatable edge-snapping ruler. The view consumes standard Android pressure/history/cancel and stylus-button events. Synthetic MotionEvent tests are adapter regression tests, **not hardware validation**. Launcher and library branding are generated from the approved, centered 3B wordmark: run `python3 scripts/prepare-branding.py`.

Settings → Stylus and device reports only observed system inputs and real device ranges. Xiaomi proprietary squeeze, double tap, slide and pen vibration remain disabled pending official interfaces and target-device testing. Android text fields request the installed system handwriting IME; offline behavior must be tested in airplane mode on the actual tablet. The unmodified official MiSans variable TTF is bundled for native UI; `scripts/prepare-design-assets.py /path/to/MiSans.zip` copies the original files and records their hashes without font conversion. Some file-operation/permission dialogs remain standard Android controls. Device-specific font scaling and system insets still need target-device review.

## Feedback

The endpoint is empty by default. Configure a real deployed HTTPS service with `-PfeedbackEndpoint=https://YOUR_HOST/v1/feedback` after validating `../feedback-service/`. Feedback is sent only on Send, with message, optional contact and an idempotency request ID. Drafts remain unless a genuine accepted receipt is returned. No documents/screenshots/logs are attached. No GitHub token is embedded.

## Signing and GitHub updates

Debug APKs have an automatically created local debug certificate. Preserve the local debug keystore only for development continuity; a different machine normally produces a different key, so it cannot overwrite this install. Do not claim it is a production signature. A later production signature cannot replace a debug install in place: export a local backup first, install the production app after removing the development app, then restore.

Production signing uses only environment variables:

```sh
export ANNOTA_KEYSTORE=/secure/path/annota-release.jks
export ANNOTA_STORE_PASSWORD=...
export ANNOTA_KEY_ALIAS=...
export ANNOTA_KEY_PASSWORD=...
./gradlew assembleRelease -PannotaVersionCode=5 -PannotaVersionName=0.1.4
python3 scripts/make-update-manifest.py app/build/outputs/apk/release/app-release.apk \
  --build-tools "$ANDROID_HOME/build-tools/35.0.0" --output annota-update.json
```

No production keystore is generated or checked in. Without the environment variables `assembleRelease` produces an unsigned artifact, which must not be distributed as installable. Keep the production key and an offline backup permanently; future updates must use it.

A real stable Release at `dpan538/Annota` must include the signed APK **and** the generated `annota-update.json`. App check reads the latest stable Release only on user request, shows notes, and waits for a download action. Download permits only HTTPS GitHub asset hosts, enforces size and cancellation, checks SHA-256, cryptographic APK signature (apksig), exact package/version and the currently installed signer before asking the Android installer. Install permission refusal leaves the verified file available for retry. A debug APK can upgrade only to the same debug signing identity. No Release is created by this project.

404, rate limit, offline/network error, cancellation, hash/signature mismatch and installation permission states have explicit messages. GitHub release availability and an actual production installer handoff require a real Release/signing key; local fixture tests do not establish publication.

See `../docs/IMPLEMENTATION-STATUS.md` for actual build/test evidence, artifact paths/hashes and target-device acceptance work.

## Onboarding and regression fixtures

Current development build: `0.1.4-dev` / versionCode 5. Both seed PDFs are bundled and verified by SHA-256 and page count. They publish together in a database transaction. Any earlier initialization marker is final, including the old guide-only development build. No sample is added back on restart or upgrade, even after removal. Paper rights are separate: see `app/src/main/assets/SAMPLE-PAPER-NOTICE.md`; redistribution clearance remains a public-release checklist item.

The update tests require a same-development-key versionCode 6 / versionName `0.1.5-update-fixture` APK at the target app's `cache/update-test.apk`. Build it with Gradle property overrides, keep it outside Git and stage it with `adb shell run-as io.github.dpan538.annota ...` on the dedicated emulator. Without it, those two tests are explicitly skipped; do not report them as passed. No fixture is a published update. Test APKs reset library data and must never run on a user's tablet.

## Reader design, lifecycle and memory acceptance

`../docs/READER-ACCEPTANCE.md` records the genuine first-page covers, landscape guide, top-only reader controls, all-black home wordmark, lazy library metadata, heap-based bounded caches and background invalidation. `scripts/verify-reader-flow.py` is a destructive, emulator-only real UI/lifecycle/upgrade check. It requires `--reset-test-library` and a same-key higher-version local fixture. Never point it at personal data.

## English UI and APK checks

The language switch is runtime state in the same APK, not a second binary. `EnglishUiTest` measures English home/settings text for landscape and portrait at button sizes 60/68/76, checks all four sides of the rendered selected-theme outline, then exercises the real language toggle, cold Activity reopen, paper/tool menus and feedback failure/draft retention. Document names, the authored Chinese guide and dictionary content retain their original language.

Run `SoakTest` separately on the dedicated emulator (it resets the test database):

```sh
adb shell am instrument -w -e class io.github.dpan538.annota.SoakTest -e soakSeconds 600 \
  io.github.dpan538.annota.test/androidx.test.runner.AndroidJUnitRunner
adb exec-out run-as io.github.dpan538.annota cat cache/english-soak.json > english-soak.json
```

`SoakTest` is excluded from the short suite above. Its report describes emulator/debug behavior, not Xiaomi hardware or an hours-long device session. `scripts/verify-apk.py` checks a real download copy, ZIP/assets, APK signature, install and cold launch. Without `--download-url` it uses loopback HTTP and explicitly does not claim public GitHub validation. Use a real published Annota asset URL to test anonymous HTTPS delivery.

The recorded 602-second / 316-cycle run, original output, build hashes, CSV and memory curves live in [log](../log/2026-10-21-emulator-soak/README.md).
