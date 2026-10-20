"""Render three black-on-white proposals as outlined vector artwork."""
from pathlib import Path
import json
import sys
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.boundsPen import BoundsPen

out = Path(__file__).resolve().parents[1] / 'branding/logo-concepts'
source_root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parents[1] / 'design/assets'
misans_source = source_root / 'misans/lt.a6a7c6e600ff19f0.0.woff2'
hand_source = source_root / 'guide-hand/Xiaolai-Regular.ttf'
misans = instantiateVariableFont(TTFont(misans_source), {'wght':700}, inplace=False)
hand = TTFont(hand_source)
out.mkdir(parents=True, exist_ok=True)

def text_paths(font, text, max_width, max_height, cx, cy, bold=0):
    glyphs = font.getGlyphSet(); cmap = font.getBestCmap(); advance=0; records=[]; bounds=[]
    for letter in text:
        glyph=glyphs[cmap[ord(letter)]]
        pen=SVGPathPen(glyphs); glyph.draw(pen)
        box=BoundsPen(glyphs);glyph.draw(box)
        if box.bounds:
            x0,y0,x1,y1=box.bounds;bounds.append((x0+advance,y0,x1+advance,y1))
        records.append((advance,pen.getCommands()));advance+=glyph.width
    x0=min(b[0] for b in bounds);y0=min(b[1] for b in bounds);x1=max(b[2] for b in bounds);y1=max(b[3] for b in bounds)
    scale=min(max_width/(x1-x0),max_height/(y1-y0));tx=cx-(x0+x1)/2*scale;ty=cy+(y0+y1)/2*scale
    paths=''.join(f'<path d="{d}" transform="translate({x:.3f} 0)"/>' for x,d in records)
    stroke=f' stroke="#000000" stroke-width="{bold}" stroke-linejoin="round"' if bold else ''
    return f'<g fill="#000000"{stroke} transform="translate({tx:.3f} {ty:.3f}) scale({scale:.6f} {-scale:.6f})">{paths}</g>'

def svg(name, description, drawing):
    return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024" role="img" aria-labelledby="title desc"><title>'+name+'</title><desc>'+description+'</desc><rect width="1024" height="1024" fill="#FFFFFF"/>'+drawing+'</svg>\n'

book='<g fill="none" stroke="#000000" stroke-linecap="round" stroke-linejoin="round"><rect x="252" y="176" width="528" height="672" rx="38" stroke-width="23"/><path d="M330 180V844" stroke-width="16"/><path d="M231 294H273M231 438H273M231 582H273M231 726H273" stroke-width="23"/></g>'
variants=[
    ('annota-wordmark-misans','纯字标','MiSans Bold',svg('Annota · 纯字标','白底黑字的 Annota，使用 MiSans 700 原始字形。',text_paths(misans,'Annota',766,280,512,512))),
    ('annota-notebook-misans','笔记本 · MiSans','MiSans Bold N',svg('Annota · 笔记本','黑色笔记本轮廓，中央为 MiSans 700 的 N。',book+text_paths(misans,'N',307,332,555,512))),
    ('annota-notebook-hand','笔记本 · 手写','小赖字体 N',svg('Annota · 手写笔记本','相同笔记本轮廓，中央是指南所用小赖字体的 N，作加粗笔触。',book+text_paths(hand,'N',299,332,555,512,bold=38))),
]
manifest=[]
for stem,label,face,content in variants:
    (out/f'{stem}.svg').write_text(content)
    manifest.append({'id':stem,'label':label,'typeface':face,'background':'#FFFFFF','ink':'#000000','status':'proposal','artworkLicense':'CC-BY-NC-4.0'})
(out/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
print('Outlined SVG proposals:',len(variants))
