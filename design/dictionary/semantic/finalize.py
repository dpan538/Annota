#!/usr/bin/env python3
"""Finalize local bundle hashes and licenses after build.py (no network)."""
import hashlib,json,shutil
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]
ASSETS=ROOT/'android/app/src/main/assets/semantic'
REVISION='1110a243fdf4706b3f48f1d95db1a4f5529b4d41'
MODEL_SHA='4278337fd0ff3c68bfb6291042cad8ab363e1d9fbc43dcb499fe91c871902474'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
assert sha(ASSETS/'model.onnx')==MODEL_SHA, 'Official LFS SHA256 mismatch'
for name in ('WordNet-3.0.txt','Chinese-Open-WordNet.txt','Wiktionary-NOTICE.md','CC-BY-SA-4.0.txt'):
    shutil.copyfile(ROOT/'android/app/src/main/assets/dictionary/licenses'/name,ASSETS/name)
report=json.loads((Path(__file__).parent/'build-report.json').read_text())
manifest={
 'schema_version':1,'model_id':'sentence-transformers/all-MiniLM-L6-v2', 'revision':REVISION,
 'model_source_path':'onnx/model_qint8_arm64.onnx','model_license':'Apache-2.0',
 'official_lfs_sha256':MODEL_SHA,'embedding_dimension':384,'max_tokens':128,
 'normalization':'bert-uncased-wordpiece-v1',
 'pooling':'attention-mask mean including CLS/SEP followed by L2 normalization',
 'input_names':['input_ids','attention_mask','token_type_ids'], 'input_dtype':'int64',
 'output_name':'last_hidden_state','output_dtype':'float32',
 'vector_index':{'file':'glosses.sqlite','table':'vectors','key_column':'key','key':'sha256(raw exact gloss UTF-8), lowercase hex','encoding':'signed-int8-symmetric-per-vector','decode':'signed byte * scale; L2 normalize before cosine','count':report['metadata']['gloss_count'],'truncated_glosses':report['metadata']['truncated_glosses'],'truncation':'Offline glossary only: first127tokens+SEP; context encoder never silently truncates','sources':report['metadata']['sources']},
 'network_at_runtime':False,
 'files':{p.name:{'sha256':sha(p),'bytes':p.stat().st_size} for p in sorted(ASSETS.iterdir()) if p.is_file() and p.name!='manifest.json' and '.tmp.' not in p.name}
}
(ASSETS/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'files':len(manifest['files']),'total_asset_bytes':sum(p.stat().st_size for p in ASSETS.iterdir() if p.is_file()),'index_bytes':(ASSETS/'glosses.sqlite').stat().st_size},indent=2))
