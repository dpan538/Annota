"""Freeze substantive English definitions absent from bundled Chinese lexicons.

Build-host only, no network. The output remains CC BY-SA 4.0, not MIT.
"""
import argparse
import collections
import gzip
import hashlib
import json
from pathlib import Path
import re
import sqlite3

from import_kaikki_forms import EXCLUDE, POS, normalize

REFERENCE = re.compile(
    r"^(?:synonym|antonym|alternative|alternate|variant|obsolete|archaic|dated|rare|"
    r"nonstandard|misspelling|eye dialect|plural|singular|past|present|inflection|"
    r"comparative|superlative|diminutive|augmentative|initialism|acronym|abbreviation|"
    r"contraction|clipping|short form|female equivalent|feminine|masculine|"
    r"used other than|see also|see|compare)\b", re.I)
DERIVED = re.compile(
    r"^(?:(?:the |a )?(?:state|quality|act|action|process|condition|property|fact|practice) of\b|"
    r"in (?:a|an|the) .{0,90} (?:manner|way)\.?$|"
    r"(?:of|relating|pertaining) (?:or relating |or pertaining )?to\b|"
    r"(?:not|non-) [\w-]+\.?$|(?:more|most) [\w-]+\.?$|"
    r"to (?:convert|change|turn) (?:to|into) (?:a |an )?[\w-]+\.?$)", re.I)
META = re.compile(r"translation hub|(?:given|family|personal) name|surname|"
                  r"\b(?:city|town|village|municipality|county|province|district) (?:in|of)\b|"
                  r"\b(?:people|ethnic group|tribe|language) (?:of|spoken|native)\b|"
                  r"\b(?:born|died) in \d{3,4}\b|\(\d{3,4}[–-]\d{0,4}\)", re.I)


def definitions(record):
    """Keep independent explanations, not inflections or reference-only glosses."""
    pos = record.get("pos", "")
    original = record.get("word", "")
    word = normalize(original)
    # Capitalized words are deliberately excluded from this fallback corpus;
    # acronym handling and proper names remain the bilingual dictionaries' job.
    if pos not in POS or original != original.lower():
        return
    if not re.fullmatch(r"[a-z][a-z' -]{1,119}", word):
        return
    for sense in record.get("senses", []):
        tags = set(sense.get("tags", []))
        if tags & (EXCLUDE | {"form-of", "alt-of", "proper-noun", "name", "romanization",
                             "offensive", "derogatory", "vulgar", "ethnic-slur"}):
            continue
        if sense.get("form_of") or sense.get("alt_of"):
            continue
        glosses = sense.get("glosses", [])
        if not glosses:
            continue
        gloss = " ".join(glosses[-1].split())
        if not 20 <= len(gloss) <= 500 or len(gloss.split()) < 5:
            continue
        if REFERENCE.search(gloss) or DERIVED.search(gloss) or META.search(gloss):
            continue
        if re.search(r"\b(?:in this manner|in this way|as above|aforementioned)\b", gloss, re.I):
            continue
        topics = sorted(set(t for t in sense.get("topics", [])
                            if isinstance(t, str)))[:6]
        yield {"word": word, "pos": pos, "gloss": gloss, "topics": topics}


def bilingual_keys(bundle):
    keys, provenance = set(), []
    for name, table, column in (("en-zh.sqlite", "entries", "key"),
                                 ("wiktionary.sqlite", "senses", "word"),
                                 ("wordnet.sqlite", "senses", "word")):
        path = bundle / name
        with sqlite3.connect(f"file:{path}?mode=ro", uri=True) as db:
            keys.update(normalize(row[0]) for row in
                        db.execute(f"SELECT DISTINCT {column} FROM {table}"))
        provenance.append({"file": name, "sha256": digest(path)})
    return keys, provenance


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("bundle", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    excluded, baseline = bilingual_keys(args.bundle)
    kept = collections.defaultdict(list)
    records = 0
    with gzip.open(args.source, "rt", encoding="utf-8") as stream:
        for line in stream:
            records += 1
            record = json.loads(line)
            if normalize(record.get("word", "")) in excluded:
                continue
            for item in definitions(record):
                bucket = kept[item["word"]]
                if len(bucket) < 3 and not any(x["gloss"] == item["gloss"] for x in bucket):
                    bucket.append(item)
    args.destination.mkdir(parents=True, exist_ok=True)
    out = args.destination / "kaikki-en-definitions.jsonl.gz"
    rows = [item for word in sorted(kept) for item in kept[word]]
    raw = "".join(json.dumps(x, ensure_ascii=False, separators=(",", ":")) + "\n"
                  for x in rows).encode()
    out.write_bytes(gzip.compress(raw, mtime=0))
    metadata = {
        "source": "https://kaikki.org/dictionary/English/kaikki.org-dictionary-English.jsonl.gz",
        "source_sha256": digest(args.source), "source_bytes": args.source.stat().st_size,
        "license": "CC-BY-SA-4.0",
        "attribution": "English Wiktionary contributors; extracted by Kaikki / Wiktextract",
        "records_scanned": records, "headwords": len(kept), "senses": len(rows),
        "multiword_headwords": sum(" " in word for word in kept),
        "pos_senses": dict(collections.Counter(x["pos"] for x in rows)),
        "extract_bytes": out.stat().st_size, "extract_sha256": digest(out),
        "bilingual_exclusion_baseline": baseline,
        "filter": "lowercase ordinary noun/verb/adj/adv; exclude existing bilingual headwords, forms, alternatives, outdated/nonstandard/rare/offensive/derogatory/vulgar forms, reference-only and short derivational glosses, missing-reference glosses, capitalized names and explicit place/person glosses; 20-500 characters and >=5 words; first three distinct substantive senses per headword in source order",
        "integration": "English definitions only, never Chinese translations. Display explicit English-definition label only after bilingual lookup fails. Community dictionary quality is not guaranteed; all fields retain source provenance.",
    }
    (args.destination / "kaikki-en-definitions-provenance.json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(metadata, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
