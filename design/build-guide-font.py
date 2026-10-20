"""Subset an OFL handwriting face for the authored, offline guide only."""
from pathlib import Path
from html.parser import HTMLParser
import base64
import hashlib
import json
from fontTools import subset
from fontTools.ttLib import TTFont

root = Path(__file__).resolve().parent
assets = root / "assets/guide-hand"
source = json.loads((assets / "source.json").read_text())
original = assets / "Xiaolai-Regular.ttf"
assert hashlib.sha256(original.read_bytes()).hexdigest() == source["sha256"]


class HandCopy(HTMLParser):
    def __init__(self):
        super().__init__()
        self.stack = []
        self.text = ""

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        active = (self.stack[-1] if self.stack else False) or tag in {"h1", "h2", "h3"} or "g-hand" in attrs.get("class", "").split()
        if tag not in {"br", "hr", "img", "input", "path", "meta"}:
            self.stack.append(active)

    def handle_endtag(self, tag):
        if tag not in {"br", "hr", "img", "input", "path", "meta"} and self.stack:
            self.stack.pop()

    def handle_data(self, text):
        if self.stack and self.stack[-1]:
            self.text += text


copy = HandCopy()
copy.feed((root / "guide-content.fragment.html").read_text())
needed = {ord(c) for c in copy.text if ord(c) >= 32}
font = TTFont(original)
assert needed <= set(font.getBestCmap())
subsetter = subset.Subsetter(options=subset.Options())
subsetter.populate(unicodes=needed)
subsetter.subset(font)
for name in font["name"].names:
    if name.nameID in {1, 3, 4, 6, 16}:
        name.string = "AnnotaGuideHand".encode(name.getEncoding())
font.flavor = "woff2"
output = assets / "annota-guide-hand.woff2"
font.save(output)
css = '@font-face{font-family:"Annota Guide Hand";font-style:normal;font-weight:400;font-display:swap;src:url(data:font/woff2;base64,' + base64.b64encode(output.read_bytes()).decode() + ') format("woff2");}'
(assets / "font.css").write_text(css)
print(json.dumps({"handwritingGlyphs": len(needed), "woff2Bytes": output.stat().st_size}))
