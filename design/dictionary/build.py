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
from wordnet import build_wordnet
from wiktionary import build_wiktionary
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def normalize(text):
    return " ".join(unicodedata.normalize("NFKC", text).casefold()
                    .translate(str.maketrans("‐‑–—’", "----'")).replace("\u00ad", "").split())


def boundary_key(text):
    """Hyphen and space are equivalent boundaries; never concatenate separate words."""
    return re.sub(r"[ -]+", " ", normalize(text))


def authored_phrases():
    """Original short equivalents, with explicit inflections/spellings, not inferred stems."""
    rows, keys, category = [], set(), None
    for number, line in enumerate((ROOT / "sources/annota-phrases.tsv").read_text().splitlines(), 1):
        if line.startswith("# category: "):
            category = line.removeprefix("# category: ")
        if not line.strip() or line.startswith("#"):
            continue
        fields = line.split("\t")
        if len(fields) not in (2, 3) or not category:
            raise ValueError(f"Invalid phrase format on line {number}")
        word, meaning = fields[:2]
        key = normalize(word)
        if key in keys or len(key) > 160 or not re.fullmatch(r"[a-z0-9' -]+", key):
            raise ValueError(f"Invalid or duplicate phrase: {word}")
        if not meaning.strip() or len(meaning) > 20:
            raise ValueError(f"Phrase meaning should be concise: {word}")
        aliases = [normalize(a) for a in fields[2].split("|")] if len(fields) == 3 else []
        if any(not a or len(a) > 160 for a in aliases):
            raise ValueError(f"Invalid alias for: {word}")
        keys.add(key)
        rows.append({"word": word, "key": key, "meaning": meaning,
                     "aliases": sorted(set(aliases)), "category": category})
    return rows


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
        # Include every translated entry: frequency/exam filters exclude specialist vocabulary.
        rows = []
        with ecdict_path.open(encoding="utf-8-sig", newline="") as src:
            for row in csv.DictReader(src):
                if row.get("translation") and row.get("word"):
                    rows.append({k: row.get(k, "") for k in
                                 ("word", "phonetic", "definition", "translation", "exchange")})
        buffer = io.StringIO()
        writer = csv.DictWriter(buffer, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
        (ROOT / "sources/ecdict-core.csv.gz").write_bytes(
            gzip.compress(buffer.getvalue().encode(), mtime=0))

    phrases = authored_phrases()
    en, temp = database(out / "en-zh.sqlite", """
        PRAGMA user_version=3;
        CREATE TABLE entries(id INTEGER PRIMARY KEY, word TEXT NOT NULL, key TEXT NOT NULL,
            compact TEXT NOT NULL, reverse_key TEXT NOT NULL,
            phonetic TEXT, definition TEXT, translation TEXT,
            source TEXT NOT NULL DEFAULT 'ecdict', category TEXT NOT NULL DEFAULT '');
        CREATE INDEX en_key ON entries(key);
        CREATE INDEX en_compact ON entries(compact);
        CREATE INDEX en_reverse ON entries(reverse_key);
        CREATE TABLE forms(key TEXT NOT NULL, entry_id INTEGER NOT NULL);
        CREATE INDEX form_key ON forms(key);
        CREATE TABLE aliases(key TEXT NOT NULL, boundary_key TEXT NOT NULL,
            entry_id INTEGER NOT NULL, UNIQUE(key, entry_id));
        CREATE INDEX alias_key ON aliases(key);
        CREATE INDEX alias_boundary ON aliases(boundary_key);
    """)
    with gzip.open(ROOT / "sources/ecdict-core.csv.gz", "rt", encoding="utf-8", newline="") as src:
        for row in csv.DictReader(src):
            key = normalize(row["word"])
            compact = re.sub(r"[^a-z0-9]", "", key)
            ident = en.execute("INSERT INTO entries(word,key,compact,reverse_key,phonetic,definition,translation) VALUES(?,?,?,?,?,?,?)",
                               (row["word"], key, compact, compact[::-1], row["phonetic"],
                                row["definition"].replace("\\n", "\n"),
                                row["translation"].replace("\\n", "\n"))).lastrowid
            en.execute("INSERT OR IGNORE INTO aliases VALUES(?,?,?)",
                       (key, boundary_key(key), ident))
            # Explicit source forms only; never guess an arbitrary stem.
            for form in row["exchange"].split("/"):
                kind, sep, value = form.partition(":")
                if sep and kind in {"p", "d", "i", "3", "r", "t", "s"} and value:
                    en.execute("INSERT INTO forms VALUES(?,?)", (normalize(value), ident))
    # Reverse lemma links are explicit in ECDICT's `0:` field. Preserve them even
    # when the inflected form itself is also a headword.
    with gzip.open(ROOT / "sources/ecdict-core.csv.gz", "rt", encoding="utf-8", newline="") as src:
        for row in csv.DictReader(src):
            for form in row["exchange"].split("/"):
                if form.startswith("0:") and form[2:]:
                    en.execute("INSERT INTO forms SELECT ?,id FROM entries WHERE key=?",
                               (normalize(row["word"]), normalize(form[2:])))
    # The frozen SPECIALIST-derived LemmInflect lookup adds attested morphology,
    # not heuristic suffix deletion. Keep ambiguous bases instead of guessing one.
    with gzip.open(ROOT / "sources/lemminflect-forms.jsonl.gz", "rt") as src:
        for line in src:
            if not line.strip():
                continue
            row = json.loads(line)
            for lemma in row["lemmas"]:
                if normalize(lemma) != normalize(row["form"]):
                    en.execute("INSERT INTO forms SELECT ?,id FROM entries WHERE key=?",
                               (normalize(row["form"]), normalize(lemma)))
    en.execute("DELETE FROM forms WHERE rowid NOT IN (SELECT min(rowid) FROM forms GROUP BY key,entry_id)")
    base_en_count = en.execute("SELECT count(*) FROM entries").fetchone()[0]
    for row in phrases:
        compact = re.sub(r"[^a-z0-9]", "", row["key"])
        # Author-owned phrase meanings take priority without removing original source records.
        ident = en.execute("""INSERT INTO entries(word,key,compact,reverse_key,phonetic,definition,
                           translation,source,category) VALUES(?,?,?,?,?,?,?,?,?)""",
                           (row["word"], row["key"], compact, compact[::-1], "", "",
                            row["meaning"], "annota", row["category"])).lastrowid
        for key in [row["key"]] + row["aliases"]:
            en.execute("INSERT OR IGNORE INTO aliases VALUES(?,?,?)",
                       (key, boundary_key(key), ident))
    en_count = en.execute("SELECT count(*) FROM entries").fetchone()[0]
    finish(en, temp, out / "en-zh.sqlite")

    zh, temp = database(out / "zh-en.sqlite", """
        PRAGMA user_version=3;
        CREATE TABLE entries(id INTEGER PRIMARY KEY, traditional TEXT NOT NULL,
            simplified TEXT NOT NULL, pinyin TEXT NOT NULL, definition TEXT NOT NULL,
            source TEXT NOT NULL DEFAULT 'cedict');
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
    base_zh_count = zh.execute("SELECT count(*) FROM entries").fetchone()[0]
    for row in phrases:
        # Reverse complete equivalents only. Ellipsis constructions are not standalone words.
        for meaning in row["meaning"].split("；"):
            if "……" not in meaning and len(meaning) > 1:
                zh.execute("INSERT INTO entries(traditional,simplified,pinyin,definition,source) VALUES(?,?,?,?,?)",
                           (meaning, meaning, "", row["word"], "annota"))
    zh_count = zh.execute("SELECT count(*) FROM entries").fetchone()[0]
    finish(zh, temp, out / "zh-en.sqlite")
    (ROOT / "licenses/CC-CEDICT-header.txt").write_text("".join(header))
    wordnet = build_wordnet()
    manifest = {
        "wordnet": wordnet,
        "wiktionary": build_wiktionary(),
        "schemaVersion": 3, "networkRequired": False,
        "en-zh": {"entries": en_count, "baseEntries": base_en_count,
                  "source": "ECDICT complete translated vocabulary + Annota authored phrases",
                  "license": "MIT", "url": "https://github.com/skywind3000/ECDICT"},
        "zh-en": {"entries": zh_count, "baseEntries": base_zh_count,
                  "source": "CC-CEDICT + Annota authored reverse equivalents",
                  "license": "CC-BY-SA-4.0", "url": "https://www.mdbg.net/chinese/dictionary?page=cc-cedict"},
        "annota-phrases": {"entries": len(phrases), "aliases": sum(len(r["aliases"]) for r in phrases),
                           "license": "MIT", "source": "Original Annota phrase equivalents, 2026-11-01",
                           "categories": {c: sum(r["category"] == c for r in phrases)
                                          for c in sorted({r["category"] for r in phrases})}},
        "files": {p.name: {"asset": p.name + ".pack" if p.stat().st_size > 90_000_000 else p.name,
                          "bytes": p.stat().st_size,
                          "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
                  for p in sorted(out.glob("*.sqlite"))},
        "sources": {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                    for p in sorted((ROOT / "sources").iterdir()) if p.suffix in (".gz", ".tsv", ".json")}
    }
    (out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
    print(json.dumps(manifest, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--ecdict", type=Path, help="Full local CSV; omit to rebuild from saved core source")
    build(parser.parse_args().ecdict)
