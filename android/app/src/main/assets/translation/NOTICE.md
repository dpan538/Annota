Offline English → Simplified Chinese reference translation

Model: Helsinki-NLP / Language Technology Research Group, University of Helsinki.
https://huggingface.co/Helsinki-NLP/opus-mt-en-zh
The model card identifies Apache-2.0; its complete text is included.
The upstream OPUS-MT project also describes models as CC BY 4.0. Attribution and
both upstream notices are retained in UPSTREAM-MODEL-CARD.md / UPSTREAM-OPUS-README.md.
https://github.com/Helsinki-NLP/OPUS-MT-train

ONNX conversion / quantization: Xenova (Joshua Lochner).
https://huggingface.co/Xenova/opus-mt-en-zh
Pinned revision: 046f55aec303cdee3e0318604406d4df20f1e8ea.
Annota derives source-unigram.json from the source SentencePiece model and shared
vocab without training or changing weights; CPU greedy decoding, Simplified Chinese
language prefix. Runtime: Microsoft ONNX Runtime, MIT license included separately.

All weights, tokenization and inference are on this device, with ORT telemetry
disabled. No Google service, download, API, remote fallback or account is involved.
Machine translation is a reference, not a verified dictionary definition. Names,
technical terminology, ambiguous expressions and numerical meaning can be wrong.
