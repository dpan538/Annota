"""Build-time only: export pinned Marian source.spm + vocab.json to Kotlin trie data.
Usage: python3 export_tokenizer.py model-directory output.json
Requires sentencepiece and protobuf only on the build host, never in the APK.
"""
import json, pathlib, sys
from sentencepiece import sentencepiece_model_pb2 as pb
source=pathlib.Path(sys.argv[1])
model=pb.ModelProto();model.ParseFromString((source/'source.spm').read_bytes())
vocab=json.loads((source/'vocab.json').read_text())
assert model.trainer_spec.model_type == pb.TrainerSpec.UNIGRAM
entries=[[p.piece,p.score,vocab.get(p.piece,1)] for p in model.pieces if p.type == pb.ModelProto.SentencePiece.NORMAL]
pathlib.Path(sys.argv[2]).write_text(json.dumps({'unknownId':1,'minimumScore':min(p[1] for p in entries),'pieces':entries},ensure_ascii=False,separators=(',',':')))
