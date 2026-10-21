#!/usr/bin/env python3
"""Inspect a real signed APK and emit the exact GitHub Release update contract."""
import argparse
import hashlib
import json
import os
import re
import subprocess
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument("apk", type=Path)
p.add_argument("--build-tools", type=Path, required=True)
p.add_argument("--output", type=Path, required=True)
p.add_argument("--allow-debug", action="store_true", help="only for explicit development prereleases or local verification")
a = p.parse_args()
java = os.environ.get("JAVA_HOME")
if not java:
    p.error("Set JAVA_HOME to a full JDK 17 or 21")
verify = subprocess.run([str(a.build_tools / "apksigner"), "verify", "--verbose", "--print-certs", str(a.apk)], check=True, capture_output=True, text=True).stdout
certs = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)", verify)
if len(certs) != 1:
    p.error("Exactly one verified APK signer is required")
badging = subprocess.run([str(a.build_tools / "aapt2"), "dump", "badging", str(a.apk)], check=True, capture_output=True, text=True).stdout
match = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
if not match or match[1] != "io.github.dpan538.annota":
    p.error("APK application ID is incorrect")
if "application-debuggable" in badging and not a.allow_debug:
    p.error("Refusing a debuggable APK for a production release")
manifest = {"schemaVersion": 1, "packageName": match[1], "versionCode": int(match[2]),
            "versionName": match[3], "apk": a.apk.name,
            "sha256": hashlib.sha256(a.apk.read_bytes()).hexdigest(),
            "certificateSha256": certs[0].lower()}
a.output.write_text(json.dumps(manifest, indent=2) + "\n")
print(a.output)
