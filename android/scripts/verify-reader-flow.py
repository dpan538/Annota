#!/usr/bin/env python3
"""Destructive, real UI/lifecycle/upgrade check for a dedicated Android emulator only."""
import argparse, gzip, hashlib, json, re, sqlite3, subprocess, tempfile, time
from pathlib import Path
import xml.etree.ElementTree as ET

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--adb', required=True)
p.add_argument('--apk', required=True)
p.add_argument('--upgrade-apk', required=True, help='Same-key higher-version local fixture, never a Release')
p.add_argument('--serial', default='emulator-5554')
p.add_argument('--output', default='verification/reader-polish-flow.json')
p.add_argument('--reset-test-library', action='store_true')
a = p.parse_args()
assert a.reset_test_library, 'Explicit --reset-test-library is required; this removes emulator test data.'
assert a.serial.startswith('emulator-'), 'Refusing a physical device.'
base = [str(Path(a.adb).resolve()), '-s', a.serial]
pkg = 'io.github.dpan538.annota'

def adb(*args, binary=False):
    result = subprocess.run(base + list(args), check=True, capture_output=True, timeout=45)
    return result.stdout if binary else result.stdout.decode().strip()
def shell(*args): return adb('shell', *args)
assert shell('getprop', 'ro.kernel.qemu') == '1', 'Not the dedicated emulator.'
report = {
    'device': 'API36 ARM64 dedicated emulator',
    'network': 'airplane mode',
    'targetApkSha256': hashlib.sha256(Path(a.apk).read_bytes()).hexdigest(),
    'upgradeFixtureSha256': hashlib.sha256(Path(a.upgrade_apk).read_bytes()).hexdigest(),
    'steps': [],
}
def step(name): report['steps'].append(name); print(name, flush=True)
def until(condition, label):
    end = time.time() + 10
    while time.time() < end:
        if condition(): return
        time.sleep(.1)
    raise AssertionError('State did not settle: '+label)
def nodes():
    shell('uiautomator', 'dump', '/data/local/tmp/annota-flow.xml')
    return list(ET.fromstring(shell('cat', '/data/local/tmp/annota-flow.xml')).iter('node'))
def find(label, timeout=20):
    end = time.time() + timeout
    while time.time() < end:
        current = nodes()
        hint = next((n for n in current if n.get('text') in ('Got it','知道了')), None)
        if hint is not None:
            tap(hint); continue
        candidates = [n for n in current if label in (n.get('text'), n.get('content-desc'))]
        if candidates:
            return next((n for n in candidates if n.get('clickable') == 'true'), candidates[0])
        time.sleep(.2)
    raise AssertionError('UI entry not found: ' + label)
def tap(node, hold=False):
    x0, y0, x1, y1 = map(int, re.findall(r'\d+', node.get('bounds')))
    x, y = str((x0 + x1)//2), str((y0+y1)//2)
    shell('input', 'swipe', x, y, x, y, '700') if hold else shell('input', 'tap', x, y)
def click(label, hold=False): tap(find(label), hold)
def launch(): shell('am', 'start', '-W', '-n', pkg+'/.MainActivity')
def library():
    current = nodes()
    back = next((n for n in current if n.get('content-desc') == '返回书库'), None)
    if back is not None: tap(back)
    find('主页')
def restart(): shell('am', 'force-stop', pkg); launch(); library()
def db():
    data = adb('exec-out', 'run-as', pkg, 'cat', 'databases/library.sqlite', binary=True)
    with tempfile.NamedTemporaryFile(suffix='.sqlite') as f:
        f.write(data); f.flush()
        c=sqlite3.connect(f.name); c.row_factory=sqlite3.Row
        try: return [dict(r) for r in c.execute('SELECT * FROM books')]
        finally: c.close()
def state(book):
    ref = book['state']
    if ref.startswith('['): return json.loads(ref)
    assert re.fullmatch(r'[a-f0-9]{64}\.json\.gz', ref)
    data = adb('exec-out', 'run-as', pkg, 'cat', 'files/states/'+ref, binary=True)
    return json.loads(gzip.decompress(data))
def book(title): return next(b for b in db() if b['title'] == title)
def pss():
    text = shell('dumpsys', 'meminfo', pkg)
    match = re.search(r'TOTAL PSS:\s*(\d+)', text)
    return int(match[1]) if match else None

shell('am', 'force-stop', pkg)
adb('install', '-r', '-d', str(Path(a.apk).resolve()))
shell('pm', 'clear', pkg)
shell('cmd', 'connectivity', 'airplane-mode', 'enable')
launch(); find('新建笔记')
initial = db(); assert {b['id'] for b in initial} == {'guide','attention'}
assert [len(state(b)) for b in sorted(initial,key=lambda b:b['id'])] == [11,5]
step('Offline fresh launch: exactly the two real PDFs')

click('新建笔记'); click('格子 / Grid'); click('黑 / Black')
edit = next(n for n in nodes() if n.get('class') == 'android.widget.EditText')
tap(edit)
shell('input', 'keycombination', 'KEYCODE_CTRL_LEFT', 'KEYCODE_A')
name = 'Flow-check-' + str(int(time.time()))
shell('input', 'text', name); click('确定')
find('笔与颜色')
shell('input', 'swipe', '780', '500', '1030', '620', '500')
time.sleep(.4)
note = book(name); saved=state(note)
assert saved[0]['style']=='grid' and saved[0]['paper']=='black' and saved[0]['marks']
step('Real UI: create grid/black note and draw a committed stroke')

report['foregroundPssKiB'] = pss()
shell('input', 'keyevent', '3'); time.sleep(.6)
try:
    shell('am', 'send-trim-memory', pkg, '20')
    report['manualTrimRequest'] = 'accepted'
except subprocess.CalledProcessError as error:
    assert b'Unable to set a higher trim level' in error.stderr or b'Unable to set a background trim level' in error.stderr, error.stderr.decode()
    report['manualTrimRequest'] = 'Android rejected manual level due to current process state; Activity.onStop release checked separately'
time.sleep(.6); report['backgroundPssKiB'] = pss()
launch(); library(); click(name)
assert state(book(name)) == saved
step('Home/background/trim/resume: saved ink and paper remain intact')

restart(); click(name); assert state(book(name)) == saved
step('Force-stop and cold reopen: identical saved document state')

click('更多'); click('导出 PDF')
save = next((n for n in nodes() if n.get('text') in ('Save','SAVE','保存') or n.get('content-desc') in ('Save','SAVE','保存')), None)
assert save is not None, 'System document save UI not reached'
tap(save)
# Android DocumentsUI normally starts in Downloads on this dedicated emulator.
path='/sdcard/Download/'+name+'.pdf'
file_bytes = b''
previous_size = -1
end = time.time() + 20
while time.time() < end:
    try:
        candidate = adb('exec-out', 'cat', path, binary=True)
    except subprocess.CalledProcessError:
        candidate = b''
    if candidate.startswith(b'%PDF-') and b'%%EOF' in candidate[-1024:] and len(candidate) == previous_size:
        file_bytes = candidate
        break
    previous_size = len(candidate)
    time.sleep(.3)
assert file_bytes, 'System export did not finish writing a complete PDF'
export = Path(a.output).parent / 'reader-polish-export.pdf'
export.write_bytes(file_bytes)
report['export']={'file':export.name,'bytes':len(file_bytes),'sha256':hashlib.sha256(file_bytes).hexdigest()}
step('System SAF export: a real PDF was saved outside the app')

restart()
for title in ('Attention Is All You Need','Annota 使用指南'):
    click(title, hold=True); click('移到废纸篓')
until(lambda: all(b['trash']==1 for b in db() if b['id'] in ('guide','attention')), 'both samples moved to Trash')
step('Real UI: remove both initial documents to Trash')

shell('am', 'force-stop', pkg)
before = db()
sources_before = {b['id']:state(b) for b in before}
adb('install','-r',str(Path(a.upgrade_apk).resolve()))
launch(); find('主页')
after=db(); assert len(after)==len(before)
assert all(b['trash']==1 for b in after if b['id'] in ('guide','attention'))
assert {b['id']:state(b) for b in after}==sources_before
step('Actual same-signature upgrade: deleted samples stay removed; no duplicate or lost note')

shell('am','force-stop',pkg)
boot_id = shell('cat', '/proc/sys/kernel/random/boot_id')
adb('reboot')
end=time.time()+150
while time.time()<end:
    try:
        if shell('cat', '/proc/sys/kernel/random/boot_id') != boot_id and shell('getprop','sys.boot_completed')=='1': break
    except subprocess.CalledProcessError: pass
    time.sleep(.5)
else: raise AssertionError('Emulator reboot timed out')
shell('input','keyevent','82'); launch(); find('主页')
assert state(book(name))==saved
assert all(b['trash']==1 for b in db() if b['id'] in ('guide','attention'))
step('Emulator reboot: ink, template, background and deleted-sample state persist')
report['noteStateSha256']=hashlib.sha256(json.dumps(saved,sort_keys=True).encode()).hexdigest()
report['initialDocumentsResurrected']=False
report['passed']=True
out=Path(a.output);out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print('Saved '+str(out),flush=True)
