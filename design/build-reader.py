"""Embed the locally rendered, user-provided PDF pages into the reader prototype."""
from pathlib import Path
import base64
import gzip
import json
import re

root = Path(__file__).resolve().parent
pages = []
for page in sorted((root / "assets/attention").glob("page-*.webp")):
    inline = page.parent / "inline" / page.name
    pages.append("data:image/webp;base64," + base64.b64encode((inline if inline.exists() else page).read_bytes()).decode())
if len(pages) != 11:
    raise SystemExit('Local example pages are not distributed. Run render-reader-pdf.py with an authorized local 11-page PDF, then extract-reader-text.py.')
reader = (root / "reader-v2.fragment.html").read_text()
reader = reader.replace("/*PDF_PAGES*/[]", json.dumps(pages, separators=(",", ":")))
text_data = base64.b64encode((root / "assets/attention/text.json.gz").read_bytes()).decode()
reader = reader.replace("/*PDF_TEXT*/''", json.dumps(text_data))
guide_source = (root / "guide-content.fragment.html").read_text().replace("/*GUIDE_HAND_FONT*/", (root / "assets/guide-hand/font.css").read_text())
guide = gzip.compress(guide_source.encode(), mtime=0)
reader = reader.replace("/*GUIDE_HTML*/''", json.dumps(base64.b64encode(guide).decode()))
home = root / "home-ui.fragment.html"
text = home.read_text()
config = json.loads((root / "project.config.json").read_text())
repository = config.get("repository")
assert repository is None or re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository)
text = re.sub(r"/\*ANNOTA_PROJECT_CONFIG\*/[\s\S]*?/\*ANNOTA_PROJECT_CONFIG_END\*/", lambda _: "/*ANNOTA_PROJECT_CONFIG*/" + json.dumps(config, separators=(",", ":")) + "/*ANNOTA_PROJECT_CONFIG_END*/", text)
text = re.sub(r"\n<!-- ANNOTA_READER_START -->[\s\S]*?<!-- ANNOTA_READER_END -->", "", text)
index = text.rfind("</div>")
library = (root / "library-controls.fragment.html").read_text()
text = text[:index] + "\n<!-- ANNOTA_READER_START -->\n" + library + "\n" + reader + "\n<!-- ANNOTA_READER_END -->\n" + text[index:]
home.write_text(text)
print(f"Embedded {len(pages)} pages; fragment {home.stat().st_size:,} bytes")
