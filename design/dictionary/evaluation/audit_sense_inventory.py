"""Read-only candidate/source audit, not an Android lookup accuracy benchmark.
Run from any cwd. Writes only a separate research report beside this script.
"""
import hashlib
import json
import sqlite3
from pathlib import Path

HERE = Path(__file__).resolve().parent
BUNDLE = HERE.parent / 'bundle'
cases = json.loads((HERE / 'sense-ranking-cases.json').read_text())['cases']
queries = sorted({case['query'] for case in cases} | {'measured', 'related', 'scaling'})
report = {'kind': 'candidate inventory only; no runtime predictions or correctness rate',
          'databases': {}, 'words': {q: {} for q in queries}}
for name in ['en-zh', 'wiktionary', 'wordnet']:
    path = BUNDLE / (name + '.sqlite')
    db = sqlite3.connect(path.as_uri() + '?mode=ro', uri=True)
    db.row_factory = sqlite3.Row
    stats = {'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'bytes': path.stat().st_size}
    if name == 'en-zh':
        stats['entries'] = db.execute('select count(*) from entries').fetchone()[0]
        stats['with_english_definition'] = db.execute("select count(*) from entries where trim(coalesce(definition,''))<>''").fetchone()[0]
        for query in queries:
            report['words'][query][name] = [dict(row) for row in db.execute(
                'select word,translation,definition,source from entries where key=? order by id', (query,))]
    else:
        stats['senses'] = db.execute('select count(*) from senses').fetchone()[0]
        stats['words'] = db.execute('select count(distinct word) from senses').fetchone()[0]
        stats['missing_gloss'] = db.execute("select count(*) from senses where trim(coalesce(gloss,''))=''").fetchone()[0]
        stats['polysemous_words'] = db.execute('select count(*) from (select word from senses group by word having count(*)>1)').fetchone()[0]
        stats['duplicate_word_pos_rank_groups'] = db.execute('select count(*) from (select word,pos,rank from senses group by word,pos,rank having count(*)>1)').fetchone()[0]
        stats['same_chinese_distinct_gloss_groups'] = db.execute('select count(*) from (select word,pos,meaning from senses group by word,pos,meaning having count(distinct gloss)>1)').fetchone()[0]
        for query in queries:
            report['words'][query][name] = [dict(row) for row in db.execute(
                'select word,pos,rank,synset,meaning,gloss from senses where word=? order by rank,pos,synset', (query,))]
    report['databases'][name] = stats
    db.close()
report['case_count'] = len(cases)
report['abstention_case_count'] = sum(case['expectAbstain'] for case in cases)
(HERE / 'sense-inventory-audit.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
print(json.dumps(report['databases'], ensure_ascii=False, indent=2))
