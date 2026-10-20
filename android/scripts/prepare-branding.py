#!/usr/bin/env python3
"""Derive Android resources from the one approved, outlined SVG (no font needed)."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
source = ET.parse(root.parent / "branding/annota-logo.svg").getroot()
group = source.find("{*}g")
tx, ty, sx, sy = re.findall(r"-?\d+(?:\.\d+)?", group.attrib["transform"])
paths = []
for path in group:
    x = re.findall(r"-?\d+(?:\.\d+)?", path.attrib["transform"])[0]
    paths.append(f'<group android:translateX="{x}"><path '
                 f'android:fillColor="{path.get("fill", group.get("fill"))}" '
                 f'android:pathData="{path.get("d")}"/></group>')
glyphs = (f'<group android:translateX="{tx}" android:translateY="{ty}" '
          f'android:scaleX="{sx}" android:scaleY="{sy}">' + ''.join(paths) + '</group>')

def vector(width, height, content, intrinsic_width=108, intrinsic_height=108):
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- Generated from branding/annota-logo.svg; CC BY-NC 4.0. -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android" '
            f'android:width="{intrinsic_width}dp" android:height="{intrinsic_height}dp" '
            f'android:viewportWidth="{width}" android:viewportHeight="{height}">'
            + content + '</vector>\n')

res = root / 'app/src/main/res'
drawable = res / 'drawable'
drawable.mkdir(parents=True, exist_ok=True)
background = '<path android:fillColor="#FFFFFF" android:pathData="M192,0H832Q1024,0 1024,192V832Q1024,1024 832,1024H192Q0,1024 0,832V192Q0,0 192,0Z"/>'
(drawable / 'ic_launcher.xml').write_text(vector(1024, 1024, background + glyphs))
# Keep the wordmark within the adaptive icon's safe zone. The OS supplies its mask.
safe = '<group android:pivotX="512" android:pivotY="512" android:scaleX="0.72" android:scaleY="0.72">' + glyphs + '</group>'
(drawable / 'ic_launcher_foreground.xml').write_text(vector(1024, 1024, safe))
(drawable / 'ic_launcher_monochrome.xml').write_text(vector(1024, 1024, safe.replace('#4460CE', '#000000')))
adaptive = res / 'drawable-v26'
adaptive.mkdir(parents=True, exist_ok=True)
(adaptive / 'ic_launcher.xml').write_text('''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@android:color/white"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>
</adaptive-icon>
''')
# Same outlines, cropped to their actual bounds for the library sidebar.
wordmark = '<group android:translateX="-112" android:translateY="-420.91976">' + glyphs + '</group>'
(drawable / 'annota_wordmark.xml').write_text(vector(800, 182.16055, wordmark.replace('#4460CE', '#000000'), 112, 25.502477))
print('Generated launcher and sidebar resources from approved 3B SVG.')
