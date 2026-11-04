#!/usr/bin/env python3
"""Developer-only, opt-in fetch of immutable model files; never called by Android.

Every file must match the committed manifest hash. No mutable main/latest URLs.
"""
import argparse, hashlib, json, urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]
ASSETS=ROOT/'android/app/src/main/assets/semantic'
REVISION='1110a243fdf4706b3f48f1d95db1a4f5529b4d41'
FILES={'model.onnx':'onnx/model_qint8_arm64.onnx','vocab.txt':'vocab.txt','tokenizer.json':'tokenizer.json','config.json':'config.json','tokenizer_config.json':'tokenizer_config.json','special_tokens_map.json':'special_tokens_map.json','MODEL_CARD.md':'README.md','pooling_config.json':'1_Pooling/config.json','sentence_bert_config.json':'sentence_bert_config.json'}
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--download-frozen-model',action='store_true',required=True);parser.parse_args()
    manifest=json.loads((ASSETS/'manifest.json').read_text());assert manifest['revision']==REVISION
    for target,source in FILES.items():
        path=ASSETS/target;expected=manifest['files'][target]['sha256']
        if path.exists() and hashlib.sha256(path.read_bytes()).hexdigest()==expected:continue
        url=f'https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/{REVISION}/{source}'
        with urllib.request.urlopen(url,timeout=120) as response:data=response.read()
        assert hashlib.sha256(data).hexdigest()==expected, f'Hash mismatch: {target}'
        path.write_bytes(data);print(target,len(data))
if __name__=='__main__':main()
