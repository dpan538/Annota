# Annota

[中文](README.md) · [English](README_EN.md)

**Open a document. Read with focus. Make it your own.**

Local PDF reading and handwritten annotation, designed around **Xiaomi Pad 9 Pro Max**. No account required. Documents, ink, reading progress and dictionary queries stay on your device.

[![Android](https://img.shields.io/badge/Android-10%2B-3DDC84)](DOWNLOAD.md)
[![Stable](https://img.shields.io/badge/Stable-1.1.1-16A34A)](https://github.com/dpan538/Annota/releases/tag/v1.1.1)
[![License](https://img.shields.io/badge/License-MIT-8B5CF6)](LICENSE)
![Offline](https://img.shields.io/badge/Dictionary-Offline-0891B2)
![Local](https://img.shields.io/badge/Storage-On_device-DB2777)
![No account](https://img.shields.io/badge/Account-Not_required-64748B)
![Device](https://img.shields.io/badge/Xiaomi-Pad_9_Pro_Max_only-FF6900)

[**Download 1.1.1 stable APK**](https://github.com/dpan538/Annota/releases/download/v1.1.1/Annota-1.1.1.apk) · [Installation](DOWNLOAD.md) · [Changelog](CHANGELOG.md)

## Features

- **Focused reading.** Tiled PDF rendering, continuous scrolling, pinch zoom, adjacent pages, document search, outlines, bookmarks and restored reading positions.
- **Handwritten annotation.** Pen, pencil, fountain pen and highlighter; pressure response, hold-to-straighten, ruler snapping, selection move / scale / rotation and undo / redo. Handwritten signatures and PDF export.
- **Offline language tools.** English–Chinese / Chinese–English dictionaries, Wiktionary, WordNet and attested word forms support technical terms, compounds and clearly labelled English definitions. On-device MiniLM compares context with candidate senses; OPUS-MT provides reference translations for English phrases and short passages. All assets ship with the APK.
- **Local library.** Folders, favorites, search and trash; PDF sharing from other apps, `.annota.zip` backup / restore and a continue-reading home widget.

This page prepares the **1.1.1 stable release**; download assets become available when the Release is published. See the [changelog](CHANGELOG.md) for differences.

## Get started

1. Install the APK and open the bundled *Attention Is All You Need*, or import a PDF. An illustrated guide is included.
2. Tap the pen icon to annotate, or use reading mode to hide annotation tools. Long-press text to copy or look it up offline.
3. Work is saved on the device. Export a PDF to share it, or create a full local backup.

**Target device: Xiaomi Pad 9 Pro Max.** Android 10+ is the installation minimum, not a claim of testing on other devices. Scans can be read and annotated; selection and search require a PDF text layer. OCR, iPad support, cloud sync and certificate-based digital signing are not included.

## A closer look

Actual app screenshots in the same blue theme. Select an image to view full size.

| Local library | Annotation tools |
| --- | --- |
| [![Library](docs/images/readme-library-blue.png)](docs/images/readme-library-blue.png) | [![Pens](docs/images/readme-pen-blue.png)](docs/images/readme-pen-blue.png) |

| Offline dictionary | Page navigation |
| --- | --- |
| [![Dictionary](docs/images/readme-dictionary-blue.png)](docs/images/readme-dictionary-blue.png) | [![Pages](docs/images/readme-pages-blue.png)](docs/images/readme-pages-blue.png) |

## Local dictionary

Lookup finishes contextual sense comparison before showing definitions. Insufficient evidence retains alternative meanings; similarity scores are not certainty. Missing Chinese definitions may fall back to explicitly labelled English definitions. Reference translation supports **English → Simplified Chinese**; technical meanings still need review.

After opening a PDF, background processing prepares context windows from the current page's already extracted text. Context vectors persist on-device and are reused by content hash, model revision and algorithm version. Dictionaries, models and sense vectors all ship with the APK, including for first use.

Under **Settings → About**, **Local dictionary** below App update is enabled by default. It controls semantic reranking and background preparation only; ordinary offline lookup remains available when disabled. See the [local-data policy](docs/PRIVACY.md).

## Privacy and updates

Reading, writing, dictionary lookup and reference translation are fully offline. Network access is used only when checking for updates, downloading an APK or submitting feedback. Feedback becomes a public GitHub Issue; do not include private documents. Back up before uninstalling or clearing app data.

**1.1.1 is a stable release**, offered by the default update check. Download and installation require confirmation. The existing signing certificate is retained for in-place upgrades from earlier stable and Preview builds.

## Build

Native Kotlin / Android Views, PdfRenderer, PDFBox and local SQLite. Requires JDK 17 / 21, Android SDK 36 and Build Tools 36.0.0.

```sh
git clone https://github.com/dpan538/Annota.git
cd Annota/android
./gradlew :app:assembleDebug
```

[Android project](android/README.md) · [Testing](docs/testing.md) · [Dictionary sources](design/dictionary/README.md) · [Report an issue](https://github.com/dpan538/Annota/issues)

## License

Code: [MIT](LICENSE). Original visual assets and guide: [CC BY-NC 4.0](LICENSE-VISUALS.md). Fonts, icons, dictionaries and the sample paper retain their respective licenses; see [third-party notices](THIRD_PARTY_NOTICES.md).

An independent project, not affiliated with or endorsed by Xiaomi.
