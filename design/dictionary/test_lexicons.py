#!/usr/bin/env python3
"""Verify the offline lexicon sources and the ``lexicons`` loaders.

Run directly so the dictionary bundles can be audited without network access:

    python3 design/dictionary/test_lexicons.py
"""
import gzip
import json
import re
import sys
import time
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

import lexicons  # noqa: E402  (import after sys.path setup)

GZIP_MAGIC = b"\x1f\x8b"
SYNSET_ID = re.compile(r"\d{8}-[nvar]\Z")
ILI_ID = re.compile(r"i\d+\Z")


class AFirstLoadTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        started = time.perf_counter()
        cls.ili = lexicons.load_ili_map()
        cls.oewn = lexicons.load_oewn()
        cls.wn30 = lexicons.load_wn30()
        cls.omw = lexicons.load_omw_chinese()
        cls.variants = lexicons.load_opencc_variants()
        cls.first_load_seconds = time.perf_counter() - started
        cls.report = lexicons.report()
        print(f"\n[lexicons] first load: {cls.first_load_seconds:.2f}s")

    def test_sources_build_files_are_committed_and_reproducible(self):
        expected = {
            "oewn-2025.xml.gz", "ili-map-pwn30.tab.gz", "wn30-synsets.tsv.gz",
            "wn30-sense-order.tsv.gz", "omw-cwn.tsv.gz", "omw-wikt-cmn.tsv.gz",
            "opencc-tscharacters.txt.gz",
        }
        self.assertEqual(set(lexicons.SOURCE_FILES), expected)
        for name in lexicons.SOURCE_FILES:
            path = lexicons.SOURCES_BUILD / name
            self.assertTrue(path.is_file(), name)
            self.assertGreater(path.stat().st_size, 1000, name)
            with path.open("rb") as handle:
                header = handle.read(10)
            self.assertEqual(header[:2], GZIP_MAGIC, name)
            # mtime=0 and no embedded filename: rebuilding yields identical bytes.
            self.assertEqual(int.from_bytes(header[4:8], "little"), 0, name)
            self.assertEqual(header[3] & 0x08, 0, name)

    def test_licence_files_are_committed_with_upstream_text(self):
        licences = {}
        for meta in lexicons.SOURCES.values():
            path = lexicons.LICENSES / meta["licenseFile"]
            self.assertTrue(path.is_file(), meta["licenseFile"])
            self.assertGreater(path.stat().st_size, 200, meta["licenseFile"])
            licences[meta["licenseFile"]] = path.read_text(encoding="utf-8")

        self.assertIn("Attribution 4.0 International", licences["oewn-CC-BY-4.0.txt"])
        self.assertIn("https://creativecommons.org/licenses/by/4.0/",
                      licences["oewn-CC-BY-4.0.txt"])
        self.assertIn("2025-edition/english-wordnet-2025.xml.gz",
                      licences["oewn-CC-BY-4.0.txt"])
        self.assertIn("Princeton University", licences["wordnet-3.0-license.txt"])
        self.assertIn("WordNet 3.0", licences["wordnet-3.0-license.txt"])
        self.assertIn("lope.linguistics.ntu.edu.tw/cwn",
                      licences["cwn-Academia-Sinica-license.txt"])
        self.assertIn("CC BY-SA 3.0", licences["omw-wikt-CC-BY-SA-3.0-NOTICE.md"])
        self.assertIn("creativecommons.org/licenses/by-sa/3.0",
                      licences["omw-wikt-CC-BY-SA-3.0-NOTICE.md"])
        self.assertIn("Apache License", licences["opencc-Apache-2.0.txt"])
        self.assertIn("Version 2.0", licences["opencc-Apache-2.0.txt"])
        self.assertIn("http://www.apache.org/licenses/LICENSE-2.0",
                      licences["opencc-Apache-2.0.txt"])

    def test_public_api_is_offline_cached_and_quick_on_repeat(self):
        # No networking modules are imported by the loader module.
        self.assertEqual(self.first_load_seconds, self.first_load_seconds)
        self.assertLess(self.first_load_seconds, 120.0)
        self.assertIs(lexicons.load_ili_map(), self.ili)
        self.assertIs(lexicons.load_oewn(), self.oewn)
        self.assertIs(lexicons.load_wn30(), self.wn30)
        self.assertIs(lexicons.load_omw_chinese(), self.omw)
        self.assertIs(lexicons.load_opencc_variants(), self.variants)
        self.assertIs(lexicons.report(), self.report)
        started = time.perf_counter()
        lexicons.load_oewn()
        lexicons.load_wn30()
        lexicons.report()
        self.assertLess(time.perf_counter() - started, 0.05)
        source = Path(lexicons.__file__).read_text(encoding="utf-8")
        for module in ("urllib", "requests", "httpx", "socket", "http.client"):
            self.assertNotIn(module, source.split("__all__")[0])


class IliMapTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ili = lexicons.load_ili_map()
        cls.report = lexicons.report()

    def test_coverage_and_samples(self):
        self.assertGreater(len(self.ili), 100_000)
        self.assertEqual(len(self.ili), 117_659)
        self.assertEqual(self.ili["00001740-a"], "i1")
        self.assertEqual(self.ili["00001740-n"], "i35545")
        # CILI spells satellite adjectives with -s; the project contract folds
        # them onto the COW/OMW POS spelling -a.
        self.assertEqual(self.ili["00306663-a"], "i1695")

    def test_keys_and_values_are_well_formed(self):
        for synset, ili in self.ili.items():
            self.assertRegex(synset, SYNSET_ID)
            self.assertRegex(ili, ILI_ID)
        self.assertEqual(self.report["alignment"]["iliSatelliteIdsFolded"], 10_693)


class OewnTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ili = lexicons.load_ili_map()
        cls.oewn = lexicons.load_oewn()
        cls.report = lexicons.report()

    def test_synset_coverage_and_samples(self):
        self.assertGreater(len(self.oewn["synsets"]), 100_000)
        self.assertEqual(len(self.oewn["synsets"]), 104_335)
        self.assertEqual(self.report["counts"]["oewnSynsetsInSource"], 107_519)
        able = self.oewn["synsets"]["i1"]
        self.assertEqual(able["id"], "oewn-00001740-a")
        self.assertEqual(able["pos"], "a")
        self.assertIn("means or skill", able["gloss"])
        self.assertIn(("able", "a"), self.oewn["lemmas"]["i1"])

    def test_every_returned_synset_has_a_valid_cili_alignment(self):
        ilis = set(self.ili.values())
        self.assertEqual(set(self.oewn["synsets"]) - ilis, set())
        for entry in self.oewn["synsets"].values():
            self.assertIn(entry["pos"], "nvar")
            self.assertTrue(entry["gloss"], entry["id"])

    def test_lemma_totals(self):
        # OEWN houses ~135k lemmas; the ILI-keyed map stores the ones that can
        # be aligned to PWN30 (the rest are counted in report()).
        self.assertGreaterEqual(self.report["counts"]["oewnLexicalEntries"], 130_000)
        self.assertLessEqual(self.report["counts"]["oewnLexicalEntries"], 140_000)
        self.assertGreaterEqual(self.report["counts"]["oewnLemmas"], 130_000)
        self.assertLessEqual(self.report["counts"]["oewnLemmas"], 140_000)
        self.assertEqual(self.report["counts"]["oewnLemmaAssociations"],
                         sum(len(pairs) for pairs in self.oewn["lemmas"].values()))

    def test_unmapped_synsets_are_reported(self):
        # OEWN 2025 uses ili="in" on synsets with only an OEWN-internal
        # definition; they cannot be aligned to PWN30 and must be surfaced.
        alignment = self.report["alignment"]
        self.assertEqual(alignment["oewnUnmappedIliSynsets"], 3_184)
        self.assertEqual(alignment["oewnDuplicateIli"], 0)
        self.assertEqual(alignment["oewnGlossMissing"], 0)
        self.assertTrue(alignment["oewnUnmappedSynsetSamples"])
        # OEWN renumbers most synsets: align by ILI, never by offset equality.
        self.assertLess(alignment["oewnSynsetIdsMatchingPwn30Offset"], 1_000)


class WordNet30Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ili = lexicons.load_ili_map()
        cls.wn30 = lexicons.load_wn30()
        cls.report = lexicons.report()

    def test_synset_coverage_and_samples(self):
        synsets = self.wn30["synsets"]
        self.assertGreater(len(synsets), 100_000)
        self.assertEqual(len(synsets), 117_659)
        entity = synsets["00001740-n"]
        self.assertEqual(entity["pos"], "n")
        self.assertEqual(entity["lemmas"], ["entity"])
        self.assertIn("that which is perceived", entity["gloss"])
        dog = synsets["02084071-n"]
        self.assertEqual(dog["lemmas"], ["dog", "domestic dog", "Canis familiaris"])
        # Satellite adjectives normalise to POS a but keep the PWN30 offset.
        able = synsets["00306663-a"]
        self.assertEqual(able["pos"], "a")
        self.assertEqual(able["lemmas"], ["able"])

    def test_wn30_ids_align_with_the_cili_map(self):
        self.assertEqual(set(self.wn30["synsets"]), set(self.ili))
        for entry in self.wn30["synsets"].values():
            self.assertIn(entry["pos"], "nvar")
            self.assertTrue(entry["lemmas"], entry["pos"])
            for lemma in entry["lemmas"]:
                self.assertNotIn("_", lemma)

    def test_frequency_order_matches_wordnet_sense_numbers(self):
        order = self.wn30["sense_order"]
        # index.adj lists able's senses as 00001740-a (1), 00510348-a (2),
        # 00306663-a (3), 01017439-a (4) -- not the physical index.sense order.
        self.assertEqual(order[("able", "a")],
                         ["00001740-a", "00510348-a", "00306663-a", "01017439-a"])
        self.assertEqual(order[("cut", "n")][0], "13289159-n")
        self.assertEqual(len(order[("cut", "n")]), 20)
        self.assertEqual(order[("a", "n")][0], "13658027-n")
        self.assertEqual(self.report["counts"]["wn30SenseGroups"], 155_287)
        self.assertEqual(self.report["counts"]["wn30Senses"], 206_941)
        for ids in order.values():
            self.assertEqual(len(ids), len(set(ids)))
            for synset in ids:
                self.assertIn(synset, self.wn30["synsets"])


class OmwChineseTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.wn30 = lexicons.load_wn30()
        cls.omw = lexicons.load_omw_chinese()
        cls.report = lexicons.report()

    def test_per_source_coverage(self):
        counts = self.report["counts"]
        self.assertGreaterEqual(counts["omwCwnSynsets"], 4_800)
        self.assertLessEqual(counts["omwCwnSynsets"], 5_000)
        self.assertGreaterEqual(counts["omwWiktSynsets"], 12_000)
        self.assertLessEqual(counts["omwWiktSynsets"], 12_200)
        self.assertEqual(counts["omwCwnSynsets"], 4_913)
        self.assertEqual(counts["omwWiktSynsets"], 12_130)

    def test_samples_and_source_labels(self):
        breathing = self.omw["00001740-v"]
        self.assertEqual(breathing["lemmas"][0], "呼吸")
        self.assertEqual(breathing["sources"], ["cwn", "wikt"])
        not_do = self.omw["00002098-a"]
        self.assertEqual(not_do["lemmas"], ["莫"])
        self.assertEqual(not_do["sources"], ["cwn"])
        dying = self.omw["00004171-a"]
        self.assertEqual(dying["sources"], ["wikt"])
        self.assertIn("垂死", dying["lemmas"])

    def test_entries_are_well_formed_and_aligned(self):
        self.assertEqual(set(self.omw) - set(self.wn30["synsets"]), set())
        for synset, entry in self.omw.items():
            self.assertRegex(synset, re.compile(r"\d{8}-[nvar]\Z"))
            self.assertTrue(entry["lemmas"], synset)
            self.assertEqual(entry["sources"],
                             [source for source in ("cwn", "wikt")
                              if source in entry["sources"]])
            self.assertTrue(entry["sources"])
            for lemma in entry["lemmas"]:
                self.assertNotIn("_", lemma)
                self.assertEqual(lemma, lemma.strip())


class OpenCcTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.variants = lexicons.load_opencc_variants()
        cls.report = lexicons.report()

    def test_single_character_mappings(self):
        mapping = dict(self.variants)
        self.assertEqual(len(self.variants), len(mapping))
        self.assertEqual(len(self.variants), 4_148)
        self.assertEqual(mapping["學"], "学")
        self.assertEqual(mapping["體"], "体")
        self.assertEqual(mapping["漢"], "汉")
        for traditional, simplified in self.variants:
            self.assertEqual(len(traditional), 1, traditional)
            self.assertEqual(len(simplified), 1, simplified)
        self.assertEqual(self.report["alignment"]["openccIdentityMappings"], 927)


class ReportTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.report = lexicons.report()

    def test_report_is_json_serialisable_and_stable(self):
        first = lexicons.dumps_report()
        second = lexicons.dumps_report()
        self.assertEqual(first, second)
        json.loads(first)
        self.assertFalse(self.report["networkRequired"])
        self.assertEqual(self.report["schema"], 1)

    def test_report_covers_sources_and_licences(self):
        self.assertEqual(set(self.report["sources"]), set(lexicons.SOURCES))
        for meta in self.report["sources"].values():
            path = lexicons.SOURCES_BUILD / meta["file"]
            self.assertEqual(meta["bytes"], path.stat().st_size)
            self.assertEqual(len(meta["sha256"]), 64)
            self.assertTrue(meta["url"].startswith("https://"))
            self.assertTrue(meta["licenseFile"])
        for name in ("oewn-CC-BY-4.0.txt", "wordnet-3.0-license.txt",
                     "cwn-Academia-Sinica-license.txt",
                     "omw-wikt-CC-BY-SA-3.0-NOTICE.md", "opencc-Apache-2.0.txt"):
            self.assertIn(name, self.report["licenses"])
            self.assertEqual(len(self.report["licenses"][name]["sha256"]), 64)

    def test_report_counts_match_loader_results(self):
        counts = self.report["counts"]
        self.assertEqual(counts["iliMap"], len(lexicons.load_ili_map()))
        self.assertEqual(counts["oewnSynsets"],
                         len(lexicons.load_oewn()["synsets"]))
        self.assertEqual(counts["wn30Synsets"],
                         len(lexicons.load_wn30()["synsets"]))
        self.assertEqual(counts["omwChineseSynsets"],
                         len(lexicons.load_omw_chinese()))
        self.assertEqual(counts["openccCharMappings"],
                         len(lexicons.load_opencc_variants()))


class ZReproducibilityTests(unittest.TestCase):
    def test_reparsing_sources_is_deterministic(self):
        before = lexicons.dumps_report()
        started = time.perf_counter()
        for loader in (lexicons.load_ili_map, lexicons.load_oewn, lexicons.load_wn30,
                       lexicons.load_omw_chinese, lexicons.load_opencc_variants,
                       lexicons.report):
            loader.cache_clear()
        after = lexicons.dumps_report()
        elapsed = time.perf_counter() - started
        self.assertEqual(before, after)
        self.assertLess(elapsed, 120.0)
        json.loads(after)


if __name__ == "__main__":
    unittest.main(verbosity=2)
