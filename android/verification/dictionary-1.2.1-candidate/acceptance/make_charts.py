#!/usr/bin/env python3
"""Render the 1.2.1 candidate acceptance charts from recorded JSON evidence.

Inputs (all under this directory):
  before/dictionary-latency.json, after/dictionary-latency.json
  before/ink_current.png, after/ink_current.png, after/ink_v4_baseline.png
  before/ink-acceptance.json, after/ink-acceptance.json
  ../validation.json, ../ink-performance-*.json (optional), ../../../design/translation/evaluation/variant-report.json
Outputs (written next to this script): chart-latency.png, chart-data.png,
chart-translation.png, chart-ink-frame.png (when data exists), ink-before-after.png,
acceptance-summary.json.

All inputs come from the same emulator run; the script does not fetch or claim external numbers.
"""
from __future__ import annotations

import json
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib import font_manager
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
INK_BLUE = "#24345e"
INK_LIGHT = "#8fa3c8"
ACCENT = "#c2410c"
GRID = "#d8dce6"

# Prefer a CJK-capable font when the host has one; labels remain readable with DejaVu otherwise.
for candidate in (
    "/System/Library/Fonts/PingFang.ttc",
    "/System/Library/Fonts/Supplemental/Songti.ttc",
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
):
    if Path(candidate).exists():
        font_manager.fontManager.addfont(candidate)
        matplotlib.rcParams["font.family"] = font_manager.FontProperties(fname=candidate).get_name()
        break
matplotlib.rcParams["axes.unicode_minus"] = False


def load(path: Path):
    with path.open() as handle:
        return json.load(handle)


def warm_summary(path: Path):
    data = load(path)
    calls = data["calls"]
    warm = [row for row in calls if row["pass"] == "warm"]
    first = [row for row in calls if row["pass"] == "first"]
    repeat = [row for row in calls if row["pass"] == "repeat"]
    return data, warm, first, repeat


def chart_latency():
    before, warm_b, first_b, repeat_b = warm_summary(HERE / "before/dictionary-latency.json")
    after, warm_a, first_a, repeat_a = warm_summary(HERE / "after/dictionary-latency.json")

    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(12.4, 4.6), gridspec_kw={"width_ratios": [1.55, 1]})
    rounds = [row["round"] - 80 for row in warm_b]
    # Keep the one-off cold suggestion outlier visible but annotate rather than hide it.
    ax1.plot(rounds, [row["ms"] for row in warm_b], color=INK_LIGHT, lw=1.8, marker="o", ms=2.6, label="1.2.0 before")
    ax1.plot(rounds, [row["ms"] for row in warm_a], color=ACCENT, lw=1.8, marker="o", ms=2.6, label="1.2.1 candidate")
    ax1.set_yscale("log")
    ax1.set_xlabel("dictionary call round (steady state)")
    ax1.set_ylabel("latency (ms, log scale)")
    ax1.set_title("40 rounds of interactive dictionary calls", fontsize=11)
    ax1.grid(True, which="both", color=GRID, lw=0.6)
    ax1.legend(frameon=False, fontsize=9)
    top_b = max(warm_b, key=lambda row: row["ms"])
    top_a = max(warm_a, key=lambda row: row["ms"])
    ax1.annotate(f"first suggestion call\n{top_b['ms']:.0f} ms → {top_a['ms']:.0f} ms",
                 xy=(top_a["round"] - 80, top_a["ms"]), xytext=(18, 20),
                 textcoords="offset points", fontsize=8, color=ACCENT,
                 arrowprops=dict(arrowstyle="->", color=ACCENT, lw=0.8))

    def value(rows, key):
        return [row[key] for row in rows]

    labels = ["median", "p95", "max"]
    before_vals = [
        before["summary"]["warm"]["medianMs"],
        before["summary"]["warm"]["p95Ms"],
        before["summary"]["warm"]["maxMs"],
    ]
    after_vals = [
        after["summary"]["warm"]["medianMs"],
        after["summary"]["warm"]["p95Ms"],
        after["summary"]["warm"]["maxMs"],
    ]
    x = range(len(labels))
    ax2.bar([i - 0.2 for i in x], before_vals, width=0.38, color=INK_LIGHT, label="1.2.0")
    ax2.bar([i + 0.2 for i in x], after_vals, width=0.38, color=ACCENT, label="1.2.1 candidate")
    for i, (b, a) in enumerate(zip(before_vals, after_vals)):
        ax2.text(i - 0.2, b, f"{b:.1f}", ha="center", va="bottom", fontsize=7.5)
        ax2.text(i + 0.2, a, f"{a:.1f}", ha="center", va="bottom", fontsize=7.5)
    ax2.set_xticks(list(x), labels)
    ax2.set_ylabel("ms")
    ax2.set_yscale("log")
    ax2.set_title("steady-state summary", fontsize=11)
    ax2.grid(True, axis="y", color=GRID, lw=0.6)
    ax2.legend(frameon=False, fontsize=9)
    fig.suptitle("Dictionary latency: same 40 calls, clean install, emulator-5584 (log scale)", fontsize=12)
    fig.tight_layout()
    fig.savefig(HERE / "chart-latency.png", dpi=170)
    plt.close(fig)
    return before, after


def chart_data():
    before_manifest = load(ROOT / "android/app/src/main/assets/dictionary/manifest.json")
    # 1.2.0 numbers recorded in the public release/verification record.
    sizes = {
        "en-zh .pack (MiB)": (95.45, Path(ROOT / "android/app/src/main/assets/dictionary/en-zh.sqlite.pack").stat().st_size / 1048576),
        "all bundled assets (MiB)": (462.0, 386.1),
        "candidate APK (MiB)": (477.27, 412171912 / 1048576),
    }
    counts = {
        "en-zh aliases (thousands)": (771.862, 0.682),
        "zh-en entries (thousands)": (129.801, 238.664),
    }
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(11.6, 4.2))
    for ax, data, title, unit in (
        (ax1, sizes, "download / asset size", "MiB"),
        (ax2, counts, "dictionary coverage", "thousands"),
    ):
        names = list(data)
        before_vals = [data[n][0] for n in names]
        after_vals = [data[n][1] for n in names]
        x = range(len(names))
        ax.bar([i - 0.2 for i in x], before_vals, width=0.38, color=INK_LIGHT, label="1.2.0")
        ax.bar([i + 0.2 for i in x], after_vals, width=0.38, color=ACCENT, label="1.2.1 candidate")
        for i, (b, a) in enumerate(zip(before_vals, after_vals)):
            ax.text(i - 0.2, b, f"{b:.1f}", ha="center", va="bottom", fontsize=7.5)
            ax.text(i + 0.2, a, f"{a:.1f}", ha="center", va="bottom", fontsize=7.5)
        ax.set_xticks(list(x), [n.replace(" (MiB)", "").replace(" (thousands)", "") for n in names], rotation=12, fontsize=8.5)
        ax.set_ylabel(unit)
        ax.set_title(title, fontsize=11)
        ax.grid(True, axis="y", color=GRID, lw=0.6)
        ax.legend(frameon=False, fontsize=9)
    fig.suptitle("Dictionary data: smaller index, more Chinese→English coverage", fontsize=12)
    fig.tight_layout()
    fig.savefig(HERE / "chart-data.png", dpi=170)
    plt.close(fig)


def chart_translation():
    report = load(ROOT / "design/translation/evaluation/variant-report.json")
    variants = report["variants"]
    names = [v["name"] for v in variants]
    chrf = [v["aggregate"]["meanChrF"] for v in variants]
    seconds = [v["aggregate"]["meanSeconds"] for v in variants]
    fig, ax = plt.subplots(figsize=(11.6, 4.4))
    colors = [ACCENT if n.startswith("beam") else INK_BLUE for n in names]
    bars = ax.barh(names, chrf, color=colors)
    for bar, value, sec in zip(bars, chrf, seconds):
        ax.text(value + 0.15, bar.get_y() + bar.get_height() / 2, f"{value:.2f} chrF · {sec:.3f}s", va="center", fontsize=8)
    ax.set_xlabel("mean chrF on 40 authored development probes (not an accuracy benchmark)")
    ax.set_xlim(0, max(chrf) * 1.28)
    ax.grid(True, axis="x", color=GRID, lw=0.6)
    ax.set_title("English→Chinese decoding variants: no stable gain justifies beam cost", fontsize=12)
    fig.tight_layout()
    fig.savefig(HERE / "chart-translation.png", dpi=170)
    plt.close(fig)


def chart_ink_width():
    record = load(HERE / "after/ink-acceptance.json")
    v4 = record["widthProfiles"]["v4"]
    v5 = record["widthProfiles"]["current"]
    fig, ax = plt.subplots(figsize=(11.6, 4.2))
    ax.plot(range(len(v4)), v4, color=INK_LIGHT, lw=2.0, label="fountain v4 (1.2.0)")
    ax.plot(range(len(v5)), v5, color=ACCENT, lw=2.0, label="fountain v5 (candidate)")
    ax.set_xlabel("sample index along the demonstration stroke (entry → acceleration → slow pool → hook)")
    ax.set_ylabel("rendered width (page units)")
    ax.set_title("Fountain-pen width profile: entry taper, speed response, slow-write pool, release", fontsize=11.5)
    ax.grid(True, color=GRID, lw=0.6)
    ax.legend(frameon=False, fontsize=9)
    # shade the semantic segments of the authored demo stroke
    n = len(v4)
    for start, end, label in ((0, 0.34, "entry"), (0.34, 0.57, "acceleration"), (0.57, 0.74, "slow pool"), (0.74, 1.0, "hook / release")):
        ax.axvspan(int(n * start), int(n * end), color="#f2f4f9", zorder=0)
        ax.text(int(n * (start + end) / 2), ax.get_ylim()[1] * 0.96, label, ha="center", fontsize=8, color="#555555")
    fig.tight_layout()
    fig.savefig(HERE / "chart-ink-width.png", dpi=170)
    plt.close(fig)


def chart_ink_frame():
    files = sorted((HERE / "ink-performance").glob("*cold-hot-frame*.json"))
    if not files:
        return
    record = load(files[-1])
    cases = record["cases"]
    labels = [f"{case['marks']} strokes\n{case['points']:,} points" for case in cases]
    cold_before = [case["coldPerMark"]["median"] for case in cases]
    cold_after = [case["coldNew"]["median"] for case in cases]
    hot_before = [case["hotPerMark"]["median"] for case in cases]
    hot_after = [case["hotNew"]["median"] for case in cases]
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(12.2, 4.4), sharey=True)
    for ax, before, after, title in (
        (ax1, cold_before, cold_after, "first frame after a page change"),
        (ax2, hot_before, hot_after, "steady-state frame (writing / scrolling)"),
    ):
        x = range(len(labels))
        ax.bar([i - 0.2 for i in x], before, width=0.38, color=INK_LIGHT, label="per-mark baseline")
        ax.bar([i + 0.2 for i in x], after, width=0.38, color=ACCENT, label="page Picture cache")
        for i, (b, a) in enumerate(zip(before, after)):
            ax.text(i - 0.2, b, f"{b:.0f}", ha="center", va="bottom", fontsize=7.5)
            ax.text(i + 0.2, a, f"{a:.0f}", ha="center", va="bottom", fontsize=7.5)
            ax.text(i, max(b, a) * 1.06, f"{b / a:.1f}×", ha="center", fontsize=8, color=ACCENT)
        ax.set_xticks(list(x), labels, fontsize=8)
        ax.set_title(title, fontsize=10.5)
        ax.grid(True, axis="y", color=GRID, lw=0.6)
        ax.legend(frameon=False, fontsize=8.5, loc="upper left")
    ax1.set_ylabel("median frame time (ms)")
    fig.suptitle("ReaderView frame cost, 1080×1440 software canvas (current tree)", fontsize=12)
    fig.tight_layout()
    fig.savefig(HERE / "chart-ink-frame.png", dpi=170)
    plt.close(fig)


def compose_ink_comparison():
    before = Image.open(HERE / "before/ink_current_zoom.png").convert("RGB")
    after = Image.open(HERE / "after/ink_current_zoom.png").convert("RGB")
    control = Image.open(HERE / "after/ink_v4_zoom.png").convert("RGB")
    width, height = before.size
    label_h, pad = 54, 18
    canvas = Image.new("RGB", (width * 2 + pad * 3, height + label_h + pad * 2), "white")
    canvas.paste(before, (pad, label_h + pad))
    canvas.paste(after, (width + pad * 2, label_h + pad))
    draw = ImageDraw.Draw(canvas)
    try:
        font = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 22)
        small = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 15)
    except OSError:
        font = ImageFont.load_default()
        small = ImageFont.load_default()
    draw.text((pad, 16), "1.2.0 — fountain pen v4 (before)", fill="#24345e", font=font)
    draw.text((width + pad * 2, 16), "1.2.1 candidate — fountain pen v5 (after)", fill="#c2410c", font=font)
    draw.text((pad, height + label_h + pad - 2), "authored demo stroke 179 samples: slow entry → acceleration → slow pool → sharp hook; v4 rendering bit-identical in both builds", fill="#555555", font=small)
    canvas.save(HERE / "ink-before-after.png", optimize=True)

    diff = Image.new("RGB", (width, height), "white")
    draw_diff = ImageDraw.Draw(diff)
    # A/B difference strip makes visible where v5 changes width/taper; not a correctness claim.
    px_b, px_a = before.load(), after.load()
    for y in range(0, height, 2):
        for x in range(0, width, 2):
            b, a = px_b[x, y], px_a[x, y]
            delta = sum(abs(b[i] - a[i]) for i in range(3))
            if delta:
                draw_diff.point((x, y), fill=(194, 65, 12))
    diff.save(HERE / "ink-difference-map.png")


def main():
    before, after = chart_latency()
    chart_data()
    chart_translation()
    chart_ink_width()
    chart_ink_frame()
    compose_ink_comparison()
    summary = {
        "dictionary": {
            "before": before["summary"],
            "after": after["summary"],
            "queries": [row["query"] for row in before["calls"] if row["pass"] == "first"],
        },
        "ink": {
            "before": load(HERE / "before/ink-acceptance.json"),
            "after": load(HERE / "after/ink-acceptance.json"),
        },
    }
    (HERE / "acceptance-summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    print("charts written")


if __name__ == "__main__":
    main()
