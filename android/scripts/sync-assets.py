#!/usr/bin/env python3
"""Copy the confirmed offline dictionaries from the read-only design source."""
from pathlib import Path
import shutil
import hashlib
import json
import gzip
root = Path(__file__).resolve().parents[1]
source = root.parent / "design/dictionary"
target = root / "app/src/main/assets/dictionary"
manifest = json.loads((source / "bundle/manifest.json").read_text())
for name, item in manifest["files"].items():
    data = (source / "bundle" / name).read_bytes()
    assert hashlib.sha256(data).hexdigest() == item["sha256"]
for directory in ("bundle", "licenses", "sources"):
    dest = target if directory == "bundle" else target / directory
    dest.mkdir(parents=True, exist_ok=True)
    for file in (source / directory).iterdir():
        if file.is_file():
            record = manifest["files"].get(file.name, {}) if directory == "bundle" else {}
            asset = record.get("asset", file.name)
            if asset.endswith(".pack") and asset != file.name:
                (dest / asset).write_bytes(gzip.compress(file.read_bytes(), mtime=0))
                (dest / file.name).unlink(missing_ok=True)
            else:
                shutil.copyfile(file, dest / file.name)
print("Verified and copied offline dictionaries, sources and licenses")
