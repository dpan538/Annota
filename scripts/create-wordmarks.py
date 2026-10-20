"""Outline three centered wordmarks from the documented, unmodified fonts.

Run with --fetch-fonts to fetch the two OFL fonts pinned in sources.json.
Font instances exist only in memory; the output is vector artwork, not a font.
"""
from pathlib import Path
import argparse
import hashlib
import json
from urllib.request import urlopen
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen

root = Path(__file__).resolve().parents[1]
fonts = root / 'branding/fonts'
out = root / 'branding/wordmark-refinement'
parser = argparse.ArgumentParser()
parser.add_argument('--fetch-fonts', action='store_true')
args = parser.parse_args()
sources = json.loads((fonts / 'sources.json').read_text())
for source in sources:
    for filename, record in source['files'].items():
        target = fonts / filename
        if not target.exists() and args.fetch_fonts:
            data = urlopen(record['url'], timeout=40).read()
            assert hashlib.sha256(data).hexdigest() == record['sha256']
            target.write_bytes(data)
        if not target.exists():
            raise SystemExit('Missing font: run this script with --fetch-fonts.')
        assert hashlib.sha256(target.read_bytes()).hexdigest() == record['sha256']

def kerning(font, left, right):
    if 'GPOS' not in font:
        return 0
    table = font['GPOS'].table
    lookups = {i for f in table.FeatureList.FeatureRecord if f.FeatureTag == 'kern'
               for i in f.Feature.LookupListIndex}
    amount = 0
    for i in sorted(lookups):
        lookup = table.LookupList.Lookup[i]
        for sub in lookup.SubTable:
            if lookup.LookupType == 9:
                sub = sub.ExtSubTable
            if not hasattr(sub, 'PairSet') and not hasattr(sub, 'Class1Record'):
                continue
            if left not in sub.Coverage.glyphs:
                continue
            value = None
            if sub.Format == 1:
                pairs = sub.PairSet[sub.Coverage.glyphs.index(left)].PairValueRecord
                value = next((p.Value1 for p in pairs if p.SecondGlyph == right), None)
            elif sub.Format == 2:
                first = sub.ClassDef1.classDefs.get(left, 0)
                second = sub.ClassDef2.classDefs.get(right, 0)
                value = sub.Class1Record[first].Class2Record[second].Value1
            if value is not None:
                amount += getattr(value, 'XAdvance', 0)
                break
    return amount

def outline(font, tracking):
    glyphs = font.getGlyphSet()
    names = [font.getBestCmap()[ord(c)] for c in 'Annota']
    advance = 0
    records, bounds = [], []
    for i, name in enumerate(names):
        if i:
            advance += kerning(font, names[i-1], name) + tracking
        glyph = glyphs[name]
        pen = SVGPathPen(glyphs)
        glyph.draw(pen)
        box = BoundsPen(glyphs)
        glyph.draw(box)
        x0, y0, x1, y1 = box.bounds
        records.append((advance, pen.getCommands()))
        bounds.append((x0 + advance, y0, x1 + advance, y1))
        advance += glyph.width
    x0, y0 = min(b[0] for b in bounds), min(b[1] for b in bounds)
    x1, y1 = max(b[2] for b in bounds), max(b[3] for b in bounds)
    scale = 690 / (x1 - x0)
    tx = 512 - (x0 + x1) / 2 * scale
    ty = 512 + (y0 + y1) / 2 * scale
    paths = ''.join(f'<path d="{p}" transform="translate({x:.3f} 0)"/>' for x, p in records)
    return f'<g fill="#000000" transform="translate({tx:.3f} {ty:.3f}) scale({scale:.7f} {-scale:.7f})">{paths}</g>'

variants = [
    ('annota-misans-medium', 'MiSans 轻字重', 'MiSans',
     root / 'design/assets/misans/lt.a6a7c6e600ff19f0.0.woff2', {'wght': 500}, -12,
     '保留原来的清爽字形，减轻字重，略收字距。'),
    ('annota-manrope', 'Manrope 紧凑', 'Manrope',
     fonts / 'Manrope[wght].ttf', {'wght': 600}, -22,
     '几何感更整齐，双层 a 保留清楚的阅读感。'),
    ('annota-bricolage-narrow', 'Bricolage 窄体', 'Bricolage Grotesque',
     fonts / 'BricolageGrotesque[opsz,wdth,wght].ttf', {'wght': 550, 'wdth': 85, 'opsz': 96}, -12,
     '用字体本身的窄体轴收紧比例，字形更有个性。'),
]
out.mkdir(parents=True, exist_ok=True)
manifest = []
for stem, label, family, source, axes, tracking, note in variants:
    font = instantiateVariableFont(TTFont(source), axes, inplace=False)
    content = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024" role="img" aria-labelledby="title desc">'
               f'<title>Annota · {label}</title><desc>白底，居中黑色 Annota 字标。{note}</desc>'
               '<rect width="1024" height="1024" fill="#FFFFFF"/>' + outline(font, tracking) + '</svg>\n')
    (out / f'{stem}.svg').write_text(content)
    manifest.append({'id': stem, 'label': label, 'family': family, 'axes': axes,
                     'trackingFontUnits': tracking, 'width': 690, 'canvas': [1024, 1024],
                     'note': note, 'status': 'proposal', 'artworkLicense': 'CC-BY-NC-4.0'})
(out / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
print('Created three centered wordmark proposals.')
