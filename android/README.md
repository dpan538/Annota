# Annota Android

Native Kotlin application ID: `io.github.dpan538.annota`. Minimum Android 10 / API 29, compile / target API 36. Current stable version 1.0.2 (13); 1.0.0 was the first stable release. The earlier 0.1.0-dev build used a simplified functional-test interface and did not meet the approved design. This folder contains the native implementation; it does not publish GitHub Releases or change the design sources.

Version 1.0.2 (13) adds handwritten signatures and signed/restricted PDF compatibility. More → Handwritten signature captures transparent ink, then allows moving and proportional resizing before a single undoable insertion. Restricted PDFs open read-only; original signed bytes are retained, including when a private unlocked viewing copy is needed. Unchanged exports preserve the original bytes. Annotated derivatives preserve visible form/signature appearances and explicitly do not preserve original digital-signature validation. No certificate signing/trust verification is implemented. See [signature acceptance](../docs/PDF-SIGNATURE-ACCEPTANCE.md). Run `PdfSignatureTest` alongside the PDF/input regressions; update fixtures for this source must now use a versionCode greater than 13.

## Build

Install a **full JDK 17 or 21**, Android SDK platform 36 and build-tools 36.0.0. Set `JAVA_HOME` and `ANDROID_HOME`, or create untracked `local.properties` with `sdk.dir=/absolute/sdk/path`.

```sh
./gradlew assembleDebug assembleDebugAndroidTest lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class io.github.dpan538.annota.CoreTest,io.github.dpan538.annota.DesignUiTest,io.github.dpan538.annota.EnglishUiTest,io.github.dpan538.annota.InputTest,io.github.dpan538.annota.ReaderPolishTest,io.github.dpan538.annota.RenderLifecycleTest,io.github.dpan538.annota.SeedTest,io.github.dpan538.annota.UpdateTest,io.github.dpan538.annota.ContinuousReaderTest,io.github.dpan538.annota.MotionTest io.github.dpan538.annota.test/androidx.test.runner.AndroidJUnitRunner
```

The two `UpdateTest` cases require a same-signed APK built with `-PannotaVersionCode=14 -PannotaVersionName=update-test-fixture`, staged in the target application's `cache/update-test.apk` (push to `/data/local/tmp/` then copy using `adb shell run-as io.github.dpan538.annota`). Keep the installed debug target at versionCode 13. Without the fixture these two tests explicitly skip. Run instrumentation only on a disposable emulator because storage fixtures reset its library.

The checked-in wrapper pins Gradle 8.13 and verifies its distribution SHA-256. `.tooling/` holds only ignored local downloads and SDK/emulator files. No SDK, build cache or private key should be committed. `scripts/sync-assets.py` can re-copy the dictionaries from the read-only `../design/dictionary/` source. The actual database SHA-256 values are checked again at installation. The original authored five-page guide is bundled as `assets/guide.pdf`; the owner-supplied original paper is bundled as a second initial document with an independent rights notice.

## Storage and offline behavior

The library uses private SQLite transactions. Immutable source PDFs/images and compressed, content-addressed annotation snapshots are committed with AtomicFile before database references change. A bounded 50-action history survives restart; unreferenced state snapshots are collected periodically and at startup. Library progress, favorites, folders and trash live in the private database. Source files are immutable and safely shared by duplicates. Deleting an application or clearing its data removes these files. Android cloud backup and device transfer are disabled.

Settings → Export backup writes `.annota.zip` with complete current library documents, folders, page order, rotation, ink, images, page bookmarks and SHA-256. Undo history, theme preferences and feedback contact drafts are not included. Restore verifies assets and merges with new IDs; it does not overwrite existing documents. The SAF provider is responsible for the final destination; interrupted output is deleted where its provider supports deletion. This is not a cloud backup.

PDF rendering uses Android PdfRenderer. PDFBox-Android extracts text and composes exports while retaining source vector pages/text. New paper backgrounds are rendered locally; ink/images are overlaid into the export. Scanned pages need OCR outside this version. A source PDF is capped at 512 MiB / 10,000 pages; render bitmaps are capped to 2,500 pixels. These limits do not guarantee every PDF feature, font or color space. PDF parsing is cancellable between pages and operations; a currently running native render or PDFBox parse must return before cancellation can complete.

The dictionaries are ECDICT core (57,841 entries) and CC-CEDICT (125,301 entries), not sentence translation. SQLite queries run in a worker with bound arguments and stale-request isolation. Source data and license texts are included offline. No remote font, analytics, login, sync or automatic update checks are used.

## Controls and hardware

The library and full four-tab settings screen now use the approved design coordinate system, native MiSans 400/500 fonts, original Lucide icons, navigation selection, action-card subtitles and thumbnail/title/date structure. Source provenance is recorded in `verification/design-source.json`. The two UI regression tests compare native control rectangles to measurements from the original HTML, for landscape/portrait and Pine/Blue Note themes. The native reader retains the four palettes, three groups of 13 compact top icons, adjacent two-page spreads and page thumbnails. More includes Undo/Redo, page movement, fit, export/share and document information. Long press a thumbnail for page copy/rotation/template/clear/delete/reorder. Drawing tools support pen/pencil/fountain/highlighter, whole/local eraser, shapes, system image selection and a draggable/rotatable edge-snapping ruler. The view consumes standard Android pressure/history/cancel and stylus-button events. Synthetic MotionEvent tests are adapter regression tests, **not hardware validation**. Launcher and library branding are generated from the approved, centered 3B wordmark: run `python3 scripts/prepare-branding.py`.

Settings → Stylus and device reports only observed system inputs and real device ranges. Xiaomi proprietary squeeze, double tap, slide and pen vibration remain disabled pending official interfaces and target-device testing. Android text fields request the installed system handwriting IME; offline behavior must be tested in airplane mode on the actual tablet. The unmodified official MiSans variable TTF is bundled for native UI; `scripts/prepare-design-assets.py /path/to/MiSans.zip` copies the original files and records their hashes without font conversion. Some file-operation/permission dialogs remain standard Android controls. Device-specific font scaling and system insets still need target-device review.

## Feedback

Feedback posts to the deployed Vercel HTTPS relay only when the user clicks Submit. Payload fields are message body, application version and request ID; no contacts, documents, screenshots, logs or device identifiers are included. The server creates a public Issue in this repository and returns its real URL. Only a matching accepted receipt clears that draft and triggers the automatic success notice; network or service failures retain it. No browser login or manual result check is required. GitHub credentials remain server-side. See `../feedback-service/README.md` and `../docs/PRIVACY.md`.

## Signing and GitHub updates

Public stable APKs are non-debuggable release builds. The existing certificate is retained by explicit user decision so development users can upgrade in place. Local debug builds normally use the developer machine's own key and cannot overwrite a public install if the certificate differs. No signing material is committed.

Production signing uses only environment variables:

```sh
export ANNOTA_KEYSTORE=/secure/path/annota-release.jks
export ANNOTA_STORE_PASSWORD=...
export ANNOTA_KEY_ALIAS=...
export ANNOTA_KEY_PASSWORD=...
./gradlew assembleRelease
python3 scripts/make-update-manifest.py app/build/outputs/apk/release/app-release.apk \
  --build-tools "$ANDROID_HOME/build-tools/36.0.0" --output annota-update.json
```

Signing material is supplied locally and kept outside Git. Without the environment variables `assembleRelease` produces an unsigned artifact, which must not be distributed as installable. Keep the production key and an offline backup permanently; future updates must use it.

A real stable Release at `dpan538/Annota` must include the signed APK **and** the generated `annota-update.json`. App check reads the latest stable Release only on user request, shows notes, and waits for a download action. Download permits only HTTPS GitHub asset hosts, enforces size and cancellation, checks SHA-256, cryptographic APK signature (apksig), exact package/version and the currently installed signer before asking the Android installer. Install permission refusal leaves the verified file available for retry. Every in-place upgrade must retain the installed signing identity. Gradle builds the artifact; publishing is a separate verified Release step.

404, rate limit, offline/network error, cancellation, hash/signature mismatch and installation permission states have explicit messages. Public stable releases, anonymous downloads and real Android installer upgrades are recorded in `../docs/INTEGRATION-ACCEPTANCE.md` and `../docs/NEUTRAL-READING-ACCEPTANCE.md`; local fixtures alone do not establish publication.

See `../docs/IMPLEMENTATION-STATUS.md` for actual build/test evidence, artifact paths/hashes and target-device acceptance work.

## Onboarding and regression fixtures

Current stable build: `1.0.2` / versionCode 13. Both seed PDFs are bundled and verified by SHA-256 and page count. They publish together in a database transaction. Any earlier initialization marker is final, including the old guide-only development build. No sample is added back on restart or upgrade, even after removal. Paper rights are separate: see `app/src/main/assets/SAMPLE-PAPER-NOTICE.md`; redistribution clearance remains a public-release checklist item.

The update tests require a same-development-key versionCode 14 / versionName `update-test-fixture` APK at the target app's `cache/update-test.apk`. Build it with Gradle property overrides, keep it outside Git and stage it with `adb shell run-as io.github.dpan538.annota ...` on the dedicated emulator. Without it, those two tests are explicitly skipped; do not report them as passed. No fixture is a published update. Test APKs reset library data and must never run on a user's tablet.

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

## Continuous reading and preview updates (0.1.5-dev)

The reader uses bounded continuous vertical rows (including two-page spreads), visible-page rendering, fling, focal-point pinch zoom, and persisted page/offset/zoom/spread. Fit width is the default; Fit page remains available. Progress updates never serialize all ink.

PaperMenu is an anchored rounded preview panel shared by creation, insertion and template changes; it handles IME resize and removes its host layout observer when dismissed. Buttons, panels and navigation use linear 100–180 ms motion; fling uses Android scrolling physics. System-disabled animations are respected and ink is immediate.

Development/preview builds list the latest 30 GitHub releases and accept non-draft prereleases with an update manifest, selecting the highest larger integer versionCode. Production stable builds retain the stable-only endpoint. Every install verifies the APK again, including restored pending packages. Publish APK + SHA256SUMS.txt + annota-update.json; --allow-debug is explicitly for labelled development previews, never production.

0.1.4 needs one manual overwrite because its old binary excludes prereleases. Current acceptance: [continuous reading/update record](../docs/CONTINUOUS-READER-ACCEPTANCE.md). The earlier 316-cycle log remains 0.1.4 evidence, not a repeated soak of this reader.

## Three control sizes and 0.1.7 preview

60/68/76 now change actual library, reader, floating-menu and paper-picker controls. Settings retain their host and scroll; pen parameters update inside one popup. Run `UiSizingTest` on the dedicated emulator with the other regression classes. `ReaderVisualTest` also accepts `-e buttonSize 76` for the largest controls. See [acceptance](../docs/UI-SIZING-ACCEPTANCE.md) for final build and public delivery evidence.

## Minimal reading mode (0.1.8-dev)

Read is a per-document, read-only workspace, distinct from the Hand tool in annotation. It hides editing controls and blocks stylus erasing/buttons, undo/redo and page editing. Tap the document to toggle chrome without resizing its viewport; long press still opens local lookup. Annotate restores the previous nib, width, color and ruler.

Reading controls sit at the two sides of the toolbar, with the center left empty. The annotation action is an icon without a caption; More → Reading display contains the three presets. Only Original / Soft / Clear display presets are exposed. A cached color matrix is applied to both base PDF previews and resident detail tiles, without invalidating render caches or modifying source/export/ink. This is a whole-page transform, including embedded images, not text segmentation. Black notebook backgrounds are preserved. See [reading acceptance](../docs/READING-MODE-ACCEPTANCE.md) for the native tests, large-keyboard checks and research sources.

## Document search, selection and navigation (0.1.9-dev)

More → Find in document opens the existing left pane in a temporary read-only search state. Results stream by current page order, highlight on the original page and wrap with Previous/Next; close restores the original viewport and annotation tool. Searches normalize case/Unicode and support line-wrapped phrases and Chinese text. There is no OCR; results stop at 1,000 matches. Query, result index and return position survive rotation.

Long-press selection has draggable start/end handles and a touch magnifier. Geometric text flows keep ordinary double-column paragraphs separate; handles extend across lines on the same page. Copy / Dictionary is a compact text-only menu, without icons. This is a layout heuristic rather than semantic document reconstruction.

The existing Pages pane now also contains embedded Outline and Bookmarks. Page-ID bookmarks survive reorder, duplicate and local backup/restore, without adding ink history. Internal PDF links and outline jumps expose a temporary Back to reading control; external URLs and executable actions are not launched. See [navigation acceptance](../docs/DOCUMENT-NAVIGATION-ACCEPTANCE.md).
