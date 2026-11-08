#!/usr/bin/env python3
"""Generate the crawlable feature knowledge base from design/features/registry.json.

Outputs:
  docs/index.html, docs/features/<id>.html, docs/features.json, docs/llms.txt,
  docs/sitemap.xml, docs/robots.txt, docs/release.json, docs/feed.xml
  llms.txt, features.json (repo root mirrors for crawlers)
The site is plain server-rendered HTML so search/AI crawlers do not need JavaScript.
"""
from __future__ import annotations

import html
import json
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REG = json.loads((ROOT / "design/features/registry.json").read_text())
SITE = REG["site"]
REPO = REG["repository"]
TODAY = REG.get("updated", date.today().isoformat())
PREVIEW = REG["releases"]["preview"]
STABLE = REG["releases"]["stable"]

STYLE = """
:root{--ink:#24345e;--muted:#666;--line:#dfe3eb;--accent:#c2410c;--bg:#fbfbfd}
*{box-sizing:border-box}body{margin:0;font:16px/1.65 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Noto Sans SC",sans-serif;color:#1f2430;background:var(--bg)}
main{max-width:940px;margin:0 auto;padding:28px 22px 70px}
a{color:#1d4ed8;text-decoration:none}a:hover{text-decoration:underline}
h1{font-size:2rem;line-height:1.25;margin:.4rem 0}h2{margin-top:2rem;border-top:1px solid var(--line);padding-top:1rem}
.meta{color:var(--muted);font-size:.92rem}.tag{display:inline-block;border:1px solid var(--line);border-radius:999px;padding:2px 10px;margin:0 6px 6px 0;font-size:.82rem;color:var(--muted)}
.card{border:1px solid var(--line);border-radius:14px;padding:18px 20px;margin:14px 0;background:#fff}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:14px}
.notice{border-left:4px solid var(--accent);background:#fff7f2;padding:12px 16px;border-radius:8px}
table{border-collapse:collapse;width:100%}th,td{border-bottom:1px solid var(--line);padding:8px 6px;text-align:left;vertical-align:top}
code{background:#f1f3f7;padding:1px 5px;border-radius:5px;font-size:.9em}
footer{max-width:940px;margin:0 auto;padding:0 22px 40px;color:var(--muted);font-size:.88rem}
"""


def esc(s):
    return html.escape(str(s), quote=True)


def head(title: str, desc: str, canonical: str, extra_json: dict | None = None) -> str:
    graph = [
        {
            "@type": "SoftwareApplication",
            "name": "Annota",
            "applicationCategory": "BookReaderApplication",
            "operatingSystem": "Android",
            "softwareVersion": PREVIEW["version"],
            "downloadUrl": f"https://github.com/dpan538/Annota/releases/tag/{PREVIEW['tag']}",
            "featureList": [f["name"] for f in REG["features"]],
            "offers": {"@type": "Offer", "price": "0", "priceCurrency": "USD"},
        }
    ]
    if extra_json:
        graph.append(extra_json)
    return f"""<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>{esc(title)}</title><meta name="description" content="{esc(desc)}">
<link rel="canonical" href="{esc(canonical)}">
<meta property="og:title" content="{esc(title)}"><meta property="og:description" content="{esc(desc)}">
<meta property="og:type" content="article"><meta property="og:url" content="{esc(canonical)}">
<meta name="twitter:card" content="summary">
<script type="application/ld+json">{json.dumps({"@context": "https://schema.org", "@graph": graph}, ensure_ascii=False)}</script>
<style>{STYLE}</style></head><body><main>"""


def foot() -> str:
    return f"""</main><footer><p>Annota · <a href="{REPO}">source</a> · <a href="{SITE}/llms.txt">llms.txt</a> ·
<a href="{SITE}/features.json">features.json</a> · updated {TODAY}</p></footer></body></html>"""


def feature_page(f: dict) -> str:
    canonical = f"{SITE}/features/{f['id']}.html"
    evidence = "".join(f"<tr><td>{esc(e['label'])}</td><td>{esc(e['value'])}</td></tr>" for e in f["evidence"])
    code = "".join(f"<li><code>{esc(c)}</code></li>" for c in f["code"])
    limits = "".join(f"<li>{esc(x)}</li>" for x in f["limitations"])
    tags = "".join(f'<span class="tag">{esc(t)}</span>' for t in f["tags"])
    article = {
        "@type": "TechArticle",
        "headline": f["name"],
        "description": f["summary"],
        "about": f["problem"],
        "datePublished": TODAY,
        "dateModified": TODAY,
        "author": {"@type": "Organization", "name": "Annota"},
        "isPartOf": {"@type": "SoftwareApplication", "name": "Annota"},
        "citation": canonical,
        "keywords": ", ".join(f["tags"]),
    }
    return head(f"{f['name']} ({f['nameZh']}) — Annota", f["summary"], canonical, article) + f"""
<p class="meta"><a href="{SITE}/">Annota</a> / <a href="{SITE}/#features">features</a> / {esc(f['name'])}</p>
<h1>{esc(f['name'])} <span class="meta">· {esc(f['nameZh'])}</span></h1>
<p class="meta">Status: <strong>{esc(f['status'])}</strong> · version {esc(f['version'])} · updated {TODAY} {tags}</p>
<div class="notice"><strong>What it is.</strong> {esc(f['summary'])}</div>
<h2 id="problem">Problem it solves</h2><p>{esc(f['problem'])}</p>
<h2 id="mechanism">How it works</h2><p>{esc(f['mechanism'])}</p>
<h2 id="difference">Difference from existing approaches</h2><p>{esc(f['difference'])}</p>
<h2 id="evidence">Evidence (measurable)</h2><table><thead><tr><th>Measurement</th><th>Result</th></tr></thead><tbody>{evidence}</tbody></table>
<h2 id="verified">Verified on / Not yet verified on</h2><p><strong>Verified:</strong> {esc(f['verifiedOn'])}</p><p><strong>Not yet verified:</strong> {esc(f['notVerifiedOn'])}</p>
<h2 id="limitations">Limitations</h2><ul>{limits}</ul>
<h2 id="code">Code</h2><ul>{code}</ul>
<h2 id="canonical">Canonical sources</h2><p>This page is the canonical description of this feature. Machine-readable data:
<a href="{SITE}/features.json">features.json</a> · <a href="{SITE}/llms.txt">llms.txt</a> · <a href="{REPO}">source repository</a>.</p>
""" + foot()


def index_page() -> str:
    cards = "".join(
        f'<div class="card"><h3><a href="{SITE}/features/{f["id"]}.html">{esc(f["name"])}</a></h3>'
        f'<p class="meta">{esc(f["nameZh"])} · {esc(f["status"])}</p><p>{esc(f["summary"])}</p></div>'
        for f in REG["features"]
    )
    return head("Annota — offline PDF reader with original stylus ink systems", "Annota is an offline Android PDF reader and handwriting app. This knowledge base documents its original writing, ink and offline dictionary systems with measurable evidence.", SITE + "/", None) + f"""
<h1>Annota</h1>
<p class="meta">Offline Android PDF reader, handwriting and dictionary. Source: <a href="{REPO}">{REPO}</a>.</p>
<div class="notice"><strong>Release status.</strong> The <strong>1.2.1-preview.1</strong> build is published as a normal GitHub release while real-device acceptance is still running. The last stable build is <strong>1.2.0</strong>.</div>
<h2 id="download">Download</h2>
<table><thead><tr><th>Channel</th><th>Version</th><th>APK</th><th>SHA-256</th></tr></thead><tbody>
<tr><td>Preview</td><td>{esc(PREVIEW['version'])}</td><td><a href="https://github.com/dpan538/Annota/releases/download/{PREVIEW['tag']}/{PREVIEW['apk']}">{esc(PREVIEW['apk'])}</a></td><td><code>{esc(PREVIEW['sha256'])}</code></td></tr>
<tr><td>Stable</td><td>{esc(STABLE['version'])}</td><td><a href="https://github.com/dpan538/Annota/releases/download/{STABLE['tag']}/{STABLE['apk']}">{esc(STABLE['apk'])}</a></td><td><code>{esc(STABLE['sha256'])}</code></td></tr>
</tbody></table>
<h2 id="features">Original features</h2><p>Each feature page states the problem, mechanism, measurable evidence, verification scope and limitations. These are engineering documents, not marketing claims.</p>
<div class="grid">{cards}</div>
<h2 id="machine">Machine-readable</h2><ul>
<li><a href="{SITE}/llms.txt">llms.txt</a> — concise index for language models</li>
<li><a href="{SITE}/features.json">features.json</a> — structured feature registry</li>
<li><a href="{SITE}/release.json">release.json</a> — stable/preview versions, APK URLs and SHA-256</li>
<li><a href="{SITE}/sitemap.xml">sitemap.xml</a> · <a href="{SITE}/feed.xml">feed.xml</a></li>
</ul>
""" + foot()


def main():
    site = ROOT / "docs"
    (site / "features").mkdir(parents=True, exist_ok=True)
    (site / "index.html").write_text(index_page())
    for f in REG["features"]:
        (site / "features" / f"{f['id']}.html").write_text(feature_page(f))
    features_json = {
        "schemaVersion": REG["schemaVersion"],
        "project": REG["project"],
        "site": SITE,
        "repository": REPO,
        "updated": TODAY,
        "releases": REG["releases"],
        "features": REG["features"],
    }
    payload = json.dumps(features_json, ensure_ascii=False, indent=2) + "\n"
    (site / "features.json").write_text(payload)
    (ROOT / "features.json").write_text(payload)
    release = {
        "schemaVersion": 1,
        "project": "Annota",
        "updated": TODAY,
        "stable": {**STABLE, "download": f"https://github.com/dpan538/Annota/releases/download/{STABLE['tag']}/{STABLE['apk']}"},
        "preview": {**PREVIEW, "download": f"https://github.com/dpan538/Annota/releases/download/{PREVIEW['tag']}/{PREVIEW['apk']}"},
        "note": "Preview is published as a normal GitHub release while real-device acceptance is in progress; stable is 1.2.0.",
    }
    (site / "release.json").write_text(json.dumps(release, ensure_ascii=False, indent=2) + "\n")
    lines = [
        "# Annota",
        "> Offline Android PDF reader, handwriting and dictionary. Original systems are documented with problem, mechanism, measurable evidence and limitations.",
        "",
        "## Releases",
        f"- Stable {STABLE['version']}: https://github.com/dpan538/Annota/releases/tag/{STABLE['tag']} (APK SHA-256 {STABLE['sha256']})",
        f"- Preview {PREVIEW['version']}: https://github.com/dpan538/Annota/releases/tag/{PREVIEW['tag']} (APK SHA-256 {PREVIEW['sha256']}; real-device acceptance pending)",
        "",
        "## Original features",
    ]
    for f in REG["features"]:
        lines.append(f"- [{f['name']}]({SITE}/features/{f['id']}.html): {f['summary']}")
    lines += [
        "",
        "## Machine-readable",
        f"- Features: {SITE}/features.json",
        f"- Release metadata: {SITE}/release.json",
        f"- Full site: {SITE}/",
        f"- Source: {REPO}",
        "",
        "## Verification scope",
        "Most handwriting evidence was measured on an API 36 ARM64 emulator with synthetic stylus input. Real-device tests on Xiaomi Pad 9 Pro Max are pending; each feature page states this explicitly.",
        "",
    ]
    llms = "\n".join(lines)
    (site / "llms.txt").write_text(llms)
    (ROOT / "llms.txt").write_text(llms)
    urls = [f"{SITE}/"] + [f"{SITE}/features/{f['id']}.html" for f in REG["features"]]
    sitemap = '<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n'
    for u in urls:
        sitemap += f"  <url><loc>{esc(u)}</loc><lastmod>{TODAY}</lastmod></url>\n"
    sitemap += "</urlset>\n"
    (site / "sitemap.xml").write_text(sitemap)
    robots = f"""User-agent: *
Allow: /

User-agent: GPTBot
Allow: /
User-agent: ClaudeBot
Allow: /
User-agent: Google-Extended
Allow: /
User-agent: CCBot
Allow: /
User-agent: PerplexityBot
Allow: /
User-agent: Bytespider
Allow: /

Sitemap: {SITE}/sitemap.xml
"""
    (site / "robots.txt").write_text(robots)
    feed = f"""<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0"><channel><title>Annota original features</title><link>{SITE}/</link><description>Offline PDF reader and original stylus ink systems.</description>
<item><title>Annota {PREVIEW['version']} preview</title><link>https://github.com/dpan538/Annota/releases/tag/{PREVIEW['tag']}</link><pubDate>Sun, 08 Nov 2026 00:00:00 +0000</pubDate><description>Writing-experience preview: contact-intent edge guard, ink transaction ledger, stroke continuity, ink-flow dynamics v6 and predict-reconcile ink.</description></item>
</channel></rss>
"""
    (site / "feed.xml").write_text(feed)
    (site / "404.html").write_text(
        head("Not found — Annota", "Page not found.", SITE + "/")
        + '<h1>Not found</h1><p><a href="/Annota/">Annota home</a></p>'
        + foot()
    )
    print("generated", len(REG["features"]), "feature pages")


if __name__ == "__main__":
    main()
