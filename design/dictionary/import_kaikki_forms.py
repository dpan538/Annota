"""Freeze attested English inflections from a local Kaikki JSONL gzip.

No network access and no inferred suffix stripping. Keep this CC BY-SA data
separate from MIT source data; consumers resolve only lemmas they can define.
"""
import argparse
import collections
import gzip
import hashlib
import json
from pathlib import Path
import re
import unicodedata

POS = {"noun", "verb", "adj", "adv"}
EXCLUDE = {"obsolete", "archaic", "dated", "rare", "uncommon", "misspelling",
           "nonstandard", "eye-dialect", "dialectal", "proscribed"}
INFLECTION = {"plural", "singular", "past", "present", "participle",
              "comparative", "superlative", "infinitive"}


def normalize(text):
    return " ".join(unicodedata.normalize("NFKC", text).lower().split())


def pairs(record):
    """Yield only unambiguous source links, never alt_of or semantic relations."""
    pos = record.get("pos", "")
    form = normalize(record.get("word", ""))
    if pos not in POS or not re.fullmatch(r"[a-z][a-z' -]{1,159}", form):
        return
    for sense in record.get("senses", []):
        tags = set(sense.get("tags", []))
        targets = sense.get("form_of", [])
        if "form-of" not in tags or not tags & INFLECTION or tags & EXCLUDE:
            continue
        if len(targets) != 1:
            continue
        lemma = normalize(targets[0].get("word", ""))
        if lemma == form or not re.fullmatch(r"[a-z][a-z' -]{1,159}", lemma):
            continue
        # The target must be the complete tail of an inflection gloss. A linked
        # place/parenthetical term in an extracted gloss is not another lemma.
        if not any(normalize(g).rstrip(".").endswith(" of " + lemma)
                   for g in sense.get("glosses", [])):
            continue
        yield form, lemma, pos


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    extracted = set()
    records = 0
    with gzip.open(args.source, "rt", encoding="utf-8") as stream:
        for line in stream:
            records += 1
            extracted.update(pairs(json.loads(line)))
    args.destination.mkdir(parents=True, exist_ok=True)
    path = args.destination / "kaikki-forms.jsonl.gz"
    data = "".join(json.dumps({"form": f, "lemma": l, "pos": p},
                              ensure_ascii=False, separators=(",", ":")) + "\n"
                   for f, l, p in sorted(extracted)).encode()
    path.write_bytes(gzip.compress(data, mtime=0))
    digest = lambda p: hashlib.file_digest(p.open("rb"), "sha256").hexdigest()
    metadata = {
        "source": "https://kaikki.org/dictionary/English/kaikki.org-dictionary-English.jsonl.gz",
        "source_sha256": digest(args.source),
        "source_bytes": args.source.stat().st_size,
        "license": "CC-BY-SA-4.0",
        "attribution": "English Wiktionary contributors; extracted by Kaikki / Wiktextract",
        "records_scanned": records,
        "pairs": len(extracted),
        "surface_forms": len({x[0] for x in extracted}),
        "multiword_surface_forms": len({x[0] for x in extracted if " " in x[0]}),
        "pos_pairs": dict(collections.Counter(x[2] for x in extracted)),
        "extract_bytes": path.stat().st_size,
        "extract_sha256": digest(path),
        "filter": "noun/verb/adj/adv; form-of plus inflection tag; one target; gloss ends in that target; exclude archaic/rare/nonstandard/misspelling/dialectal forms; no alt_of; no heuristic morphology",
        "integration": "Keep as separate CC BY-SA index; only resolve targets with definitions in bundled dictionaries. Counts are attested forms, not new translated lemmas.",
    }
    (args.destination / "kaikki-forms-provenance.json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(metadata, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
