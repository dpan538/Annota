# Offline semantic assets

This directory builds only the fixed gloss-vector index used by the local
candidate reranker. It is not a generative translation model.

The APK includes the unchanged official `sentence-transformers/all-MiniLM-L6-v2`
ARM64 signed-int8 ONNX model at revision
`1110a243fdf4706b3f48f1d95db1a4f5529b4d41`. The model is Apache-2.0 per its pinned
[official model card](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/blob/1110a243fdf4706b3f48f1d95db1a4f5529b4d41/README.md).
The complete licenses and provenance are shipped in `assets/semantic`.

## Offline build

Use the local environment with numpy, tokenizers and onnxruntime installed:

```sh
android/.tooling/research-111/venv/bin/python design/dictionary/semantic/build.py
android/.tooling/research-111/venv/bin/python design/dictionary/semantic/finalize.py
android/.tooling/research-111/venv/bin/python design/dictionary/semantic/verify.py
```

Neither script downloads anything. Model/tokenizer downloads during development
are frozen to that exact revision. `model.onnx` SHA-256 matches the official LFS
OID (`4278337fd0ff3c68bfb6291042cad8ab363e1d9fbc43dcb499fe91c871902474`).

The builder reads the `senses.gloss` values of `bundle/wordnet.sqlite` and
`bundle/wiktionary.sqlite`, retaining every distinct nonempty raw string.
It does not index the much larger English-only fallback pool. Upstream input
file hashes are recorded so a dictionary update cannot silently reuse old data.

## Format and encoder contract

`glosses.sqlite` contains:

```sql
CREATE TABLE vectors(
  key TEXT PRIMARY KEY NOT NULL,
  vector BLOB NOT NULL CHECK(length(vector)=384),
  scale REAL NOT NULL CHECK(scale>0)
) WITHOUT ROWID;
CREATE TABLE metadata(key TEXT PRIMARY KEY NOT NULL,value TEXT NOT NULL) WITHOUT ROWID;
```

`key` is lowercase hexadecimal SHA-256 of **the exact original UTF-8 gloss**,
including punctuation and examples; no trimming, case folding or POS is added.
Identical glosses across POS can share a vector, while POS constraints remain the
runtime ranker's job. The int8 vector uses two's complement signed bytes. Decode
with `signedByte * scale`, then L2-normalize before cosine comparison.

Input: BERT uncased BasicTokenizer + WordPiece from frozen tokenizer.json/vocab,
maximum 128 tokens **including CLS and SEP**. Three int64 inputs `input_ids`,
`attention_mask`, `token_type_ids`; `last_hidden_state` float32 output `[B,T,384]`.
Attention-mask mean pooling includes CLS/SEP but excludes padding; then L2.
Glosses longer than128 are explicitly truncated offline to127tokens+SEP and
counted. The context encoder rejects overlong input; callers must intentionally
bound context. The full gloss is always the hash key and display text.

Build and parity fixtures use batch1 with no padding, matching Android.
Dynamic int8 model quantization varies with batch content: an initial batch32
index had sample cosine as low as0.984 against batch1 and was discarded.
Do not batch glosses together merely to accelerate rebuilding. `build-report.json` records timings,
quantization error and a few sanity checks, **not WSD accuracy**. Android tests
copy tokenizer and embedding fixtures from here into instrumentation assets.

English similarity cannot repair erroneous Chinese source labels, identify all
negations, or guarantee semantic correctness. Preserve conservative confidence,
POS and source-quality checks; fallback to lexical ordering when evidence is weak.
