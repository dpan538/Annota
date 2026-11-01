import sqlite3
import unittest
from contextlib import closing
from lookup import ROOT, lookup
from build import authored_phrases, normalize


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
            with closing(sqlite3.connect(f"file:{file}?mode=ro", uri=True)) as db:
                self.assertEqual(db.execute("PRAGMA integrity_check").fetchone()[0], "ok")
                with self.assertRaises(sqlite3.OperationalError):
                    db.execute("DELETE FROM entries")

    def test_authored_terms_and_explicit_aliases_are_retrievable(self):
        phrases = authored_phrases()
        self.assertGreaterEqual(len(phrases), 900)
        self.assertGreaterEqual(len({p['category'] for p in phrases}), 9)
        for phrase in phrases:
            for query in [phrase['word']] + phrase['aliases']:
                result = lookup(query)['entries']
                self.assertTrue(result, query)
                self.assertEqual(result[0]['source'], 'annota', query)
                self.assertEqual(result[0]['key'], normalize(phrase['word']), query)
                self.assertEqual(result[0]['translation'], phrase['meaning'], query)

    def test_word_boundaries_do_not_create_false_exact_matches(self):
        self.assertEqual(lookup('set up')['entries'][0]['word'], 'set up')
        self.assertEqual(lookup('setup')['entries'][0]['word'], 'setup')
        self.assertNotEqual(lookup('set up')['entries'][0]['translation'],
                            lookup('setup')['entries'][0]['translation'])
        for query in ('setu p', 'neuralnetwork', 'takeintoaccount', 'selfattention'):
            self.assertFalse(lookup(query)['entries'], query)

    def test_hyphens_line_breaks_and_inflections(self):
        examples = {
            'SELF‑ATTENTION': 'self-attention',
            'self attention': 'self-attention',
            'self-\nattention': 'self-attention',
            'neural\nnetwork': 'neural network',
            'neural net-\nwork': 'neural network',
            'neural net‐ work': 'neural network',
            'neural networks': 'neural network',
            'carried out': 'carry out',
            'taken into account': 'take into account',
            '“machine learning,”': 'machine learning',
            'multi\u00ad-head attention': 'multi-head attention',
        }
        for query, expected in examples.items():
            self.assertEqual(lookup(query)['entries'][0]['word'], expected, query)

    def test_reverse_technical_equivalents(self):
        for query, expected in [('自注意力', 'self-attention'), ('神经网络', 'neural network'),
                                ('训练集', 'training set'), ('置信区间', 'confidence interval')]:
            self.assertIn(expected, [r['definition'] for r in lookup(query)['entries']], query)

    def test_lexical_periods_survive_selection_punctuation(self):
        for query, expected in [('i.e.', 'i.e.'), ('“i.e.,”', 'i.e.'),
                                ('P.M.', 'P.M.'), ('attention.', 'attention'),
                                ('“neural network.”', 'neural network')]:
            self.assertEqual(lookup(query)['entries'][0]['word'], expected, query)
        self.assertTrue(lookup('“学习.”')['entries'])

    def test_sentence_input_is_never_silently_truncated(self):
        query = 'attention' + ' ' * 152
        self.assertEqual(lookup(query)['entries'], [])
        self.assertEqual(lookup(query)['suggestions'], [])

    def test_aliases_point_to_existing_entries(self):
        with closing(sqlite3.connect(f'file:{ROOT / "bundle/en-zh.sqlite"}?mode=ro', uri=True)) as db:
            self.assertEqual(db.execute('PRAGMA user_version').fetchone()[0], 3)
            self.assertEqual(db.execute('SELECT count(*) FROM aliases a LEFT JOIN entries e ON e.id=a.entry_id WHERE e.id IS NULL').fetchone()[0], 0)


if __name__ == "__main__":
    unittest.main()
