"""Extract Mandarin translations from an English Wiktionary/Kaikki JSONL gzip.
Usage: python3 design/dictionary/import_kaikki.py input.jsonl.gz output-directory
The source URL is mutable; verify source SHA-256 against frozen provenance first.
"""
import gzip,json,re,hashlib,collections,pathlib,sys
source=pathlib.Path(sys.argv[1])
base=pathlib.Path(sys.argv[2]);base.mkdir(parents=True,exist_ok=True)
keepkeys=lambda d,ks:{k:d[k] for k in ks if k in d}
counts=collections.Counter(); langs=collections.Counter(); heads=set(); allheads=set(); samples={}
wanted={'scaling','measured','related','virtual','instance','IT','it','bank','charge','resource','allocation','distributed','virtualization','scalable','take off','look up','underlying','resiliency','provisioning'}
outpath=base/'kaikki-en-mandarin.jsonl.gz'
# Frozen build input is canonical. Re-extraction is semantically equivalent; gzip headers differ.
with gzip.open(source,'rt',encoding='utf-8') as f,gzip.open(outpath,'wt',encoding='utf-8') as out:
 for line in f:
  d=json.loads(line); counts['records']+=1; word=d.get('word','');allheads.add(word)
  allts=list(d.get('translations',[]))
  for s in d.get('senses',[]):allts.extend(s.get('translations',[]))
  ts=[]
  for t in allts:
   if t.get('code') not in ('zh','cmn'):continue
   langs[t.get('lang','')+'|'+','.join(t.get('tags',[]))]+=1
   if not re.search('[\u3400-\u9fff]',t.get('word','')):continue
   if 'Mandarin' not in t.get('lang','') and t.get('code')!='cmn':continue
   ts.append(keepkeys(t,['word','sense','tags','english','translation','roman','_dis1']))
  if not ts:continue
  heads.add(word);counts['mandarin_records']+=1;counts['translations']+=len(ts)
  if ' ' in word:counts['multiword_records']+=1
  record={'word':word,'pos':d.get('pos'), 'translations':ts,'senses':[keepkeys(s,['glosses','tags','topics','form_of','alt_of','id']) for s in d.get('senses',[])], 'forms':[keepkeys(x,['form','tags']) for x in d.get('forms',[])]}
  out.write(json.dumps(record,ensure_ascii=False,separators=(',',':'))+'\n')
  if word in wanted:samples.setdefault(word,[]).append(record)
counts['heads']=len(allheads);counts['mandarin_heads']=len(heads)
meta={'source':'https://kaikki.org/dictionary/English/kaikki.org-dictionary-English.jsonl.gz','source_bytes':source.stat().st_size,'source_sha256':hashlib.file_digest(source.open('rb'),'sha256').hexdigest(),'counts':dict(counts),'extract_bytes':outpath.stat().st_size,'extract_sha256':hashlib.file_digest(outpath.open('rb'),'sha256').hexdigest(),'filter':'Only Han-containing code zh/cmn, lang includes Mandarin or code cmn. Minimal semantic fields. No auto sense merging.','translation_languages':dict(langs)}
(base/'kaikki-audit.json').write_text(json.dumps(meta,ensure_ascii=False,indent=2))
(base/'kaikki-samples.json').write_text(json.dumps(samples,ensure_ascii=False,indent=2))
print(json.dumps({k:v for k,v in meta.items() if k!='translation_languages'},ensure_ascii=False,indent=2))
for w,rs in samples.items():
 print(w,json.dumps([{'pos':r['pos'],'translations':r['translations'][:5]} for r in rs],ensure_ascii=False))
