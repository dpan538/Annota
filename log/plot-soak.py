#!/usr/bin/env python3
"""Render recorded emulator measurements without smoothing or invented samples."""
import csv
import json
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

folder = Path(__file__).resolve().parent / "2026-10-21-emulator-soak"
data = json.loads((folder / "measurements.json").read_text())
samples = data["samples"]
reopens = data["activityReopenPssSamples"]
with (folder / "samples.csv").open("w", newline="") as stream:
    fields = ["series", "seconds", "cycles", "pssKiB", "javaUsedBytes",
              "pageCacheBytes", "pageCacheLimit", "wordCacheBytes"]
    writer = csv.DictWriter(stream, fieldnames=fields, lineterminator="\n")
    writer.writeheader()
    for series, rows in [("periodic", samples), ("activity-reopen", reopens)]:
        writer.writerows(dict(series=series, **row) for row in rows)

plt.rcParams.update({"font.size": 10, "svg.hashsalt": "annota-soak-316"})
fig, axes = plt.subplots(3, 1, figsize=(12, 8), sharex=True, layout="constrained")
t = [row["seconds"] for row in samples]
axes[0].plot(t, [r["pssKiB"] / 1024 for r in samples], color="#1C604D",
             linewidth=1.2, label="Periodic PSS samples")
axes[0].plot([r["seconds"] for r in reopens], [r["pssKiB"] / 1024 for r in reopens],
             "o", color="#4460CE", markersize=3, label="Activity reopen PSS samples")
axes[0].set_ylabel("Process PSS (MiB)")
axes[0].legend(loc="upper left", ncols=2, frameon=False)
axes[0].set_ylim(0, max(r["pssKiB"] for r in samples + reopens) / 1024 * 1.22)
axes[1].plot(t, [r["javaUsedBytes"] / 2**20 for r in samples], color="#4460CE", linewidth=1.2)
axes[1].set_ylabel("Java used heap (MiB)")
axes[1].set_ylim(bottom=0)
axes[2].plot(t, [r["pageCacheBytes"] / 2**20 for r in samples], color="#1C604D",
             drawstyle="steps-post", label="Page bitmap cache")
axes[2].plot(t, [r["wordCacheBytes"] / 2**20 for r in samples], color="#4460CE", label="Estimated word cache")
axes[2].axhline(samples[0]["pageCacheLimit"] / 2**20, color="#656A70", linestyle="--", label="Page cache budget (24 MiB)")
axes[2].set_ylabel("Cache (MiB)")
axes[2].set_ylim(0, 28)
axes[2].set_xlabel("Elapsed time (seconds)")
axes[2].legend(loc="upper left", ncols=3, frameon=False)
for ax in axes:
    ax.set_xlim(0, data["elapsedSeconds"])
    ax.grid(axis="y", alpha=.18)
    ax.spines[["top", "right"]].set_visible(False)
fig.suptitle("Annota emulator soak · 602 seconds / 316 cycles / 31 Activity recreations\n"
             "API 36 ARM64 · debug + instrumentation · not Xiaomi hardware evidence", fontsize=14)
fig.savefig(folder / "memory-curve.png", dpi=160)
fig.savefig(folder / "memory-curve.svg", metadata={"Date": None})
svg = folder / "memory-curve.svg"
svg.write_text("\n".join(line.rstrip() for line in svg.read_text().splitlines()) + "\n")
print(f"Exported {len(samples)} periodic and {len(reopens)} reopen samples to {folder.name}")
