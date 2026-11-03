"""Build-time morphology research extract from pinned upstream lookup + overrides."""
import gzip,json,pathlib,hashlib,sys
# Usage: import_lemminflect.py directory-with-prefixed-upstream-files output.jsonl.gz
base=pathlib.Path(sys.argv[1])
pos={'adj':'ADJ','adv':'ADV','noun':'NOUN','verb':'VERB','aux':'AUX','modal':'AUX','pron':'PRON','det':'DET','prep':'ADP','conj':'CCONJ','compl':'SCONJ','interj':'INTJ'}
rows={}
with gzip.open(base/'lemminflect-lemma_lu.csv.gz','rt') as f:
 for line in f:
  word,category,lemmas=line.strip().split(',');rows[word,pos[category]]=lemmas.split('/')
for line in (base/'lemminflect-lemma_overrides.csv').read_text().splitlines():
 line=line.strip()
 if not line or line.startswith('#'):continue
 word,category,lemmas=line.split(',');rows[word,category]=lemmas.split('/')
out=pathlib.Path(sys.argv[2])
with out.open('wb') as raw,gzip.GzipFile(fileobj=raw,mode='wb',mtime=0,filename='') as f:
 for (word,category),lemmas in sorted(rows.items()):
  record={'form':word,'pos':category,'lemmas':lemmas}
  f.write((json.dumps(record,ensure_ascii=False,separators=(',',':'))+'\n').encode())
print(len(rows),len({word for word,category in rows}),hashlib.file_digest(out.open('rb'),'sha256').hexdigest())
