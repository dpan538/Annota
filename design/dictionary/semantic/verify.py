#!/usr/bin/env python3
"""Check the offline asset contract, all input-key coverage, and representative vectors."""
import hashlib,json,sqlite3
from pathlib import Path
import numpy as np
from tokenizers import Tokenizer
from build import ASSETS,ROOT,DIM,MAX_TOKENS,sha,session,encode_ids,sources

def main():
    manifest=json.loads((ASSETS/'manifest.json').read_text())
    assert manifest['normalization']=='bert-uncased-wordpiece-v1'
    assert manifest['embedding_dimension']==DIM and manifest['max_tokens']==MAX_TOKENS
    assert manifest['official_lfs_sha256']==sha(ASSETS/'model.onnx')
    for name,entry in manifest['files'].items():
        assert sha(ASSETS/name)==entry['sha256'],name
        assert (ASSETS/name).stat().st_size==entry['bytes'],name
    db=sqlite3.connect(f'file:{ASSETS/"glosses.sqlite"}?mode=ro',uri=True)
    assert db.execute('pragma integrity_check').fetchone()[0]=='ok'
    glosses, provenance=sources()
    assert provenance==manifest['vector_index']['sources']
    expected={hashlib.sha256(g.encode('utf-8')).hexdigest() for g in glosses}
    actual={r[0] for r in db.execute('select key from vectors')}
    assert expected==actual, (len(expected-actual),len(actual-expected))
    assert db.execute('select count(*) from vectors where length(vector)<>384 or scale<=0').fetchone()[0]==0
    tok=Tokenizer.from_file(str(ASSETS/'tokenizer.json'));tok.no_padding();tok.no_truncation();sess=session()
    for case in json.loads((Path(__file__).parent/'tokenizer-fixtures.json').read_text())['cases']:
        assert tok.encode(case['text']).ids==case['ids']
    for case in json.loads((Path(__file__).parent/'embedding-fixtures.json').read_text())['cases']:
        vec=encode_ids(sess,[tok.encode(case['text']).ids])[0]
        assert float(vec@np.asarray(case['embedding']))>0.99999
    # Deterministic, spread-out rawgloss sample, reencoded with Android's batch1 behavior.
    comparisons=[]
    for i in np.linspace(0,len(glosses)-1,24,dtype=int):
        gloss=glosses[i];ids=tok.encode(gloss).ids
        if len(ids)>MAX_TOKENS:ids=ids[:MAX_TOKENS-1]+[102]
        expected_vec=encode_ids(sess,[ids])[0]
        key=hashlib.sha256(gloss.encode('utf-8')).hexdigest();packed,scale=db.execute('select vector,scale from vectors where key=?',(key,)).fetchone()
        vec=np.frombuffer(packed,dtype=np.int8).astype(np.float32)*scale;assert np.all(np.isfinite(vec));vec/=np.linalg.norm(vec)
        comparisons.append({'gloss':gloss,'cosine_batch1_vs_index':float(vec@expected_vec)})
    worst=min(x['cosine_batch1_vs_index'] for x in comparisons)
    assert worst>0.999,worst
    report={'all_asset_hashes_verified':True,'official_model_lfs_hash_verified':True,'all_glosses_indexed':len(expected),'missing_keys':0,'sample_batch1_index_min_cosine':worst,'sampled_comparisons':comparisons,'scope':'Asset integrity and encoder parity sanity checks; not sense-disambiguation accuracy.'}
    (Path(__file__).parent/'verification-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print(json.dumps({k:v for k,v in report.items() if k!='sampled_comparisons'},indent=2))

if __name__=='__main__':main()
