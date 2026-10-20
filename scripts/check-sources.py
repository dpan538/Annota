"""Validate the publishable prototype sources without private PDF fixtures."""
from pathlib import Path
import hashlib
import json
import os
import re
import shutil
import subprocess

root=Path(__file__).resolve().parents[1]
for name in ['README.md','LICENSE','LICENSE-VISUALS.md','THIRD_PARTY_NOTICES.md','docs/FLOW-AUDIT.md','docs/DISTRIBUTION.md']:
    assert (root/name).is_file(), name
assert 'CC BY-NC 4.0' in (root/'LICENSE-VISUALS.md').read_text()
assert json.loads((root/'design/project.config.json').read_text())['repository']=='dpan538/Annota'
guide=(root/'design/guide-content.fragment.html').read_text()
assert re.findall(r'<article[^>]*data-guide-page="(\d+)"',guide)==['1','2','3','4','5']
node=os.environ.get('ANNOTA_NODE') or shutil.which('node')
assert node, 'Node.js 22+ is required'
checked=0
for folder in ['design','scripts']:
    for p in (root/folder).glob('*'):
        if p.suffix=='.py':compile(p.read_text(),str(p),'exec');checked+=1
        elif p.suffix=='.cjs':subprocess.run([node,'--check',str(p)],check=True,capture_output=True);checked+=1
        elif p.name.endswith('.fragment.html'):
            for script in re.findall(r'<script[^>]*>([\s\S]*?)</script>',p.read_text()):
                subprocess.run([node,'--check'],input=script,text=True,check=True,capture_output=True);checked+=1
for p in [root/'design/project.config.json',root/'package.json',root/'branding/manifest.json']:
    json.loads(p.read_text())
manifest=json.loads((root/'design/dictionary/bundle/manifest.json').read_text())
for name,digest in manifest['sources'].items():
    assert hashlib.sha256((root/'design/dictionary/sources'/name).read_bytes()).hexdigest()==digest
logo=json.loads((root/'branding/manifest.json').read_text())
assert logo['selection']=='3B' and logo['inkCenter']==[512,512]
assert logo['accent']['hex']=='#4460CE'
assert [p.name for p in (root/'branding').rglob('*.svg')]==['annota-logo.svg']
assert (root/'branding/annota-logo.png').is_file()
for item in json.loads((root/'branding/fonts/sources.json').read_text()):
    for filename,source in item['files'].items():
        if filename.endswith('.txt'):
            assert hashlib.sha256((root/'branding/fonts'/filename).read_bytes()).hexdigest()==source['sha256']
print(f'Validated {checked} source units, five guide pages, repository config, logos and dictionary source hashes.')
