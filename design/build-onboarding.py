"""Prepare two local seed documents for the future Android package."""
from pathlib import Path
from html.parser import HTMLParser
import hashlib
import json
import re
import shutil

root = Path(__file__).resolve().parent
bundle = root / "onboarding" / "bundle"
bundle.mkdir(parents=True, exist_ok=True)
paper = json.loads((root / "assets/attention/source.json").read_text())
paper_source = Path(paper["source"])
assert hashlib.sha256(paper_source.read_bytes()).hexdigest() == paper["sha256"]
shutil.copyfile(paper_source, bundle / "attention-is-all-you-need.pdf")
guide_source = root / "guide-content.fragment.html"
guide = guide_source.read_text().replace("/*GUIDE_HAND_FONT*/", (root / "assets/guide-hand/font.css").read_text())
assert re.findall(r'<article[^>]*data-guide-page="(\d+)"', guide) == ["1", "2", "3", "4", "5"]
(bundle / "annota-user-guide.html").write_text(guide)
shutil.copyfile(root / "assets/guide-hand/OFL.txt", bundle / "guide-font-OFL.txt")


class GuideCopy(HTMLParser):
    """Export the exact visible copy so editorial changes cannot drift."""
    blocks = {"header", "footer", "h1", "h2", "h3", "p", "div", "button"}

    def __init__(self):
        super().__init__()
        self.pages = []
        self.current = None

    def handle_starttag(self, tag, attrs):
        if tag == "article":
            self.current = []
        elif self.current is not None and (tag in self.blocks or tag == "br"):
            self.current.append("\n")

    def handle_endtag(self, tag):
        if tag == "article":
            text = "".join(self.current)
            lines = [re.sub(r"[ \t]+", " ", line).strip() for line in text.splitlines()]
            self.pages.append("\n".join(line for line in lines if line))
            self.current = None
        elif self.current is not None and tag in self.blocks:
            self.current.append("\n")
        elif self.current is not None and tag in {"strong", "span", "small"}:
            self.current.append(" ")

    def handle_data(self, data):
        if self.current is not None:
            self.current.append(data)


copy = GuideCopy()
copy.feed(guide)
assert len(copy.pages) == 5
copy_text = "Annota 使用指南｜完整文案\n\n与当前五页指南同步导出，包含标题、正文、示意文字、按钮与页脚。\n"
for number, page in enumerate(copy.pages, 1):
    copy_text += f"\n{'─' * 24}\n第 {number} 页\n{'─' * 24}\n\n{page}\n"
(root / "onboarding" / "guide-copy.txt").write_text(copy_text)
documents = [
    {"id": "p1", "title": "Attention Is All You Need", "kind": "pdf", "asset": "attention-is-all-you-need.pdf", "pages": 11},
    {"id": "guide", "title": "Annota 使用指南", "kind": "note", "contentFormat": "annota-guide-html-v2", "asset": "annota-user-guide.html", "pages": 5, "pageSize": [900, 600], "fontLicense": "guide-font-OFL.txt"},
]
for document in documents:
    data = (bundle / document["asset"]).read_bytes()
    document.update(bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
manifest = {
    "schemaVersion": 1,
    "seedVersion": 2,
    "requiresNetwork": False,
    "initialization": {
        "runOnce": True,
        "atomic": True,
        "overwriteExistingDocuments": False,
        "restoreDeletedSeedsOnLaunch": False,
        "restoreDeletedSeedsOnUpdate": False,
        "timestamps": "device-local initialization time; update last-opened on user open",
    },
    "documents": documents,
    "project": json.loads((root / "project.config.json").read_text()),
}
(bundle / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
print(json.dumps({"documents": len(documents), "guidePages": 5, "bundleBytes": sum((bundle / d["asset"]).stat().st_size for d in documents)}, ensure_ascii=False))
