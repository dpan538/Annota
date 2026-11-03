#!/usr/bin/env python3
"""CPU-only probes for frozen bundled models. Never downloads or invokes a service.

Requires numpy, onnxruntime and sentencepiece in the build/research environment.
Supply the corresponding frozen upstream source.spm (not needed by Android).
Reports outputs, timings and input size; deliberately does NOT score semantic
accuracy from string matching or from model-produced judgments.
"""
import argparse
import hashlib
import json
from pathlib import Path
import platform
import time
import numpy as np
import onnxruntime as ort
import sentencepiece as sp


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--assets', type=Path, required=True)
    parser.add_argument('--source-spm', type=Path, required=True)
    parser.add_argument('--cases', type=Path, default=Path(__file__).parent / 'evaluation/cross-domain.json')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    ort.disable_telemetry_events()
    vocab = json.loads((args.assets / 'vocab.json').read_text())
    inverse = {i: piece for piece, i in vocab.items()}
    tokenizer = sp.SentencePieceProcessor(model_file=str(args.source_spm))
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.inter_op_num_threads = 1
    started = time.monotonic()
    encoder = ort.InferenceSession(str(args.assets / 'encoder.onnx'), options, providers=['CPUExecutionProvider'])
    decoder = ort.InferenceSession(str(args.assets / 'decoder.onnx'), options, providers=['CPUExecutionProvider'])
    load_seconds = time.monotonic() - started
    names = [entry.name for entry in decoder.get_outputs()]

    def translate(text):
        started = time.monotonic()
        ids = [5] + [vocab.get(piece, 1) for piece in tokenizer.encode(text, out_type=str)] + [0]
        if len(ids) > 128:
            return {'inputTokens': len(ids), 'error': 'source-token-budget'}
        mask = np.ones((1, len(ids)), dtype=np.int64)
        encoded = encoder.run(None, {'input_ids': np.array([ids], dtype=np.int64), 'attention_mask': mask})[0]
        cache = {entry.name: np.empty((1, 8, 0, 64), dtype=np.float32)
                 for entry in decoder.get_inputs() if entry.name.startswith('past_key_values.')}
        output, token, ended = [], 65000, False
        for step in range(160):
            if time.monotonic() - started > 20:
                return {'inputTokens': len(ids), 'error': 'deadline'}
            values = decoder.run(None, {'input_ids': np.array([[token]], dtype=np.int64),
                'encoder_attention_mask': mask, 'encoder_hidden_states': encoded,
                'use_cache_branch': np.array([step > 0]), **cache})
            token = int(np.argmax(values[0][0, -1, :65000]))
            if token == 0:
                ended = True
                break
            output.append(token)
            for name, value in zip(names[1:], values[1:]):
                if '.encoder.' in name and step > 0:
                    continue
                cache[name.replace('present.', 'past_key_values.')] = value
        translation = ''.join(inverse[token] for token in output).replace('▁', ' ').strip()
        result = {'inputTokens': len(ids), 'outputTokens': len(output), 'translation': translation,
                  'seconds': round(time.monotonic() - started, 4), 'ended': ended}
        if not ended:
            result['error'] = 'output-token-budget'
        if '<unk>' in translation:
            result['error'] = 'unknown-output-token'
        return result

    rows = []
    for case in json.loads(args.cases.read_text())['cases']:
        row = dict(case)
        row['whole'] = translate(case['source'])
        if case.get('segments'):
            row['segmented'] = [translate(segment) for segment in case['segments']]
        rows.append(row)
        print(json.dumps(row, ensure_ascii=False), flush=True)
    report = {'model': 'Helsinki-NLP/opus-mt-en-zh, Xenova 046f55aec303cdee3e0318604406d4df20f1e8ea',
              'platform': platform.platform(), 'onnxruntime': ort.__version__, 'provider': 'CPUExecutionProvider',
              'threads': 2, 'sessionLoadSeconds': round(load_seconds, 4),
              'sourceSpmSha256': hashlib.sha256(args.source_spm.read_bytes()).hexdigest(),
              'modelSha256': {name: hashlib.sha256((args.assets / name).read_bytes()).hexdigest()
                              for name in ['encoder.onnx', 'decoder.onnx']},
              'limit': 'Authored diagnostic probes, not a random held-out corpus or accuracy benchmark; macOS timings are not Android timings.',
              'cases': rows}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')


if __name__ == '__main__':
    main()
