"""Render the supplied PDF for an offline UI prototype; never modify its source."""
from pathlib import Path
import hashlib
import json
import argparse
import pypdfium2 as pdfium

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('pdf',type=Path,help='A locally supplied 11-page example PDF; redistribution rights are separate.')
source=parser.parse_args().pdf.resolve()
if not source.is_file():parser.error('PDF does not exist')
out = Path(__file__).resolve().parent / "assets/attention"
out.mkdir(parents=True, exist_ok=True)
document = pdfium.PdfDocument(source)
for index, page in enumerate(document):
    scale = 3 if index == 0 else 1.6
    steps = 51 if index == 0 else 85
    image = page.render(scale=scale).to_pil().convert("L")
    image = image.point(lambda value: round(value / steps) * steps)
    image.save(out / f"page-{index + 1:02}.webp", lossless=True, method=6)
    if index:
        inline = out / "inline"
        inline.mkdir(exist_ok=True)
        compact = page.render(scale=1.4).to_pil().convert("L")
        compact = compact.point(lambda value: round(value / 85) * 85)
        compact.save(inline / f"page-{index + 1:02}.webp", lossless=True, method=6)
(out / "source.json").write_text(json.dumps({
    "source": str(source), "sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
    "pages": len(document), "pageSizePt": [612, 792],
    "purpose": "User-supplied local PDF, rendered for Annota UI prototype",
}, ensure_ascii=False, indent=2))
print(f"Rendered {len(document)} pages; {sum(p.stat().st_size for p in out.glob('*.webp')):,} bytes")
