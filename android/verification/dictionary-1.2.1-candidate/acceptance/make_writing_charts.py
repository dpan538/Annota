#!/usr/bin/env python3
"""Writing-experience acceptance charts (IEA/ITL/SCR/NPDE/PRC)."""
from pathlib import Path
import json
import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
INK = "#24345e"
LIGHT = "#8fa3c8"
ACCENT = "#c2410c"
GREEN = "#2f7d5c"
GRID = "#d8dce6"
for candidate in ("/System/Library/Fonts/PingFang.ttc", "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"):
    if Path(candidate).exists():
        matplotlib.rcParams["font.family"] = "PingFang SC" if "PingFang" in candidate else "Noto Sans CJK SC"
        break
matplotlib.rcParams["axes.unicode_minus"] = False


def load(p):
    return json.load(open(p))


def chart_speed():
    before = load(HERE / "writing-before/writing-acceptance.json")
    after = load(HERE / "writing-after/writing-acceptance.json")
    ledger = load(ROOT / "android/app/build/outputs/ink-ledger-evidence.json")["snapshotAndCommit"]
    labels = ["pen-up dispatch\n501 pts (UI)", "ITL UI submit\n2000 marks", "ITL snapshot\n2000 marks", "ITL background\ncommit"]
    values = [
        before["cursive"]["penUpDispatchMs"],
        after["cursive"]["penUpDispatchMs"],
    ]
    fig, (ax, ax2) = plt.subplots(1, 2, figsize=(12.2, 4.3), gridspec_kw={"width_ratios": [1, 1.4]})
    x = [0, 1]
    bars = ax.bar(x, values, width=.55, color=[LIGHT, ACCENT])
    for i, v in zip(x, values):
        ax.text(i, v, f"{v:.2f} ms", ha="center", va="bottom", fontsize=9)
    ax.set_xticks(x, ["1.2.0 / pre-change", "candidate"])
    ax.set_ylabel("ms on UI thread")
    ax.set_title("pen-up: synchronous whole-book save → ledger + async snapshot", fontsize=10.5)
    ax.grid(True, axis="y", color=GRID, lw=.6)

    names = ["ITL UI submit", "ITL snapshot", "ITL background\ncommit", "pen-up dispatch\nbefore", "pen-up dispatch\nafter"]
    vals = [ledger["uiSubmitMedianMs"], ledger["snapshotMedianMs"], ledger["asyncTotalMs"] / ledger["asyncCommits"], values[0], values[1]]
    colors = [GREEN, GREEN, GREEN, LIGHT, ACCENT]
    bars2 = ax2.barh(names, vals, color=colors)
    for bar, v in zip(bars2, vals):
        ax2.text(v * 1.15, bar.get_y() + bar.get_height() / 2, f"{v:.3f} ms", va="center", fontsize=8)
    ax2.set_xscale("log")
    ax2.set_xlabel("ms (log)")
    ax2.set_title("where the pen-up time went (2000 marks / 20k points)", fontsize=10.5)
    ax2.grid(True, axis="x", color=GRID, lw=.6)
    fig.suptitle("Writing latency: the UI only snapshots + reserves a version; serialization is off-thread", fontsize=12)
    fig.tight_layout()
    fig.savefig(HERE / "chart-writing-speed.png", dpi=170)
    plt.close(fig)


def chart_retention():
    before = load(HERE / "writing-before/writing-acceptance.json")
    after = load(HERE / "writing-after/writing-acceptance.json")
    arm_b = load(HERE / "writing-before/arm-guard-before.json")
    arm_a = load(HERE / "writing-after/arm-guard-after.json")
    fig, (ax1, ax2, ax3) = plt.subplots(1, 3, figsize=(12.4, 4.1))
    for ax, title, b, a, unit in (
        (ax1, "system CANCEL: ink kept", before["cancel"]["keptPoints"], after["cancel"]["keptPoints"], "points"),
        (ax2, "cursive injection: points saved", before["cursive"]["savedPoints"], after["cursive"]["savedPoints"], "of 501"),
        (ax3, "forearm + first back: still reading", int(arm_b["readerAfterBack"]), int(arm_a["readerAfterBack"]), "0/1"),
    ):
        vals = [b, a]
        bars = ax.bar([0, 1], vals, width=.55, color=[LIGHT, ACCENT])
        for i, v in zip([0, 1], vals):
            ax.text(i, v, f"{v:g}", ha="center", va="bottom", fontsize=10)
        ax.set_xticks([0, 1], ["pre-change", "candidate"])
        ax.set_ylabel(unit)
        ax.set_title(title, fontsize=10.5)
        ax.set_ylim(0, max(vals + [1]) * 1.28)
        ax.grid(True, axis="y", color=GRID, lw=.6)
    fig.suptitle("No dropped stroke, no accidental exit: before / after (same synthetic streams)", fontsize=12)
    fig.tight_layout()
    fig.savefig(HERE / "chart-writing-retention.png", dpi=170)
    plt.close(fig)


def chart_flow():
    v6 = load(ROOT / "android/app/build/ink-v6-profile.json")
    v5 = load(HERE / "after/ink-acceptance.json")["widthProfiles"]["current"]
    width6 = [p["width"] for p in v6["profile"]]
    fig, (ax, ax2) = plt.subplots(1, 2, figsize=(12.2, 4.2))
    ax.plot(range(len(v5)), v5, color=LIGHT, lw=2, label="fountain v5 (pre-change)")
    ax.plot(range(len(width6)), width6, color=ACCENT, lw=2, label="fountain v6 (ink dynamics)")
    ax.set_xlabel("sample index along the authored 179-point stroke")
    ax.set_ylabel("width (page units, base 11)")
    ax.set_title("ink-flow model: width is driven by pressure, speed and reservoir", fontsize=10.5)
    ax.grid(True, color=GRID, lw=.6)
    ax.legend(frameon=False, fontsize=9)
    ax2.plot(range(len(v6["profile"])), [p["reservoir"] for p in v6["profile"]], color=INK, lw=1.8, label="reservoir")
    ax2.plot(range(len(v6["profile"])), [p["flow"] for p in v6["profile"]], color=GREEN, lw=1.8, label="flow")
    ax2.plot(range(len(v6["profile"])), [p["absorption"] for p in v6["profile"]], color=ACCENT, lw=1.5, label="absorption")
    ax2.set_xlabel("sample index")
    ax2.set_ylabel("normalized state")
    ax2.set_title("v6 internal state (deterministic, fixed 240 Hz integration)", fontsize=10.5)
    ax2.grid(True, color=GRID, lw=.6)
    ax2.legend(frameon=False, fontsize=9)
    fig.suptitle("NPDE: same stroke, new dynamics; v5 frozen, v6 applied to new fountain ink", fontsize=12)
    fig.tight_layout()
    fig.savefig(HERE / "chart-ink-flow.png", dpi=170)
    plt.close(fig)


def compose():
    rows = [
        ("writing-cursive.png", "fast cursive, 501 samples"),
        ("writing-cancel.png", "system CANCEL after 120 real samples"),
        ("writing-seam.png", "two connected fountain strokes"),
    ]
    before_dir, after_dir = HERE / "writing-before", HERE / "writing-after"
    images = []
    for name, _ in rows:
        images.append((Image.open(before_dir / name).convert("RGB"), Image.open(after_dir / name).convert("RGB")))
    w, h = images[0][0].size
    label_h, pad, gap = 54, 16, 12
    canvas = Image.new("RGB", (w * 2 + pad * 3, len(rows) * (h + label_h + gap) + pad), "white")
    draw = ImageDraw.Draw(canvas)
    try:
        font = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 20)
        small = ImageFont.truetype("/System/Library/Fonts/PingFang.ttc", 14)
    except OSError:
        font = ImageFont.load_default()
        small = ImageFont.load_default()
    for r, ((before, after), (name, caption)) in enumerate(zip(images, rows)):
        y = pad + r * (h + label_h + gap)
        draw.text((pad, y), f"pre-change — {caption}", fill=INK, font=small)
        draw.text((w + pad * 2, y), f"candidate — {caption}", fill=ACCENT, font=small)
        canvas.paste(before, (pad, y + 26))
        canvas.paste(after, (w + pad * 2, y + 26))
    canvas.save(HERE / "writing-before-after.png", optimize=True)


if __name__ == "__main__":
    chart_speed()
    chart_retention()
    chart_flow()
    compose()
    print("writing charts written")
