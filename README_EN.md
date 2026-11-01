# Annota

[中文](README.md) · [English](README_EN.md)

**Open a document. Read with focus. Make it your own.**

Local PDF reading and handwritten annotation, designed around **Xiaomi Pad 9 Pro Max**. No account required. Documents, ink, reading progress and dictionary queries stay on your device.

[![Android](https://img.shields.io/badge/Android-10%2B-4460CE)](DOWNLOAD.md)
[![Beta](https://img.shields.io/badge/Beta-1.0.6--preview-4460CE)](https://github.com/dpan538/Annota/releases/tag/v1.0.6-preview)
[![License](https://img.shields.io/badge/code-MIT-4460CE)](LICENSE)

[**Download Beta APK**](https://github.com/dpan538/Annota/releases/download/v1.0.6-preview/Annota-1.0.6-preview.apk) · [Stable 1.0.2](https://github.com/dpan538/Annota/releases/download/v1.0.2/Annota-1.0.2.apk) · [Installation](DOWNLOAD.md) · [Changelog](CHANGELOG.md)

![Annota reading the original paper in its blue theme](docs/images/readme-reading-blue.png)

## Features

- **Focused reading.** Tiled PDF rendering, continuous scrolling, pinch zoom, adjacent pages, document search, outlines, bookmarks and restored reading positions.
- **Handwritten annotation.** Pen, pencil, fountain pen and highlighter; pressure response, hold-to-straighten, ruler snapping, selection move / scale / rotation and undo / redo. Handwritten signatures and PDF export.
- **Offline dictionaries.** Around 771,000 English–Chinese and 126,000 Chinese–English records. Inflections, phrase lookup and local context help with meaning; unknown phrases can be broken into dictionary segments. All data ships with the APK. No language-pack download or full-sentence translation.
- **Local library.** Folders, favorites, search and trash; PDF sharing from other apps, `.annota.zip` backup / restore and a continue-reading home widget.

Features describe the current Beta; see the [changelog](CHANGELOG.md) for release differences.

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

## Privacy and updates

Reading, writing and dictionary lookup are fully offline. Network access is used only when checking for updates, downloading an APK or submitting feedback. Feedback becomes a public GitHub Issue; do not include private documents. Back up before uninstalling or clearing app data.

**1.0.6-preview is a Beta**, offered by the default update check. Download and installation require confirmation. It uses the existing signing certificate for in-place upgrades. The latest stable version remains **1.0.2**.

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
