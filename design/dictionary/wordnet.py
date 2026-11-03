"""Build a separate sense-indexed English/Chinese WordNet; never download at runtime."""
import gzip
import json
import sqlite3
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def build_wordnet():
    data = json.loads(gzip.decompress((ROOT / 'sources/wordnet-cow.json.gz').read_bytes()))
    dest = ROOT / 'bundle/wordnet.sqlite'
    temp = dest.with_suffix('.building')
    temp.unlink(missing_ok=True)
    db = sqlite3.connect(temp)
    db.executescript('''PRAGMA user_version=1;
      CREATE TABLE senses(word TEXT, pos TEXT, rank INTEGER, synset TEXT, meaning TEXT, gloss TEXT);
      CREATE INDEX sense_word ON senses(word);
      CREATE TABLE forms(form TEXT, lemma TEXT, pos TEXT, UNIQUE(form,lemma,pos));
      CREATE INDEX wn_form ON forms(form);''')
    for word, pos, rank, synset, meanings, gloss in data['senses']:
        # '+' is COW's segmentation marker, not punctuation in the displayed equivalent.
        meanings = list(dict.fromkeys(x.replace('+', '') for x in meanings))
        db.execute('INSERT INTO senses VALUES(?,?,?,?,?,?)',
                   (word.replace('_', ' '), pos, rank, synset, '；'.join(meanings), gloss))
    db.executemany('INSERT OR IGNORE INTO forms VALUES(?,?,?)', data['exceptions'])
    with gzip.open(ROOT / 'sources/lemminflect-forms.jsonl.gz', 'rt') as src:
        for line in src:
            if not line.strip():
                continue
            row = json.loads(line)
            pos = {'NOUN': 'n', 'VERB': 'v', 'AUX': 'v', 'ADJ': 'a', 'ADV': 'r'}.get(row['pos'])
            if pos:
                for lemma in row['lemmas']:
                    if lemma.lower() != row['form'].lower():
                        db.execute('INSERT OR IGNORE INTO forms VALUES(?,?,?)',
                                   (row['form'].lower(), lemma.lower(), pos))
    db.commit()
    assert db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'
    db.execute('VACUUM')
    count = db.execute('SELECT count(*) FROM senses').fetchone()[0]
    words = db.execute('SELECT count(DISTINCT word) FROM senses').fetchone()[0]
    db.close()
    temp.replace(dest)
    return {'senses': count, 'headwords': words,
            'source': 'Princeton WordNet 3.0 + Chinese Open WordNet',
            'license': 'WordNet / Chinese Open WordNet permissive notices',
            'sourceRevision': data['cow_revision'],
            'url': 'https://github.com/omwn/omw-data/tree/main/wns/cow'}
