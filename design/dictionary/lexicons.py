"""Offline loaders for the new open lexicon sources (build-time only, no network).

The sources themselves live in ``sources-build/`` next to this module and are
committed to git so the dictionary bundles can be rebuilt offline.  The Android
assets never ship these files; only the generated SQLite bundles are packaged.

Data contract used by every loader in this module
-------------------------------------------------
``pwn30id`` is spelled ``<8-digit offset>-<pos>`` with ``pos`` in ``n/v/a/r``.
Satellite adjectives are folded onto ``a`` so the ids line up with the bundled
COW ids (``00306663-a``) and with the OMW tab files; CILI's own
``ili-map-pwn30.tab`` spells satellite adjectives with ``-s`` and
``load_ili_map`` normalises them.  Every ``ili`` key is a CILI i-number
(``i<digits>``).

All public loaders are cached: repeated calls return the same read-only object
and never re-read or re-parse the sources.  Treat the returned values as
read-only; copy them before mutating.
"""
import gzip
import hashlib
import json
import re
import xml.etree.ElementTree as ElementTree
from collections import defaultdict
from functools import lru_cache
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SOURCES_BUILD = ROOT / "sources-build"
LICENSES = ROOT / "licenses"

#: Upstream metadata for the committed ``sources-build/`` payloads.
SOURCES = {
    "oewn": {
        "file": "oewn-2025.xml.gz",
        "url": "https://github.com/globalwordnet/english-wordnet/releases/download/2025-edition/english-wordnet-2025.xml.gz",
        "upstream": "Open English WordNet 2025 edition (WN-LMF 1.3)",
        "version": "2025",
        "license": "CC-BY-4.0",
        "licenseFile": "oewn-CC-BY-4.0.txt",
        "licenseUrl": "https://creativecommons.org/licenses/by/4.0/",
    },
    "ili": {
        "file": "ili-map-pwn30.tab.gz",
        "url": "https://raw.githubusercontent.com/globalwordnet/cili/master/ili-map-pwn30.tab",
        "upstream": "CILI ILI -> Princeton WordNet 3.0 synset map",
        "version": "CILI master (2025)",
        "license": "CC-BY-4.0",
        "licenseFile": "cili-CC-BY-4.0.txt",
        "licenseUrl": "https://creativecommons.org/licenses/by/4.0/",
    },
    "wn30": {
        "file": "wn30-synsets.tsv.gz",
        "url": "https://wordnet.princeton.edu/",
        "upstream": "Princeton WordNet 3.0 data.noun/data.verb/data.adj/data.adv (synset ids normalised to POS a)",
        "version": "3.0",
        "license": "WordNet-3.0",
        "licenseFile": "wordnet-3.0-license.txt",
        "licenseUrl": "https://wordnet.princeton.edu/license-and-commercial-use",
    },
    "wn30-sense-order": {
        "file": "wn30-sense-order.tsv.gz",
        "url": "https://wordnet.princeton.edu/",
        "upstream": "Princeton WordNet 3.0 index.sense sense_number (frequency rank) order",
        "version": "3.0",
        "license": "WordNet-3.0",
        "licenseFile": "wordnet-3.0-license.txt",
        "licenseUrl": "https://wordnet.princeton.edu/license-and-commercial-use",
    },
    "omw-cwn": {
        "file": "omw-cwn.tsv.gz",
        "url": "https://github.com/omwn/omw-data/blob/main/wns/cwn/wn-data-qcn.tab",
        "upstream": "Chinese WordNet (Taiwan) via Open Multilingual WordNet, wns/cwn/wn-data-qcn.tab",
        "version": "OMW data snapshot",
        "license": "CWN",
        "licenseFile": "cwn-Academia-Sinica-license.txt",
        "licenseUrl": "http://lope.linguistics.ntu.edu.tw/cwn/",
    },
    "omw-wikt-cmn": {
        "file": "omw-wikt-cmn.tsv.gz",
        "url": "https://github.com/omwn/omw-data/blob/main/wns/wikt/wn-wikt-cmn.tab",
        "upstream": "Wiktionary Mandarin lemmas via Open Multilingual WordNet, wns/wikt/wn-wikt-cmn.tab",
        "version": "OMW data snapshot",
        "license": "CC-BY-SA-3.0",
        "licenseFile": "omw-wikt-CC-BY-SA-3.0-NOTICE.md",
        "licenseUrl": "https://creativecommons.org/licenses/by-sa/3.0/",
    },
    "opencc": {
        "file": "opencc-tscharacters.txt.gz",
        "url": "https://github.com/BYVoid/OpenCC/blob/master/data/dictionary/TSCharacters.txt",
        "upstream": "OpenCC TSCharacters traditional -> simplified character table",
        "version": "OpenCC data snapshot",
        "license": "Apache-2.0",
        "licenseFile": "opencc-Apache-2.0.txt",
        "licenseUrl": "https://www.apache.org/licenses/LICENSE-2.0",
    },
}

#: ``sources-build/`` files that must exist even when no loader reads them.
SOURCE_FILES = tuple(entry["file"] for entry in SOURCES.values())

_POS = {"n": "n", "v": "v", "a": "a", "r": "r", "s": "a",
        "1": "n", "2": "v", "3": "a", "4": "r", "5": "a"}
_ILI_RE = re.compile(r"i\d+\Z")
_SYNSET_RE = re.compile(r"(\d{8})-([a-z])\Z")
_POSITIVE_INT = re.compile(r"[1-9]\d*\Z")

#: Filled in by the loaders, consumed by :func:`report` (all plain data).
_STATS = {}


class LexiconSourceError(ValueError):
    """Raised when a committed source file is missing or malformed."""


def _source_path(name):
    entry = SOURCES.get(name)
    if entry is None:
        raise KeyError(f"Unknown lexicon source: {name!r}; known: {sorted(SOURCES)}")
    path = SOURCES_BUILD / entry["file"]
    if not path.is_file():
        raise FileNotFoundError(
            f"Missing lexicon source {path}. Committed sources-build files are "
            f"required for offline rebuilds; restore {entry['file']} from git "
            f"or regenerate it from {entry['url']}.")
    return path


def _gzip_lines(name):
    """Yield decoded lines from a committed gzip source, one at a time."""
    path = _source_path(name)
    with gzip.open(path, "rt", encoding="utf-8", newline="") as handle:
        for line in handle:
            yield line


def _project_synset_id(raw):
    """Normalise a WN3.0 synset id to Annota's ``offset-{n,v,a,r}`` spelling."""
    match = _SYNSET_RE.match(raw)
    if not match:
        raise LexiconSourceError(f"Malformed synset id: {raw!r}")
    offset, last = match.groups()
    return f"{offset}-{_POS.get(last, last)}"


def _normalise_pos(raw):
    pos = _POS.get(raw)
    if pos is None:
        raise LexiconSourceError(f"Unsupported part of speech: {raw!r}")
    return pos


def _normalise_lemma(raw):
    # WordNet and OMW spell multi-word lemmas with underscores.
    return " ".join(raw.replace("_", " ").split())


def _decode_definition(element):
    return " ".join("".join(element.itertext()).split())


def _iterparse_gzip(path):
    """Yield ``(event, element)`` pairs from a gzipped XML file."""
    with gzip.open(path, "rb") as handle:
        for event, element in ElementTree.iterparse(handle, events=("start", "end")):
            yield event, element


@lru_cache(maxsize=None)
def load_ili_map():
    """Return ``{pwn30id: ilinumber}`` for the CILI -> PWN30 alignment map.

    The upstream file spells satellite adjectives with ``-s``; they are folded
    onto ``-a`` here so ids match the bundled COW ids and the OMW tab files.
    """
    mapping = {}
    lines = 0
    folded = 0
    for line in _gzip_lines("ili"):
        line = line.rstrip("\n")
        if not line or line.startswith("#"):
            continue
        fields = line.split("\t")
        if len(fields) != 2:
            raise LexiconSourceError(f"Malformed ILI map line: {line!r}")
        ili, raw_id = fields
        if not _ILI_RE.match(ili):
            raise LexiconSourceError(f"Malformed ILI id: {ili!r}")
        lines += 1
        synset = _project_synset_id(raw_id)
        if raw_id.endswith("-s"):
            folded += 1
        if synset in mapping:
            raise LexiconSourceError(f"Duplicate PWN30 id after normalisation: {synset}")
        mapping[synset] = ili
    _STATS["ili"] = {"synsets": len(mapping), "lines": lines,
                     "satellite_ids_folded": folded}
    return mapping


@lru_cache(maxsize=None)
def load_oewn():
    """Parse Open English WordNet 2025 into ILI-keyed synsets and lemmas.

    Returns ``{'synsets': {ili: {'id', 'pos', 'gloss'}},
    'lemmas': {ili: {(word, pos), ...}}}``.  Synsets without a usable CILI
    ``ili`` attribute cannot be aligned to PWN30 and are counted in
    :func:`report` instead of being returned.
    """
    synsets = {}
    lemmas = defaultdict(set)
    synset_ili = {}
    stats = {"synsets": 0, "unmapped_ili_synsets": 0, "duplicate_ili": 0,
             "lexical_entries": 0, "senses": 0, "gloss_missing": 0,
             "unmapped_senses": 0, "unmapped_synset_samples": []}
    # LexicalEntry elements precede Synset elements in the WN-LMF document, so
    # Sense -> synset links are resolved after the single streaming pass.
    senses = []
    all_lemma_pairs = set()
    unmapped_lemmas = set()
    current_entry = None
    for event, element in _iterparse_gzip(_source_path("oewn")):
        tag = element.tag.rpartition("}")[2]
        if event == "start":
            if tag == "LexicalEntry":
                current_entry = {"lemma": None, "pos": None}
            continue
        if tag == "Synset":
            stats["synsets"] += 1
            raw_ili = (element.get("ili") or "").strip()
            pos = _normalise_pos((element.get("partOfSpeech") or "").strip())
            definitions = [child for child in element
                           if child.tag.rpartition("}")[2] == "Definition"]
            gloss = _decode_definition(definitions[0]) if definitions else ""
            if not gloss:
                stats["gloss_missing"] += 1
            synset_id = element.get("id")
            if _ILI_RE.match(raw_ili):
                if raw_ili in synsets:
                    # Keep the first synset and report the collision; CILI is 1:1.
                    stats["duplicate_ili"] += 1
                else:
                    synsets[raw_ili] = {"id": synset_id, "pos": pos, "gloss": gloss}
                if synset_id is not None:
                    synset_ili[synset_id] = raw_ili
            else:
                stats["unmapped_ili_synsets"] += 1
                if len(stats["unmapped_synset_samples"]) < 5:
                    stats["unmapped_synset_samples"].append(
                        {"id": synset_id, "ili": raw_ili, "pos": pos})
            element.clear()
        elif tag == "Lemma" and current_entry is not None:
            current_entry["lemma"] = _normalise_lemma(element.get("writtenForm") or "")
            current_entry["pos"] = _normalise_pos((element.get("partOfSpeech") or "").strip())
        elif tag == "Sense" and current_entry is not None:
            lemma = current_entry["lemma"]
            if lemma:
                stats["senses"] += 1
                senses.append((element.get("synset"), lemma, current_entry["pos"]))
        elif tag == "LexicalEntry":
            stats["lexical_entries"] += 1
            current_entry = None
            element.clear()
    for synset_ref, lemma, pos in senses:
        all_lemma_pairs.add((lemma, pos))
        ili = synset_ili.get(synset_ref)
        if ili is not None:
            lemmas[ili].add((lemma, pos))
        else:
            # No CILI alignment: the lemma cannot be attached to a PWN30 synset.
            stats["unmapped_senses"] += 1
            unmapped_lemmas.add((lemma, pos))
    senses = None
    # Lemmas attached to unmapped synsets are not reachable through an ILI;
    # count them so report() can show how much English synonym material is dropped.
    stats["distinct_lemma_pairs"] = len(all_lemma_pairs)
    stats["lemma_associations"] = sum(len(pairs) for pairs in lemmas.values())
    stats["unmapped_lemma_pairs"] = len(unmapped_lemmas)
    stats["mapped_synsets"] = len(synsets)
    _STATS["oewn"] = stats
    return {"synsets": synsets, "lemmas": dict(lemmas)}


@lru_cache(maxsize=None)
def load_wn30():
    """Load Princeton WordNet 3.0 synsets and per-lemma frequency order.

    Returns ``{'synsets': {pwn30id: {'pos', 'lemmas', 'gloss'}},
    'sense_order': {(word, pos): [pwn30id, ...]}}``.  ``sense_order`` is
    frequency order (WordNet sense number 1 first), not physical file order.
    """
    synsets = {}
    for line in _gzip_lines("wn30"):
        line = line.rstrip("\n")
        if not line or line.startswith("#"):
            continue
        fields = line.split("\t")
        if len(fields) != 4:
            raise LexiconSourceError(f"Malformed WordNet synset line: {line[:80]!r}")
        raw_id, raw_pos, raw_lemmas, gloss = fields
        synset_id = _project_synset_id(raw_id)
        if synset_id in synsets:
            raise LexiconSourceError(f"Duplicate WordNet synset id: {synset_id}")
        lemmas = [lemma for lemma in (_normalise_lemma(item)
                                      for item in raw_lemmas.split("|")) if lemma]
        synsets[synset_id] = {"pos": _normalise_pos(raw_pos),
                              "lemmas": lemmas, "gloss": gloss}
    sense_order = {}
    for line in _gzip_lines("wn30-sense-order"):
        line = line.rstrip("\n")
        if not line or line.startswith("#"):
            continue
        fields = line.split("\t")
        if len(fields) != 3:
            raise LexiconSourceError(f"Malformed WordNet sense-order line: {line[:80]!r}")
        word, raw_pos, ids = fields
        if not word or not ids:
            raise LexiconSourceError(f"Empty WordNet sense-order entry: {line[:80]!r}")
        key = (word, _normalise_pos(raw_pos))
        if key in sense_order:
            raise LexiconSourceError(f"Duplicate WordNet sense-order key: {key}")
        ordered = tuple(_project_synset_id(item) for item in ids.split(" "))
        for synset_id in ordered:
            if synset_id not in synsets:
                raise LexiconSourceError(
                    f"WordNet sense order references unknown synset {synset_id} ({word}/{key[1]})")
        if len(ordered) != len(set(ordered)):
            raise LexiconSourceError(f"Duplicate synset in WordNet sense order: {key}")
        sense_order[key] = list(ordered)
    _STATS["wn30"] = {"synsets": len(synsets),
                      "sense_groups": len(sense_order),
                      "senses": sum(len(ids) for ids in sense_order.values()),
                      "known_pos": sorted({entry["pos"] for entry in synsets.values()})}
    return {"synsets": synsets, "sense_order": sense_order}


@lru_cache(maxsize=None)
def load_omw_chinese():
    """Merge the OMW Chinese WordNets into ``{pwn30id: {lemmas, sources}}``.

    Lemmas keep first-seen order (CWN before Wiktionary) and are deduplicated;
    ``sources`` lists ``'cwn'`` and/or ``'wikt'`` in that order.
    """
    combined = {}
    stats = {"cwn_synsets": 0, "wikt_synsets": 0, "cwn_lemma_lines": 0,
             "wikt_lemma_lines": 0, "duplicate_lemma_lines": 0,
             "invalid_lines": 0}
    for source, name in (("cwn", "omw-cwn"), ("wikt", "omw-wikt-cmn")):
        for line in _gzip_lines(name):
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            fields = line.split("\t")
            if len(fields) != 3 or not fields[2].strip():
                stats["invalid_lines"] += 1
                raise LexiconSourceError(f"Malformed OMW {source} line: {line[:80]!r}")
            raw_id, relation, raw_lemma = fields
            if not relation.endswith(":lemma"):
                stats["invalid_lines"] += 1
                raise LexiconSourceError(f"Unsupported OMW relation {relation!r} in {source}")
            synset_id = _project_synset_id(raw_id)
            lemma = _normalise_lemma(raw_lemma)
            entry = combined.setdefault(synset_id, {"lemmas": [], "sources": []})
            if source not in entry["sources"]:
                entry["sources"].append(source)
            stats[f"{source}_lemma_lines"] += 1
            if lemma in entry["lemmas"]:
                stats["duplicate_lemma_lines"] += 1
                continue
            entry["lemmas"].append(lemma)
    stats["cwn_synsets"] = sum(1 for entry in combined.values() if "cwn" in entry["sources"])
    stats["wikt_synsets"] = sum(1 for entry in combined.values() if "wikt" in entry["sources"])
    stats["synsets"] = len(combined)
    stats["lemma_total"] = sum(len(entry["lemmas"]) for entry in combined.values())
    _STATS["omw"] = stats
    return combined


@lru_cache(maxsize=None)
def load_opencc_variants():
    """Return ``[(traditional, simplified), ...]`` single-character mappings.

    The first simplified candidate of each upstream line is used, matching the
    ``traditional -> simplified`` variant table required by ``zh-en.sqlite``.
    """
    variants = []
    seen = set()
    stats = {"mappings": 0, "identity": 0, "multi_char_skipped": 0, "duplicates": 0}
    for line in _gzip_lines("opencc"):
        line = line.rstrip("\n")
        if not line or line.startswith("#"):
            continue
        fields = line.split("\t")
        if len(fields) != 2:
            raise LexiconSourceError(f"Malformed OpenCC line: {line[:80]!r}")
        traditional, candidates = fields
        first = candidates.split()
        if not first:
            raise LexiconSourceError(f"Empty OpenCC candidate list: {line[:80]!r}")
        simplified = first[0]
        if len(traditional) != 1 or len(simplified) != 1:
            stats["multi_char_skipped"] += 1
            continue
        if traditional in seen:
            stats["duplicates"] += 1
            continue
        seen.add(traditional)
        if traditional == simplified:
            stats["identity"] += 1
        variants.append((traditional, simplified))
    stats["mappings"] = len(variants)
    _STATS["opencc"] = stats
    return variants


def _sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


@lru_cache(maxsize=1)
def report():
    """Return a JSON-serialisable provenance/count/licence summary.

    The structure is meant to be merged into ``manifest.json`` by the caller:
    ``sources`` (file sizes, sha256, upstream URL/version, licence),
    ``counts`` (loader record counts), ``alignment`` (projection / unmapped
    anomalies) and ``licenses`` (bundled licence file hashes).
    """
    # Force every loader so counts and anomalies are always present.
    ili_map = load_ili_map()
    oewn = load_oewn()
    load_wn30()
    load_omw_chinese()
    load_opencc_variants()

    sources = {}
    for name, meta in SOURCES.items():
        path = _source_path(name)
        sources[name] = {
            "file": meta["file"],
            "bytes": path.stat().st_size,
            "sha256": _sha256(path),
            "url": meta["url"],
            "upstream": meta["upstream"],
            "version": meta["version"],
            "license": meta["license"],
            "licenseFile": meta["licenseFile"],
            "licenseUrl": meta["licenseUrl"],
        }

    licenses = {}
    for meta in SOURCES.values():
        path = LICENSES / meta["licenseFile"]
        if not path.is_file():
            raise FileNotFoundError(
                f"Missing license file {path} required by {meta['file']}")
        licenses[meta["licenseFile"]] = {
            "bytes": path.stat().st_size,
            "sha256": _sha256(path),
            "license": meta["license"],
            "licenseUrl": meta["licenseUrl"],
        }

    oewn_stats = _STATS["oewn"]
    wn30_stats = _STATS["wn30"]
    omw_stats = _STATS["omw"]
    ili_stats = _STATS["ili"]
    opencc_stats = _STATS["opencc"]

    counts = {
        "iliMap": ili_stats["synsets"],
        "oewnSynsets": len(oewn["synsets"]),
        "oewnSynsetsInSource": oewn_stats["synsets"],
        "oewnLemmas": oewn_stats["distinct_lemma_pairs"],
        "oewnLemmaAssociations": oewn_stats["lemma_associations"],
        "oewnLexicalEntries": oewn_stats["lexical_entries"],
        "oewnSenses": oewn_stats["senses"],
        "wn30Synsets": wn30_stats["synsets"],
        "wn30SenseGroups": wn30_stats["sense_groups"],
        "wn30Senses": wn30_stats["senses"],
        "omwChineseSynsets": omw_stats["synsets"],
        "omwCwnSynsets": omw_stats["cwn_synsets"],
        "omwWiktSynsets": omw_stats["wikt_synsets"],
        "omwChineseLemmas": omw_stats["lemma_total"],
        "openccCharMappings": opencc_stats["mappings"],
    }
    # OEWN 2025 ids look like PWN30 ids but mostly use OEWN offsets; synsets
    # must be aligned through their CILI ILI, never by offset equality.
    pwn30_by_ili = {ili: synset for synset, ili in ili_map.items()}
    oewn_offset_matches = sum(
        1 for ili, entry in oewn["synsets"].items()
        if entry["id"].removeprefix("oewn-").split("-")[0]
        == pwn30_by_ili[ili].split("-")[0])
    alignment = {
        "pwn30IdConvention": "offset-{n,v,a,r}; satellite adjectives folded from CILI -s to -a",
        "oewnAlignmentKey": "CILI `ili` attribute (OEWN synset ids mostly use OEWN offsets)",
        "oewnSynsetIdsMatchingPwn30Offset": oewn_offset_matches,
        "iliSatelliteIdsFolded": ili_stats["satellite_ids_folded"],
        "oewnUnmappedIliSynsets": oewn_stats["unmapped_ili_synsets"],
        "oewnUnmappedSynsetSamples": oewn_stats["unmapped_synset_samples"],
        "oewnDuplicateIli": oewn_stats["duplicate_ili"],
        "oewnGlossMissing": oewn_stats["gloss_missing"],
        "oewnUnmappedSenses": oewn_stats["unmapped_senses"],
        "oewnUnmappedLemmaPairs": oewn_stats["unmapped_lemma_pairs"],
        "oewnIliCoverageOfWn30": round(len(oewn["synsets"]) / max(
            1, ili_stats["synsets"]), 6),
        "omwDuplicateLemmaLines": omw_stats["duplicate_lemma_lines"],
        "openccIdentityMappings": opencc_stats["identity"],
        "wn30Pos": wn30_stats["known_pos"],
    }
    return {
        "schema": 1,
        "networkRequired": False,
        "sources": sources,
        "counts": counts,
        "alignment": alignment,
        "licenses": licenses,
    }


def dumps_report(indent=2):
    """Return :func:`report` as stable, JSON-serialisable text."""
    return json.dumps(report(), ensure_ascii=False, indent=indent, sort_keys=True)


__all__ = [
    "ROOT", "SOURCES_BUILD", "LICENSES", "SOURCES", "SOURCE_FILES",
    "LexiconSourceError", "load_ili_map", "load_oewn", "load_wn30",
    "load_omw_chinese", "load_opencc_variants", "report", "dumps_report",
]
