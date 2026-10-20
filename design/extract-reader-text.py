"""Extract selectable text and PDF coordinates without changing the supplied PDF."""
from pathlib import Path
import gzip
import hashlib
import json
import re

import pypdfium2 as pdfium

root = Path(__file__).resolve().parent
metadata = json.loads((root / "assets/attention/source.json").read_text())
source = Path(metadata["source"])
assert hashlib.sha256(source.read_bytes()).hexdigest() == metadata["sha256"]
document = pdfium.PdfDocument(source)
pages = []
for page in document:
    textpage = page.get_textpage()
    text = textpage.get_text_range()
    lines = []
    for match in re.finditer(r"[^\r\n]+", text):
        content = match.group().strip()
        if not content:
            continue
        boxes = [
            textpage.get_charbox(i)
            for i in range(match.start(), match.end())
            if not text[i].isspace()
        ]
        left, bottom = min(b[0] for b in boxes), min(b[1] for b in boxes)
        right, top = max(b[2] for b in boxes), max(b[3] for b in boxes)
        lines.append([content, *[
            round(v, 1) for v in (left, page.get_height() - top, right - left, top - bottom)
        ]])
    pages.append(lines)
payload = json.dumps(pages, ensure_ascii=False, separators=(",", ":")).encode()
output = root / "assets/attention/text.json.gz"
output.write_bytes(gzip.compress(payload, mtime=0))
assert len(pages) == 11 and all(pages)
assert "Attention Is All You Need" == pages[0][0][0]
print(f"Extracted {sum(map(len, pages))} text lines from {len(pages)} pages; {output.stat().st_size:,} bytes")
