"""Build a separate sense-indexed English/Chinese WordNet from open sources.

Chinese meanings come from Chinese Open WordNet (COW), Chinese WordNet (CWN,
Academia Sinica) and the Wiktionary-based OMW Chinese wordnet. English lemmas
and glosses come from Princeton WordNet 3.0 and, where the CILI mapping allows,
Open English WordNet 2025. Licences stay attributed per source; never download
at runtime.
"""
import gzip
import json
import sqlite3
from pathlib import Path

from lexicons import (
    load_ili_map,
    load_oewn,
    load_omw_chinese,
    load_wn30,
)

ROOT = Path(__file__).resolve().parent


def build_wordnet():
    cow = json.loads(gzip.decompress((ROOT / "sources/wordnet-cow.json.gz").read_bytes()))
    ili_map = load_ili_map()
    oewn = load_oewn()
    wn30 = load_wn30()
    omw = load_omw_chinese()
    oewn_synsets = oewn["synsets"]

    # Chinese meanings per PWN30 synset, first occurrence order preserved.
    chinese = {}
    sources = {}
    cow_gloss = {}
    cow_pos = {}
    cow_words = {}
    for word, pos, _rank, synset, meanings, gloss in cow["senses"]:
        cleaned = []
        for meaning in meanings:
            # '+' is COW's segmentation marker, not punctuation in the equivalent.
            value = meaning.replace("+", "")
            if value and value not in cleaned:
                cleaned.append(value)
        bucket = chinese.setdefault(synset, [])
        for value in cleaned:
            if value not in bucket:
                bucket.append(value)
        sources.setdefault(synset, set()).add("cow")
        cow_gloss.setdefault(synset, gloss)
        cow_pos.setdefault(synset, pos)
        cow_words.setdefault(synset, []).append(word.replace("_", " "))
    for synset, item in omw.items():
        bucket = chinese.setdefault(synset, [])
        for lemma in item["lemmas"]:
            if lemma not in bucket:
                bucket.append(lemma)
        sources.setdefault(synset, set()).update(item["sources"])
    for synset in chinese:
        ili = ili_map.get(synset)
        if ili and ili in oewn_synsets:
            sources[synset].add("oewn")

    # rank = position among all WordNet senses of the same (lemma, pos); lower is
    # more frequent. WordNet's index.sense order is the accepted frequency order.
    order_of = {}
    for (word, pos), ids in wn30["sense_order"].items():
        for index, synset in enumerate(ids):
            order_of.setdefault((word, pos, synset), index)

    rows = []
    for synset, meanings in chinese.items():
        wordnet = wn30["synsets"].get(synset)
        pos = (wordnet["pos"] if wordnet else cow_pos.get(synset, "n"))
        lemmas = list(wordnet["lemmas"]) if wordnet else list(cow_words.get(synset, []))
        if not lemmas:
            continue
        ili = ili_map.get(synset)
        oewn_synset = oewn_synsets.get(ili) if ili else None
        gloss = (oewn_synset["gloss"] if oewn_synset else "") or cow_gloss.get(synset) \
            or (wordnet["gloss"] if wordnet else "")
        if not gloss:
            continue
        meaning = "；".join(meanings)
        tag = ",".join(sorted(sources[synset]))
        for lemma in dict.fromkeys(lemmas):
            key = (lemma, pos, synset)
            rows.append((lemma, pos, order_of.get(key, 0), synset, meaning, gloss, tag))
    # Deterministic global order: frequency rank first (matching the previous
    # ORDER BY rank,pos behaviour) with pos as the stable tie-break.
    rows.sort(key=lambda row: (row[0], row[2], row[1], row[3]))
    seen = set()
    unique = []
    for row in rows:
        key = (row[0], row[1], row[3])
        if key in seen:
            continue
        seen.add(key)
        unique.append(row)

    dest = ROOT / "bundle/wordnet.sqlite"
    temp = dest.with_suffix(".building")
    temp.unlink(missing_ok=True)
    db = sqlite3.connect(temp)
    db.executescript("""PRAGMA user_version=2;
      CREATE TABLE senses(word TEXT, pos TEXT, rank INTEGER, sense_order INTEGER,
        synset TEXT, meaning TEXT, gloss TEXT, sources TEXT);
      CREATE INDEX sense_word ON senses(word);
      CREATE INDEX sense_order ON senses(sense_order);
      CREATE TABLE forms(form TEXT, lemma TEXT, pos TEXT, UNIQUE(form,lemma,pos));
      CREATE INDEX wn_form ON forms(form);""")
    db.executemany(
        "INSERT INTO senses VALUES(?,?,?,?,?,?,?,?)",
        [(row[0], row[1], row[2], index, row[3], row[4], row[5], row[6])
         for index, row in enumerate(unique)],
    )
    for form, lemma, pos in cow["exceptions"]:
        db.execute("INSERT OR IGNORE INTO forms VALUES(?,?,?)", (form, lemma, pos))
    with gzip.open(ROOT / "sources/lemminflect-forms.jsonl.gz", "rt") as src:
        for line in src:
            if not line.strip():
                continue
            row = json.loads(line)
            pos = {"NOUN": "n", "VERB": "v", "AUX": "v", "ADJ": "a", "ADV": "r"}.get(row["pos"])
            if pos:
                for lemma in row["lemmas"]:
                    if lemma.lower() != row["form"].lower():
                        db.execute(
                            "INSERT OR IGNORE INTO forms VALUES(?,?,?)",
                            (row["form"].lower(), lemma.lower(), pos),
                        )
    db.commit()
    assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    db.execute("VACUUM")
    count = db.execute("SELECT count(*) FROM senses").fetchone()[0]
    words = db.execute("SELECT count(DISTINCT word) FROM senses").fetchone()[0]
    db.close()
    temp.replace(dest)
    return {
        "senses": count,
        "headwords": words,
        "synsets": len(chinese),
        "source": "Princeton WordNet 3.0 + Chinese Open WordNet + Chinese WordNet + "
                  "OMW Wiktionary Chinese + Open English WordNet (CILI aligned)",
        "license": "WordNet / Chinese Open WordNet CC-BY-SA-4.0 / CWN notice / "
                   "Wiktionary CC-BY-SA-3.0 / OEWN CC-BY-4.0",
        "sourceRevision": cow["cow_revision"],
        "url": "https://github.com/omwn/omw-data",
        "alignment": "https://github.com/globalwordnet/cili",
    }
