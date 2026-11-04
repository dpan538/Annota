# Offline semantic reranking assets

Model: sentence-transformers/all-MiniLM-L6-v2, © Sentence Transformers contributors.
License declared by its official model card: Apache License 2.0.
Immutable upstream revision: 1110a243fdf4706b3f48f1d95db1a4f5529b4d41.
Source: https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/tree/1110a243fdf4706b3f48f1d95db1a4f5529b4d41

model.onnx is the unchanged upstream onnx/model_qint8_arm64.onnx. Its SHA-256
is checked against the official Hugging Face LFS object ID:
4278337fd0ff3c68bfb6291042cad8ab363e1d9fbc43dcb499fe91c871902474.
Tokenizer, vocabulary and model configuration files are unchanged upstream files.
MODEL_CARD.md preserves the upstream documentation. LICENSE-APACHE-2.0.txt is
the complete Apache license obtained from https://www.apache.org/licenses/LICENSE-2.0.txt.
The upstream model repository does not contain a separate NOTICE or LICENSE file.

Annota's glosses.sqlite is a new, separately licensed derived data index. It
contains quantized embeddings of the English glosses attached to the bundled
WordNet/COW and Wiktionary Chinese senses; it does not contain learned new
Chinese translations. WordNet data remains subject to WordNet-3.0.txt and
Chinese-Open-WordNet.txt. Wiktionary-derived embeddings and this combined index
are distributed under CC BY-SA 4.0; see CC-BY-SA-4.0.txt and Wiktionary-NOTICE.md.
The application's MIT license does not replace those data/model licenses.

Modifications: deduplicate exact glosses, mean-pool masked token embeddings,
normalize, quantize each vector to signed int8, and index by SHA-256 of the full
raw UTF-8 gloss. Offline token sequences exceeding 128 tokens are explicitly
truncated to 127 tokens plus SEP, with counts recorded in manifest and metadata.
No source headword, Chinese meaning, or original dictionary record is changed.
All source dictionary records, attribution and reconstruction scripts are in:
https://github.com/dpan538/Annota/tree/main/design/dictionary
The APK also preserves original dictionary source archives and licenses.

The model performs English semantic similarity, not translation and not
verified sense disambiguation. Similarity is not a probability of correctness.
The model, tokenizer and index are bundled; lookup does not download assets,
contact a service, or upload text.
