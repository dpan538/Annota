#!/usr/bin/env python3
"""Copy unmodified official MiSans files and the approved design palette tokens.

Usage: prepare-design-assets.py /path/to/official/MiSans.zip
The archive is available from https://hyperos.mi.com/font-download/MiSans.zip.
Existing assets can be reused without an argument; their provenance is retained.
No font conversion, subsetting, merging or modification is performed.
"""
from pathlib import Path
import json,re,hashlib,sys,zipfile
root=Path(__file__).resolve().parents[1]
design=root.parent/'design'
output=root/'app/src/main/assets'
(output/'fonts').mkdir(parents=True,exist_ok=True)
provenance=root/'verification/design-source.json'
font_manifest=[]
if len(sys.argv)>1:
    with zipfile.ZipFile(sys.argv[1]) as archive:
        entry=next(i for i in archive.infolist() if i.filename.endswith('/MiSansVF.ttf'))
        data=archive.read(entry)  # ZipFile verifies the original CRC before copying.
        (output/'fonts'/'MiSansVF.ttf').write_bytes(data)
        font_manifest.append({'file':'fonts/MiSansVF.ttf','runtimeWeights':[400,500],
            'archiveEntry':'MiSans/可变字体/MiSansVF.ttf','crc32':f'{entry.CRC:08x}',
            'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()})
        for name in ['MiSans-Regular.ttf','MiSans-Medium.ttf']:
            (output/'fonts'/name).unlink(missing_ok=True)
else:
    previous=json.loads(provenance.read_text())
    font_manifest=previous['fonts']
    for font in font_manifest:
        assert hashlib.sha256((output/font['file']).read_bytes()).hexdigest()==font['sha256']
css=(design/'home-ui.fragment.html').read_text()
palettes=[]
for id in ['pine','graphite-yellow','cobalt-sage','black-kraft']:
    if id=='pine':rules=re.findall(r'#o3-home-concept\{([^{}]+)\}',css)
    else:rules=re.findall(r'#o3-home-concept\[data-palette="'+id+r'"\]\{([^{}]+)\}',css)
    values={k:v.strip() for rule in rules for k,v in re.findall(r'--o3-([\w-]+):([^;\}]+)',rule)}
    palettes.append({'id':id,**values})
(output/'design-palettes.json').write_text(json.dumps(palettes,ensure_ascii=False,indent=2)+'\n')
manifest={'source':'Annota/design/home-ui.fragment.html',
    'sha256':hashlib.sha256((design/'home-ui.fragment.html').read_bytes()).hexdigest(),
    'fontArchive':'https://hyperos.mi.com/font-download/MiSans.zip',
    'fonts':font_manifest,'fontModification':False,
    'layout':'Native port of approved 1024×682⅔ / 682⅔×1024 design coordinates; no placeholder documents seeded'}
provenance.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
print(provenance)
