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
- `build-info-0.1.1-dev.json`, `build-info-0.1.2-dev.json`: previous debug artifact identities; `build-info-0.1.3-dev.json` and `build-info-0.1.4-dev.json` record prior builds; `continuous-build-info.json` records the historical public 0.1.5-dev artifact; `build-info.json` is the current public build. Final 3B branding has its original integration evidence below.

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

## 0.1.5-dev continuous reader and preview updates

- `continuous-build.json` / `continuous-lint-summary.txt`: final versionCode 6 identity, production source hashes and 0 errors / 63 warnings; the previous current build is saved as `build-info-0.1.4-dev.json`.
- `continuous-instrumentation-api36.txt`: all 38 tests passed without skips, including the same-key v7 fixture. Seven continuous-reader cases cover slow cross-page drag, focal zoom/bounds, ink coordinates on a scrolled rotated page, viewport restoration/lightweight progress, fling interruption, repeated Next targets and 10,000-page virtualization. A fit-page bottom-margin assertion still rejects app chrome; default fit-width may legitimately continue beyond the viewport.
- `continuous-ui-flow.json`: actual black/grid creation and 19 ink points retained after cold restart; page/fraction/zoom/fit/spread also retained after reopening the PDF.
- `continuous-*.png`: actual paper previews, note ink/reopen, continuous page boundary, two-page rows, rounded menus, portrait and English panels. OS handwriting-IME onboarding is excluded from app screenshots.
- `continuous-reader-motion.mp4`, `continuous-menu-motion.mp4`, `continuous-video-info.json`: original emulator screen recordings without interpolation or retiming; not device frame-rate/latency benchmarks.
- `continuous-apk-local-check.json`: local transfer, resources/archive, signature, install and cold launch.
- `continuous-apk-public-check.json`: anonymous complete download of the real published 0.1.5-dev APK, resource/signature verification, installation of that copy and successful cold launch.
- `continuous-release-public.json`: real public Pre-release metadata, all three assets, manifest and checksum agreement; stable `/latest` still returns 404, which corrected preview clients do not use.
- `continuous-update-install.json` / `continuous-update-*.png`: actual native GitHub download from a local corrected-client version 5 fixture, recovered pending package after process death, Android source-permission UI, system installer upgrade to public version 6 and exact installed-APK hash. All three book states, ink, viewport and Trash flags retained; pending record/cache cleared. This is not the original 0.1.4 client or Xiaomi installer acceptance.
- `continuous-rebuild-check.json`: default version 6 restored after building the local lower-version client fixture. All ZIP payload entries match the published APK; archive/signing metadata change the whole-file hash. The published APK remains the exact originally tested artifact.

See [continuous reader/update acceptance](../../docs/CONTINUOUS-READER-ACCEPTANCE.md) and the [work log](../../log/2026-10-21-continuous-update/README.md). The 316-cycle experiment remains explicitly scoped to 0.1.4, not this renderer.

## 0.1.6-dev local candidate: PDF detail and compact tools

- `reader-fidelity-build.json`: local versionCode 7 identity and source hashes; not a public release. The published 0.1.5 artifact is unchanged.
- `reader-fidelity-instrumentation-api36.txt`: all 54 regressions passed without skips, including pen-up/history pressure preservation, four source rotations at 400%, complete simultaneous detail residency in a 3408×2272 viewport, actual toolbar touch passthrough, pen/eraser nib retention, ruler lock, pointer order/palm cancellation and in-place theme updates.
- `reader-fidelity-visual.json`, `reader-fidelity-screenshots.json`, `reader-fidelity-*.png`: separate native visual flow and exact screenshot dimensions/hashes; actual selection tap, thumbnail long press, dictionary/keyboard, aligned definition baselines, enlarged long-entry height, portrait and zoom of both bundled PDFs.
- `reader-fidelity-focused-large-keyboard-api36.txt`, `reader-fidelity-large-keyboard.json` and `*-large-keyboard.png`: one complete native visual flow at 420 dpi with a tall real IME.
- `reader-fidelity-motion.mp4`: original recording, no interpolation/retiming, not a Xiaomi latency/frame-rate benchmark.
- `reader-fidelity-dictionary-before.png`: the earlier sparse dictionary layout, before cards, alignment and height corrections.
- `reader-fidelity-zoom-before.png`: actual blurry tile hole caught during visual QA, followed by exact allocation planning and a residency regression. `reader-fidelity-regression-before.txt` preserves the earlier page-clip failure and test isolation/interaction expectation corrections.
- `reader-fidelity-apk-local-check.json`: full local transfer/archive/assets/version/signature check; public HTTPS distribution is not claimed.

See [acceptance](../../docs/READER-FIDELITY-ACCEPTANCE.md) and [work log](../../log/2026-10-21-reader-fidelity/README.md). Physical Xiaomi input, extended large-PDF pressure, production signing and public feedback remain separate.

Context dictionary continuation: `dictionary-context-*.png` shows the native PDF fixture with training-set/sunset/river-bank senses, concise manual fallback and disclosure. See [context acceptance](../../docs/DICTIONARY-CONTEXT-ACCEPTANCE.md) for the offline rule coverage and uncertainty fallback.

## 三档按键与 0.1.7

`ui-sizing-*` 记录最终版三档控件、完整回归、普通/大号键盘及 APK 发布身份；`size-*.png` 为三档横竖屏原生截图，`release-017-*` 为最终包的阅读器与词典记录。`reader-fidelity-*` 继续属于之前的 0.1.6 本地候选；不跨包冒认测试。详见[验收说明](../../docs/UI-SIZING-ACCEPTANCE.md)。

0.1.7 完整身份保留为 `ui-sizing-build.json`；旧公开 0.1.5 的完整身份保留为 `continuous-build-info.json`。`ui-sizing-update-install.json` 及 `release-017-update-*` 保存原始 0.1.5 经真实系统安装器升级到 0.1.7 的记录。

## 极简纯阅读（0.1.8-dev）

`reading-build.json` 记录本轮 APK 及源码/测试哈希，`reading-instrumentation-api36.txt` 为完整原生回归。新增六项覆盖只读、显示像素/颜色、分块驻留与视口、按文档恢复、三档横竖屏和隐藏顶栏后的实际查词/键盘。`reading-regression-visual-api36.txt` 为原有视觉流程；`reading-large-ui-api36.txt` 和 `reading-large-lookup-api36.txt` 为 420 dpi 的实际触摸检查。

`reading-*.png` 为新模式原生截图，`reading-regression-*` 为本轮旧功能视觉回归，`reading-large-*` 为大系统键盘环境；不覆盖旧版截图。`reading-apk-local-check.json` 仅证明 loopback 下载及资源/签名核验。发布回执单独归档，不把测试夹具当作公开更新。详见[验收说明](../../docs/READING-MODE-ACCEPTANCE.md)。

本轮 `reading-build.json` / `build-info.json` 指向公开 0.1.8。`reading-apk-public-check.json` 为匿名完整下载、资源/签名、下载副本安装与冷启动；`reading-release-public.json` 为公开三附件与先前 0.1.7 原件保留校验。`reading-large-regression-*` 另记录 76 档 / 420 dpi 的完整原有视觉流程，并归档三个关键截图。

`reading-update-install.json` / `reading-update-*.png` 保存原始公开 0.1.7 → 0.1.8 的原生下载/校验、实际 Android 安装器与数据保留。ADB 仅建立旧版起点，安装来源权限已授予，未宣称本轮重测权限拒绝/进程死亡分支。
