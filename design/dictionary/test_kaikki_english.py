import collections
import gzip
import json
from pathlib import Path
import unittest

from import_kaikki_english import definitions, digest


def entry(word, gloss, **sense_fields):
    return {"word": word, "pos": "noun", "senses": [{"glosses": [gloss], **sense_fields}]}


class EnglishDefinitionTests(unittest.TestCase):
    def test_substantive_definition_kept_without_truncation(self):
        gloss = "A plain text file used by an operating system to map hostnames to IP addresses."
        self.assertEqual(list(definitions(entry("hosts file", gloss)))[0]["gloss"], gloss)

    def test_cross_references_and_forms_rejected(self):
        for gloss in ["Synonym of a complicated technical term.",
                      "Alternative spelling of another long expression.",
                      "Used other than figuratively or idiomatically: see economic, rationalism.",
                      "The action of one who drops in.",
                      "To convert to a tandemer."]:
            self.assertEqual(list(definitions(entry("sample", gloss))), [], gloss)
        self.assertEqual(list(definitions(entry("samples", "A clear explanation of the plural form.",
                                               form_of=[{"word": "sample"}]))), [])

    def test_names_archaic_and_long_glosses_rejected(self):
        for record in [entry("Example", "An ordinary item used to demonstrate something."),
                       entry("example", "A town in a distant country near the sea."),
                       entry("example", "A former expression for an ordinary item.", tags=["archaic"]),
                       entry("example", "An offensive description applied to a person.", tags=["offensive"]),
                       entry("example", "To sing in this manner with a particular voice."),
                       entry("example", "word " * 101)]:
            self.assertEqual(list(definitions(record)), [])

    def test_frozen_source_integrity_and_bounded_senses(self):
        sources = Path(__file__).with_name("sources")
        path = sources / "kaikki-en-definitions.jsonl.gz"
        metadata = json.loads((sources / "kaikki-en-definitions-provenance.json").read_text())
        self.assertEqual(digest(path), metadata["extract_sha256"])
        counts = collections.Counter()
        found = {}
        with gzip.open(path, "rt") as stream:
            for line in stream:
                row = json.loads(line)
                counts[row["word"]] += 1
                if row["word"] in {"hosts file", "clump up", "hematitization"}:
                    found[row["word"]] = row["gloss"]
                self.assertTrue(20 <= len(row["gloss"]) <= 500)
        self.assertLessEqual(max(counts.values()), 3)
        self.assertEqual(len(counts), metadata["headwords"])
        self.assertEqual(sum(counts.values()), metadata["senses"])
        self.assertEqual(set(found), {"hosts file", "clump up", "hematitization"})
        self.assertIn("hostnames", found["hosts file"])
        self.assertIn("mineral", found["hematitization"])


if __name__ == "__main__":
    unittest.main()
