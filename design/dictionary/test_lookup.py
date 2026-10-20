import sqlite3
import unittest
from lookup import ROOT, lookup


class OfflineDictionaryTests(unittest.TestCase):
    def test_english_and_forms(self):
        self.assertTrue(lookup("attention")["entries"])
        self.assertEqual(lookup("ATTENTION")["entries"], lookup("attention")["entries"])
        self.assertTrue(lookup("running")["entries"])

    def test_chinese_and_traditional(self):
        for word in ("字典", "学习", "學習"):
            self.assertTrue(lookup(word)["entries"], word)

    def test_unknown_and_literal_query(self):
        self.assertEqual(lookup("zzzznotaword")["entries"], [])
        self.assertEqual(lookup("' OR 1=1 --")["entries"], [])
        self.assertEqual(lookup("")["entries"], [])
        self.assertLessEqual(len(lookup("attent")["suggestions"]), 12)

    def test_fuzzy_prefix_typo_and_transposition(self):
        for query in ("attenti", "attenton", "attnetion", "ttention"):
            self.assertIn("attention", lookup(query)["suggestions"], query)
        self.assertEqual(lookup("ａｔｔｅｎｔｉｏｎ")["entries"], lookup("attention")["entries"])
        self.assertTrue(lookup("字")["entries"])

    def test_read_only_files_and_integrity(self):
        for file in (ROOT / "bundle").glob("*.sqlite"):
            with sqlite3.connect(f"file:{file}?mode=ro", uri=True) as db:
                self.assertEqual(db.execute("PRAGMA integrity_check").fetchone()[0], "ok")
                with self.assertRaises(sqlite3.OperationalError):
                    db.execute("DELETE FROM entries")


if __name__ == "__main__":
    unittest.main()
