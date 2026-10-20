"""Build bundled dictionary assets from local source files; no runtime network."""
import argparse
import csv
import gzip
import hashlib
import io
import json
import re
import sqlite3
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def normalize(text):
    return " ".join(unicodedata.normalize("NFKC", text).casefold()
                    .translate(str.maketrans("‐‑–—", "----")).split())


def database(path, schema):
    # Never overwrite a user dictionary; these are generated build artifacts.
    temp = path.with_suffix(".building")
    if temp.exists():
        temp.unlink()
    db = sqlite3.connect(temp)
    db.executescript(schema)
    return db, temp


def finish(db, temp, dest):
    db.commit()
    assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    db.execute("VACUUM")
    db.close()
    temp.replace(dest)


def build(ecdict_path):
    out = ROOT / "bundle"
    out.mkdir(exist_ok=True)
    if ecdict_path:
        # A compact, reproducible subset; the original full CSV stays outside the bundle.
        rows = []
        with ecdict_path.open(encoding="utf-8-sig", newline="") as src:
            for row in csv.DictReader(src):
                freq = [int(row[k]) for k in ("bnc", "frq") if row.get(k, "").isdigit() and int(row[k]) > 0]
                if row.get("translation") and (row.get("tag") or (freq and min(freq) <= 50000)):
                    rows.append({k: row.get(k, "") for k in
                                 ("word", "phonetic", "definition", "translation", "exchange")})
        buffer = io.StringIO()
        writer = csv.DictWriter(buffer, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
        (ROOT / "sources/ecdict-core.csv.gz").write_bytes(
            gzip.compress(buffer.getvalue().encode(), mtime=0))

    en, temp = database(out / "en-zh.sqlite", """
        PRAGMA user_version=2;
        CREATE TABLE entries(id INTEGER PRIMARY KEY, word TEXT NOT NULL, key TEXT NOT NULL,
            compact TEXT NOT NULL, reverse_key TEXT NOT NULL,
            phonetic TEXT, definition TEXT, translation TEXT);
        CREATE INDEX en_key ON entries(key);
        CREATE INDEX en_compact ON entries(compact);
        CREATE INDEX en_reverse ON entries(reverse_key);
        CREATE TABLE forms(key TEXT NOT NULL, entry_id INTEGER NOT NULL);
        CREATE INDEX form_key ON forms(key);
    """)
    with gzip.open(ROOT / "sources/ecdict-core.csv.gz", "rt", encoding="utf-8", newline="") as src:
        for row in csv.DictReader(src):
            key = normalize(row["word"])
            compact = re.sub(r"[^a-z0-9]", "", key)
            ident = en.execute("INSERT INTO entries(word,key,compact,reverse_key,phonetic,definition,translation) VALUES(?,?,?,?,?,?,?)",
                               (row["word"], key, compact, compact[::-1], row["phonetic"],
                                row["definition"].replace("\\n", "\n"),
                                row["translation"].replace("\\n", "\n"))).lastrowid
            # Explicit source forms only; never guess an arbitrary stem.
            for form in row["exchange"].split("/"):
                kind, sep, value = form.partition(":")
                if sep and kind in {"p", "d", "i", "3", "r", "t", "s"} and value:
                    en.execute("INSERT INTO forms VALUES(?,?)", (normalize(value), ident))
    en_count = en.execute("SELECT count(*) FROM entries").fetchone()[0]
    finish(en, temp, out / "en-zh.sqlite")

    zh, temp = database(out / "zh-en.sqlite", """
        PRAGMA user_version=2;
        CREATE TABLE entries(id INTEGER PRIMARY KEY, traditional TEXT NOT NULL,
            simplified TEXT NOT NULL, pinyin TEXT NOT NULL, definition TEXT NOT NULL);
        CREATE INDEX simplified_key ON entries(simplified);
        CREATE INDEX traditional_key ON entries(traditional);
    """)
    header = []
    with gzip.open(ROOT / "sources/cedict.txt.gz", "rt", encoding="utf-8") as src:
        for line in src:
            if line.startswith("#"):
                header.append(line)
                continue
            if not line.strip():
                continue
            match = re.fullmatch(r"(\S+) (\S+) \[(.*?)\] /(.*)/\s*", line)
            if not match:
                raise ValueError("Unrecognized CC-CEDICT entry")
            traditional, simplified, pinyin, definition = match.groups()
            zh.execute("INSERT INTO entries(traditional,simplified,pinyin,definition) VALUES(?,?,?,?)",
                       (traditional, simplified, pinyin, definition.replace("/", "\n")))
    zh_count = zh.execute("SELECT count(*) FROM entries").fetchone()[0]
    finish(zh, temp, out / "zh-en.sqlite")
    (ROOT / "licenses/CC-CEDICT-header.txt").write_text("".join(header))
    manifest = {
        "schemaVersion": 2, "networkRequired": False,
        "en-zh": {"entries": en_count, "source": "ECDICT, frequency/exam core subset",
                  "license": "MIT", "url": "https://github.com/skywind3000/ECDICT"},
        "zh-en": {"entries": zh_count, "source": "CC-CEDICT",
                  "license": "CC-BY-SA-4.0", "url": "https://www.mdbg.net/chinese/dictionary?page=cc-cedict"},
        "files": {p.name: {"bytes": p.stat().st_size,
                          "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
                  for p in sorted(out.glob("*.sqlite"))},
        "sources": {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                    for p in sorted((ROOT / "sources").glob("*.gz"))}
    }
    (out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--ecdict", type=Path, help="Full local CSV; omit to rebuild from saved core source")
    build(parser.parse_args().ecdict)
