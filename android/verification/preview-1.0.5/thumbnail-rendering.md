# PDF thumbnail rendering regression

Environment: dedicated Android emulator `emulator-5584`, 1800 × 1200, debug APK built from the 1.0.5-preview working tree. This is emulator evidence, not Xiaomi physical-device acceptance.

## Reproduced defect

A real 417-page / 24,954,681-byte PDF reproduced persistent blank sidebar rows after finger scrolling. In the actual MainActivity, page 34 and 35 thumbnails stayed white while pages 36 and 37 displayed normally. There were no `AnnotaCover` render exceptions. The PDF and captures remain private and are not part of this repository.

The earlier cache-only checks passed, which was insufficient: the bitmap could be ready while the window still displayed a blank row. `DocumentCoverView.onDraw()` returned early when `getLocalVisibleRect()` was false. ListView can record a child while it is outside the viewport, then reuse that empty hardware display list when the row scrolls into view.

`ThumbnailNavigationTest` now uses actual pointer events for seven consecutive sidebar drags and inspects pixels from `UiAutomation.takeScreenshot()`. It checks every fully visible preview rather than treating a cache hit as proof of successful drawing. No screenshot files are saved by the test.

Red phase, before removing the visibility early return:

```text
java.lang.AssertionError: Scrolled thumbnail has a bitmap but is blank on screen: Rect(32, 419 - 432, 644)
Time: 12.988
Tests run: 1, Failures: 1
```

Green phase, after the rendering fix, same test plus the supplied private PDF:

```text
io.github.dpan538.annota.ThumbnailNavigationTest:..
Time: 64.678
OK (2 tests)
```

A second manual check in the actual MainActivity confirmed that pages 34 and 35 remain visible after the same consecutive drags.

## Additional scheduling fixes

- Thumbnail rendering uses a separate bounded parsed-document pool from high-resolution reader tiles.
- Queued requests whose views detached are removed; all render completion paths release pending state, including allocation failures.
- Active-page changes update sidebar selection styling without recreating every visible thumbnail subscriber.
- Sidebar previews preserve the full page's proportions.
- Reader jobs skip obsolete viewport work; every visible page's base image is queued before detail tiles.

## Anonymous timings

Main reader was zoomed to 3× while the sidebar rendered four visible rows. Times below measure first-visible-page navigation through all visible thumbnails being available. They are observations from this emulator run, not a device-performance promise.

| First visible page | Time to all four thumbnails |
| --- | ---: |
| 17 | 696 ms |
| 35 | 650 ms |
| 201 | 848 ms |
| 391 | 964 ms |
| 17, revisit | 284 ms |

Import of the private PDF took 2,219 ms in this warmed run; an earlier cold run took 5,810 ms. Synthetic 417-page checks also verify the requested page's colour identity, bounded memory/queue sizes, and row identity across active-page changes.

The optional instrumentation argument is `lecturePath`; without it the private-document test is skipped. The synthetic test remains self-contained. Final signed-release validation is recorded separately by the release workflow.
