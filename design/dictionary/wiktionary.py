"""Offline, sense-preserving Mandarin extract from English Wiktionary / Kaikki.

Data remains CC BY-SA 4.0 in its own database. Never attach a translation to
all the English senses: use the translation's own sense heading only.
"""
import gzip, json, sqlite3, re, unicodedata
from pathlib import Path
ROOT = Path(__file__).resolve().parent

def build_wiktionary():
    path = ROOT / 'bundle/wiktionary.sqlite'
    temp = path.with_suffix('.building')
    temp.unlink(missing_ok=True)
    db = sqlite3.connect(temp)
    db.executescript('''PRAGMA user_version=2;
        CREATE TABLE senses(word TEXT, pos TEXT, rank INTEGER, synset TEXT, meaning TEXT, gloss TEXT);
        CREATE INDEX sense_word ON senses(word);
        CREATE TABLE aliases(key TEXT, word TEXT, UNIQUE(key,word));
        CREATE INDEX wiki_alias ON aliases(key);
        CREATE TABLE forms(form TEXT, lemma TEXT, pos TEXT, UNIQUE(form,lemma,pos));
        CREATE INDEX wn_form ON forms(form);
        CREATE TABLE source_forms(form TEXT, lemma TEXT, UNIQUE(form,lemma));
        CREATE INDEX source_form_key ON source_forms(form);
        CREATE TABLE english_senses(word TEXT, pos TEXT, rank INTEGER, gloss TEXT);
        CREATE INDEX english_sense_word ON english_senses(word);
        CREATE TABLE english_aliases(key TEXT, word TEXT, UNIQUE(key,word));
        CREATE INDEX english_alias_key ON english_aliases(key);''')
    normalize = lambda s: ' '.join(unicodedata.normalize('NFKC', s).lower().split())
    posmap = {'noun':'n','verb':'v','adj':'a','adv':'r','name':'n','phrase':'n','prep':'prep','conj':'conj','pron':'pron','intj':'intj','num':'num'}
    with gzip.open(ROOT / 'sources/kaikki-en-mandarin.jsonl.gz', 'rt') as f:
        for line in f:
            row = json.loads(line)
            word = normalize(row['word'])
            if len(word)>160 or not re.fullmatch(r"[a-z0-9' .-]+",word): continue
            pos = posmap.get(row.get('pos'))
            if not pos: continue
            groups = {}
            for t in row['translations']:
                if set(t.get('tags',[])) & {'obsolete','archaic','dated','rare','uncommon'}: continue
                meaning = t['word'].strip()
                # Wiktionary often gives traditional / simplified explicitly. Preserve that
                # editorial mapping rather than guessing conversion character by character.
                variants = re.split(r'\s*/\s*', meaning)
                if len(variants)==2 and all(re.fullmatch(r'[\u3400-\u9fff]+',v) for v in variants):
                    meaning = variants[-1]
                if not meaning or len(meaning)>40: continue
                gloss = t.get('sense','').strip()
                if not gloss: continue
                groups.setdefault(gloss,[])
                if meaning not in groups[gloss]: groups[gloss].append(meaning)
            for rank,(gloss,meanings) in enumerate(groups.items()):
                db.execute('INSERT INTO senses VALUES(?,?,?,?,?,?)',
                    (word,pos,rank,'wiktionary:'+row['word'], '；'.join(meanings),gloss))
            if groups:
                boundary = re.sub(r'[ -]+', ' ', word)
                db.execute('INSERT OR IGNORE INTO aliases VALUES(?,?)', (boundary,word))
            for form in row.get('forms',[]):
                if set(form.get('tags',[])) & {'obsolete','archaic','romanization','table-tags'}: continue
                key = normalize(form.get('form',''))
                if key and key!=word and len(key)<=160:
                    db.execute('INSERT OR IGNORE INTO forms VALUES(?,?,?)',(key,word,pos))
    with gzip.open(ROOT / 'sources/lemminflect-forms.jsonl.gz','rt') as f:
        for line in f:
            if not line.strip():continue
            row=json.loads(line)
            pos={'NOUN':'n','VERB':'v','AUX':'v','ADJ':'a','ADV':'r'}.get(row['pos'])
            if pos:
                for lemma in row['lemmas']:
                    if normalize(lemma)!=normalize(row['form']):
                        db.execute('INSERT OR IGNORE INTO forms VALUES(?,?,?)',(normalize(row['form']),normalize(lemma),pos))
    # CC BY-SA morphology stays in this independently attributed database. A form is
    # useful only when at least one bundled dictionary can define its attested lemma.
    targets = {row[0] for row in db.execute('SELECT DISTINCT word FROM senses')}
    for filename, table in [('en-zh.sqlite','entries'), ('wordnet.sqlite','senses')]:
        with sqlite3.connect(ROOT / 'bundle' / filename) as other:
            targets.update(normalize(row[0]) for row in other.execute(f'SELECT DISTINCT word FROM {table}'))
    chinese_targets = targets.copy()
    english_ranks = {}
    with gzip.open(ROOT / 'sources/kaikki-en-definitions.jsonl.gz', 'rt') as f:
        for line in f:
            row = json.loads(line)
            word, pos = normalize(row['word']), posmap.get(row['pos'])
            if not pos or not row.get('gloss') or word in chinese_targets: continue
            rank = english_ranks.get(word, 0)
            if rank >= 3: continue
            english_ranks[word] = rank + 1
            db.execute('INSERT INTO english_senses VALUES(?,?,?,?)', (word,pos,rank,row['gloss']))
            db.execute('INSERT OR IGNORE INTO english_aliases VALUES(?,?)', (re.sub(r'[ -]+',' ',word),word))
            targets.add(word)
    chinese_pairs = 0
    with gzip.open(ROOT / 'sources/kaikki-forms.jsonl.gz', 'rt') as f:
        for line in f:
            row = json.loads(line)
            form, lemma = normalize(row['form']), normalize(row['lemma'])
            if form != lemma and lemma in targets:
                inserted = db.execute('INSERT OR IGNORE INTO source_forms VALUES(?,?)', (form,lemma)).rowcount
                if inserted and lemma in chinese_targets:
                    chinese_pairs += 1
    db.commit()
    assert db.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
    counts={'senses':db.execute('SELECT count(*) FROM senses').fetchone()[0],
            'headwords':db.execute('SELECT count(DISTINCT word) FROM senses').fetchone()[0],
            'phrases':db.execute("SELECT count(DISTINCT word) FROM senses WHERE instr(word,' ')>0").fetchone()[0],
            'sourceFormPairs':db.execute('SELECT count(*) FROM source_forms').fetchone()[0],
            'sourceFormPairsWithChineseTarget':chinese_pairs,
            'englishHeadwords':len(english_ranks),
            'englishSenses':db.execute('SELECT count(*) FROM english_senses').fetchone()[0],
            'license':'CC-BY-SA-4.0','source':'English Wiktionary contributors / Kaikki',
            'url':'https://kaikki.org/dictionary/English/'}
    db.execute('VACUUM');db.close();temp.replace(path)
    return counts
