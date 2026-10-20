"""Embed the official, unmodified MiSans font bytes for this application."""
from pathlib import Path
import base64
import json
import re

root=Path(__file__).resolve().parent
assets=root/'assets/misans'
manifest=json.loads((assets/'sources.json').read_text())
fragment=root/'home-ui.fragment.html'
html=re.sub(r'\s*@font-face\{[^}]*\}', '', fragment.read_text())
rules=[]
for item in manifest['chunks']:
    font=assets/item['file']
    if not font.is_file():
        raise SystemExit(f'Official font asset missing: {font.name}; see assets/misans/sources.json')
    encoded=base64.b64encode(font.read_bytes()).decode()
    rules.append('@font-face{font-family:"MiSans VF";font-style:normal;font-weight:150 700;font-display:swap;src:url(data:font/woff2;base64,'+encoded+') format("woff2");unicode-range:'+item['range']+';}')
fragment.write_text(html.replace('<style>','<style>\n'+''.join(rules),1))
print(f'Embedded {len(rules)} original MiSans assets without modifying font bytes.')
