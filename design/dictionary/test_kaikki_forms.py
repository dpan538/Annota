"""Guard the extraction contract against misleading Wiktextract links."""
import gzip
import json
from pathlib import Path
import unittest

from import_kaikki_forms import pairs


class KaikkiFormTests(unittest.TestCase):
    def record(self, word="autocollimators", lemma="autocollimator", pos="noun", **changes):
        sense = {"tags": ["form-of", "plural"], "form_of": [{"word": lemma}],
                 "glosses": ["plural of " + lemma]}
        sense.update(changes)
        return {"word": word, "pos": pos, "senses": [sense]}

    def test_professional_plural_and_daily_phrase(self):
        self.assertEqual(list(pairs(self.record())),
                         [("autocollimators", "autocollimator", "noun")])
        self.assertEqual(list(pairs(self.record("came across", "come across", "verb",
                                               tags=["form-of", "past"],
                                               glosses=["simple past of come across"]))),
                         [("came across", "come across", "verb")])

    def test_excludes_alt_links_place_names_and_multiple_targets(self):
        self.assertEqual(list(pairs(self.record("Borivli", "Mumbai", "name"))), [])
        self.assertEqual(list(pairs(self.record(tags=["alt-of", "alternative"]))), [])
        self.assertEqual(list(pairs(self.record(form_of=[{"word": "first"}, {"word": "second"}]))), [])

    def test_excludes_misspelling_nonstandard_and_bad_gloss_tail(self):
        for tag in ("obsolete", "misspelling", "nonstandard", "rare", "eye-dialect"):
            self.assertEqual(list(pairs(self.record(tags=["form-of", "plural", tag]))), [])
        self.assertEqual(list(pairs(self.record(glosses=["plural of another word"]))), [])

    def test_distinct_sense_lemmas_are_preserved(self):
        record = self.record("axes", "axis")
        record["senses"] += self.record("axes", "axe")["senses"]
        self.assertEqual(list(pairs(record)), [("axes", "axis", "noun"), ("axes", "axe", "noun")])

    def test_frozen_source_has_attested_examples_and_no_bad_aliases(self):
        path = Path(__file__).parent / "sources/kaikki-forms.jsonl.gz"
        with gzip.open(path, "rt") as stream:
            rows = {(r["form"], r["lemma"]) for r in map(json.loads, stream)}
        for pair in [("autocollimators", "autocollimator"), ("perovskites", "perovskite"),
                     ("came across", "come across"), ("called the tune", "call the tune")]:
            self.assertIn(pair, rows)
        for pair in [("borivli", "mumbai"), ("dennisport", "massachusetts"), ("brung", "bring")]:
            self.assertNotIn(pair, rows)


if __name__ == "__main__":
    unittest.main()
