#!/usr/bin/env python3
"""Build bundled MiniLM gloss embeddings. All inputs are local; never downloads.

Requires numpy, onnxruntime, tokenizers. Model and tokenizer are frozen in assets.
The only truncation is explicit offline glossary truncation (metadata recorded).
"""
import argparse, hashlib, json, os, platform, sqlite3, time
from pathlib import Path
import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

ROOT = Path(__file__).resolve().parents[3]
ASSETS = ROOT / 'android/app/src/main/assets/semantic'
REVISION = '1110a243fdf4706b3f48f1d95db1a4f5529b4d41'
MAX_TOKENS = 128
DIM = 384

def sha(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for block in iter(lambda: f.read(1024*1024), b''): h.update(block)
    return h.hexdigest()

def session(threads=4):
    ort.disable_telemetry_events()
    options = ort.SessionOptions()
    options.intra_op_num_threads = threads
    options.inter_op_num_threads = 1
    return ort.InferenceSession(str(ASSETS/'model.onnx'), sess_options=options, providers=['CPUExecutionProvider'])

def encode_ids(sess, rows):
    width = max(map(len, rows))
    ids = np.zeros((len(rows), width), dtype=np.int64)
    mask = np.zeros_like(ids)
    for i, row in enumerate(rows):
        ids[i, :len(row)] = row
        mask[i, :len(row)] = 1
    hidden = sess.run(['last_hidden_state'], {'input_ids':ids, 'attention_mask':mask, 'token_type_ids':np.zeros_like(ids)})[0]
    vectors = (hidden*mask[...,None]).sum(axis=1) / mask.sum(axis=1)[:,None]
    vectors = vectors.astype(np.float32)
    vectors /= np.maximum(np.linalg.norm(vectors, axis=1, keepdims=True), 1e-12)
    return vectors

def quantize(vec):
    scale = float(np.max(np.abs(vec)) / 127.0)
    packed = np.clip(np.rint(vec / scale), -127, 127).astype(np.int8)
    return packed.tobytes(), scale

def sources():
    glosses = set(); provenance = []
    for name in ('wordnet', 'wiktionary'):
        path = ROOT / f'design/dictionary/bundle/{name}.sqlite'
        with sqlite3.connect(f'file:{path}?mode=ro', uri=True) as db:
            values = [row[0] for row in db.execute("SELECT gloss FROM senses WHERE gloss IS NOT NULL AND length(trim(gloss))>0")]
        glosses.update(values)
        provenance.append({'path':str(path.relative_to(ROOT)), 'sha256':sha(path), 'rows':len(values), 'distinct_glosses':len(set(values))})
    return sorted(glosses), provenance

def fixtures(tok, sess):
    texts = ['The bank approved a loan.', 'Café naïve résumé déjà vu.', '中文词典，IT-related resources.',
             '[MASK] [mask] [CLS] [UNK] [SEP] [PAD]', "Can't we're John's IT-related resource; 3.14%.",
             'tabs\tlines\ncarriage\rnull\u0000zero\u200bwidth\u00a0space\u202fend', 'a'*101,
             'Emoji 🧪 and supplementary CJK 𠀀.', 'Αλφα русский Français', 'Hello—world… “quotes” /foo_bar', 'ΟΣ Σ İ ᾀ', 'he\ue000llo world\u2028again', 'a𫠠b a𫤠b']
    cases=[]
    for text in texts:
        encoded=tok.encode(text)
        cases.append({'text':text,'ids':encoded.ids,'tokens':encoded.tokens})
    out=Path(__file__).parent
    (out/'tokenizer-fixtures.json').write_text(json.dumps({'revision':REVISION,'cases':cases},ensure_ascii=False,indent=2)+'\n')
    pair_texts=['The bank approved a loan.', 'a financial institution that accepts deposits and lends money',
                'sloping land beside a river', 'The fisherman sat on the river bank.',
                'a financial charge imposed for a service', 'He paid the fee for the service.',
                'The cells were examined under a microscope.', 'the basic structural unit of living organisms',
                'a small room in a prison']
    vectors=[encode_ids(sess,[tok.encode(text).ids])[0] for text in pair_texts]
    (out/'embedding-fixtures.json').write_text(json.dumps({'revision':REVISION,'batch_size':1,'max_tokens':MAX_TOKENS,'pooling':'attention-mask mean including CLS and SEP then L2','cases':[{'text':t,'embedding':v.tolist()} for t,v in zip(pair_texts,vectors)]},ensure_ascii=False,indent=2)+'\n')
    checks=[]
    for a,good,bad in [(0,1,2),(3,2,1),(5,4,2),(6,7,8)]:
        yes=float(vectors[a]@vectors[good]); no=float(vectors[a]@vectors[bad])
        checks.append({'query':pair_texts[a], 'related':pair_texts[good], 'unrelated':pair_texts[bad], 'related_cosine':yes,'unrelated_cosine':no,'passed':yes>no})
    assert all(x['passed'] for x in checks), checks
    return checks

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--limit',type=int);parser.add_argument('--fixtures-only',action='store_true');parser.add_argument('--batch-size',type=int,default=1);parser.add_argument('--threads',type=int,default=4)
    args=parser.parse_args()
    if not args.limit and args.batch_size != 1:
        parser.error('Production glosses must use batch1 to match Android dynamic quantization')
    start=time.monotonic(); tok=Tokenizer.from_file(str(ASSETS/'tokenizer.json')); tok.no_truncation(); tok.no_padding();sess=session(args.threads)
    checks=fixtures(tok,sess)
    if args.fixtures_only:
        print(json.dumps(checks,indent=2));return
    glosses, provenance=sources()
    if args.limit: glosses=glosses[:args.limit]
    # Pre-tokenize without truncation so the reduction is measurable; include CLS+SEP.
    rows=[];truncated=0;longest=0
    for gloss in glosses:
        ids=tok.encode(gloss).ids; longest=max(longest,len(ids))
        if len(ids)>MAX_TOKENS:
            truncated+=1;ids=ids[:MAX_TOKENS-1]+[102]
        rows.append((gloss,ids))
    # Sort by encoded length to avoid excessive padding, tie-break using gloss.
    rows.sort(key=lambda row:(len(row[1]),row[0]))
    target=(ROOT/'android/.tooling/semantic/glosses-benchmark.sqlite') if args.limit else ASSETS/'glosses.sqlite'
    target.parent.mkdir(parents=True,exist_ok=True)
    temporary=ROOT/'android/.tooling/semantic'/('glosses-benchmark.tmp.sqlite' if args.limit else 'glosses.tmp.sqlite')
    temporary.parent.mkdir(parents=True,exist_ok=True);temporary.unlink(missing_ok=True)
    db=sqlite3.connect(temporary)
    db.execute('PRAGMA page_size=4096');db.execute('PRAGMA journal_mode=OFF');db.execute('PRAGMA synchronous=OFF')
    db.execute('CREATE TABLE vectors(key TEXT PRIMARY KEY NOT NULL, vector BLOB NOT NULL CHECK(length(vector)=384), scale REAL NOT NULL CHECK(scale>0)) WITHOUT ROWID')
    db.execute('CREATE TABLE metadata(key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL) WITHOUT ROWID')
    quantization_worst_cosine=1.0
    for offset in range(0,len(rows),args.batch_size):
        batch=rows[offset:offset+args.batch_size];vectors=encode_ids(sess,[r[1] for r in batch]);records=[]
        for (gloss,_),vec in zip(batch,vectors):
            packed,scale=quantize(vec);decoded=np.frombuffer(packed,dtype=np.int8).astype(np.float32)*scale;decoded/=np.linalg.norm(decoded)
            quantization_worst_cosine=min(quantization_worst_cosine,float(vec@decoded))
            records.append((hashlib.sha256(gloss.encode('utf-8')).hexdigest(),packed,scale))
        db.executemany('INSERT INTO vectors VALUES(?,?,?)',records)
        if offset%2048==0: print(f'{offset}/{len(rows)} elapsed={time.monotonic()-start:.1f}s',flush=True)
    metadata={'schema_version':1,'model_id':'sentence-transformers/all-MiniLM-L6-v2','revision':REVISION,'model_sha256':sha(ASSETS/'model.onnx'),'embedding_dimension':DIM,'max_tokens':MAX_TOKENS,'key':'sha256(raw exact gloss UTF-8), lowercase hex','encoding':'signed-int8-symmetric-per-vector','normalization':'masked-token mean including CLS/SEP, then L2','truncation':'offline only: first 127 token IDs then SEP; raw full gloss still supplies key','truncated_glosses':truncated,'longest_untruncated_tokens':longest,'gloss_count':len(rows),'sources':provenance}
    db.executemany('INSERT INTO metadata VALUES(?,?)',[(k,json.dumps(v,ensure_ascii=False)) for k,v in metadata.items()]);db.commit();assert db.execute('pragma integrity_check').fetchone()[0]=='ok';db.close();os.replace(temporary,target)
    report={'metadata':metadata,'build_seconds':round(time.monotonic()-start,3),'sqlite_bytes':target.stat().st_size,'sqlite_sha256':sha(target),'quantization_worst_cosine':quantization_worst_cosine,'known_pair_checks':checks,'environment':{'python':platform.python_version(),'platform':platform.platform(),'onnxruntime':ort.__version__,'numpy':np.__version__,'threads':args.threads,'batch_size':args.batch_size}}
    reportpath=Path(__file__).parent/('benchmark-report.json' if args.limit else 'build-report.json');reportpath.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n'); print(json.dumps(report,ensure_ascii=False,indent=2),flush=True)

if __name__=='__main__':main()
