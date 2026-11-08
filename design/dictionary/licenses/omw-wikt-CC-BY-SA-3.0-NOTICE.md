# Wiktionary Mandarin (OMW) — CC BY-SA 3.0 notice

Dataset: Mandarin Chinese Wiktionary lemma-to-synset links, OMW language code `cmn`
Upstream content: Wiktionary contributors, https://www.wiktionary.org/
Distributed via: Open Multilingual WordNet (OMW), file `wns/wikt/wn-wikt-cmn.tab`
<https://github.com/omwn/omw-data/blob/main/wns/wikt/wn-wikt-cmn.tab>

Bundled source: `design/dictionary/sources-build/omw-wikt-cmn.tsv.gz`
(a gzip of the unmodified OMW tab file; the upstream `#` header is preserved)

Verbatim header of the upstream distribution:

```
# Wiktionary	cmn	http://wiktionary.org/	CC BY-SA
```

## Licence

- License: Creative Commons Attribution-ShareAlike 3.0 (CC BY-SA 3.0)
- Canonical license text: <https://creativecommons.org/licenses/by-sa/3.0/>
- The OMW distribution header records the license label `CC BY-SA`; this notice
  records the 3.0 terms used for the bundled snapshot and links the canonical
  text. The authoritative terms are the CC BY-SA 3.0 legal code at that URL.

## Attribution

- Wiktionary contributors, <https://www.wiktionary.org/> — cite the applicable
  Wiktionary headword/entry page and contributors for reused material.
- Open Multilingual WordNet, <https://omwn.org/> and
  <https://github.com/omwn/omw-data>.

## Share-alike

Derived Chinese meaning data in the generated `wordnet.sqlite` that incorporates
this source is distributed under CC BY-SA 3.0, separately from Annota's MIT
application code. Source records, transformations and hashes are retained in the
repository (`design/dictionary/`) so the derivation is reproducible.

## Annota modifications

Annota reads the `cmn:lemma` rows of each PWN30-aligned synset and merges the
Mandarin lemmas with the bundled COW (`cow`) and Chinese WordNet (`cwn`) lemmas,
deduplicating while preserving source order; the combined string is stored in
the generated `wordnet.sqlite` with `wikt` listed in the sense `sources` field.
`omw-wikt-cmn.tsv.gz` is an unmodified gzip of the OMW tab file.

This OMW Wiktionary extract is separate from the Kaikki English Wiktionary
extract that feeds `wiktionary.sqlite` (CC BY-SA 4.0, see
`Wiktionary-NOTICE.md`); the two sources have different upstream snapshots and
licence versions and must not be conflated.
