#!/usr/bin/env python3
"""Verify an APK download, ZIP/assets, Android signature, install and cold launch.

Without --download-url this uses a temporary loopback HTTP server, not GitHub.
--adb is restricted to a dedicated emulator and preserves its application data.
"""
import argparse, hashlib, http.server, json, os, re, subprocess, tempfile, threading, time
import urllib.request, zipfile
from pathlib import Path

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--apk', required=True)
p.add_argument('--build-tools', required=True)
p.add_argument('--download-url')
p.add_argument('--adb')
p.add_argument('--serial', default='emulator-5554')
p.add_argument('--output', default='verification/english-apk-check.json')
a = p.parse_args()
source = Path(a.apk).resolve()
build_tools = Path(a.build_tools).resolve()
digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
expected = digest(source)
report = {'artifact': source.name, 'sha256': expected, 'bytes': source.stat().st_size}
server = None

def run(args):
    result = subprocess.run([str(x) for x in args], check=True, capture_output=True, timeout=120)
    return result.stdout.decode().strip()

class Download(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != '/package.apk':
            self.send_error(404); return
        self.send_response(200)
        self.send_header('Content-Type', 'application/vnd.android.package-archive')
        self.send_header('Content-Length', str(source.stat().st_size))
        self.end_headers()
        with source.open('rb') as stream:
            while chunk := stream.read(1024 * 1024): self.wfile.write(chunk)
    def log_message(self, *args): pass

try:
    if a.download_url:
        assert a.download_url.startswith('https://github.com/dpan538/Annota/releases/download/'), 'Expected an Annota GitHub release asset'
        url = a.download_url
        report['transport'] = 'public GitHub HTTPS, no credentials'
        report['publicUrl'] = url
    else:
        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Download)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        url = f'http://127.0.0.1:{server.server_port}/package.apk'
        report['transport'] = 'temporary loopback HTTP; public GitHub download not tested'
    with tempfile.TemporaryDirectory(prefix='annota-apk-check-') as folder:
        downloaded = Path(folder) / source.name
        with urllib.request.urlopen(url, timeout=60) as response, downloaded.open('wb') as stream:
            assert response.status == 200
            report['contentType'] = response.headers.get('Content-Type')
            report['contentLength'] = int(response.headers.get('Content-Length', 0))
            while chunk := response.read(1024 * 1024): stream.write(chunk)
        assert downloaded.stat().st_size == source.stat().st_size
        assert digest(downloaded) == expected, 'Downloaded APK differs from verified artifact'
        with zipfile.ZipFile(downloaded) as package:
            assert package.testzip() is None, 'ZIP CRC failed'
            names = package.namelist()
            assert all(n in names for n in ('AndroidManifest.xml', 'classes.dex', 'resources.arsc'))
            seed = json.loads(package.read('assets/seed.json'))
            for key, file in [('guide', 'guide.pdf'), ('attention', 'attention-is-all-you-need.pdf')]:
                assert hashlib.sha256(package.read('assets/' + file)).hexdigest() == seed[key]['sha256']
            dictionaries = json.loads(package.read('assets/dictionary/manifest.json'))['files']
            for file in ('en-zh.sqlite', 'zh-en.sqlite'):
                assert hashlib.sha256(package.read('assets/dictionary/' + file)).hexdigest() == dictionaries[file]['sha256']
            report['zipEntries'] = len(names)
            report['nativeAbis'] = sorted({n.split('/')[1] for n in names if n.startswith('lib/') and n.endswith('.so')})
        badging = run([build_tools/'aapt', 'dump', 'badging', downloaded])
        package_name, version_code, version_name = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging).groups()
        assert package_name == 'io.github.dpan538.annota'
        assert "launchable-activity: name='io.github.dpan538.annota.MainActivity'" in badging
        report.update(packageName=package_name, versionCode=int(version_code), versionName=version_name)
        signature = run([build_tools/'apksigner', 'verify', '--verbose', '--print-certs', downloaded])
        report['certificateSha256'] = re.search(r'certificate SHA-256 digest: ([a-f0-9]+)', signature)[1]
        report['signatureV2'] = 'Verified using v2 scheme (APK Signature Scheme v2): true' in signature
        report['signatureV3'] = 'Verified using v3 scheme (APK Signature Scheme v3): true' in signature
        assert report['signatureV2']
        report['assetsAndArchiveVerified'] = True
        if a.adb:
            assert a.serial.startswith('emulator-'), 'Refusing a physical/user device'
            base = [Path(a.adb).resolve(), '-s', a.serial]
            assert run(base + ['shell', 'getprop', 'ro.kernel.qemu']) == '1'
            assert 'Success' in run(base + ['install', '-r', downloaded])
            run(base + ['logcat', '-c'])
            run(base + ['shell', 'am', 'force-stop', package_name])
            launch = run(base + ['shell', 'am', 'start', '-W', '-n', package_name+'/.MainActivity'])
            assert 'Status: ok' in launch
            time.sleep(5)
            assert run(base + ['shell', 'pidof', package_name])
            assert package_name in run(base + ['shell', 'dumpsys', 'activity', 'activities'])
            crashes = run(base + ['logcat', '-d', '-s', 'AndroidRuntime:E', '*:S'])
            assert 'FATAL EXCEPTION' not in crashes
            report['downloadedCopyInstalledAndColdLaunched'] = True
            report['coldStartAmTotalTimeMs'] = int(re.search(r'TotalTime: (\d+)', launch)[1])
        report['passed'] = True
finally:
    if server: server.shutdown(); server.server_close()
out = Path(a.output); out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
print(json.dumps(report, ensure_ascii=False, indent=2))
