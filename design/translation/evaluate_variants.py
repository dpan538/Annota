#!/usr/bin/env python3
"""Offline decoding-variant comparison for the frozen bundled OPUS-MT int8 en→zh model.

This script never downloads anything and never calls a service. It loads only the
bundled encoder/decoder ONNX files plus the frozen upstream SentencePiece model,
runs several decoding policies over a fixed development set, and reports chrF
against human references together with repetition, EOS-completeness, deadline and
per-sentence timing statistics.

The companion set ``evaluation/sentence-quality.json`` is a DEVELOPMENT set that
was authored while implementing the 1.2.1 decoding changes. It is not a random
sample, not a held-out set and not an accuracy benchmark; the numbers below only
compare decoding policies on the same fixed probes. macOS timings are not
Android timings.

Usage:
  python design/translation/evaluate_variants.py \
    --assets android/app/src/main/assets/translation \
    --source-spm /tmp/annota-dict/opus/source.spm \
    --output design/translation/evaluation/variant-report.json
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import platform
import statistics
import time
from dataclasses import dataclass
from pathlib import Path
import re

import numpy as np
import onnxruntime as ort
import sentencepiece as sp

MAX_OUTPUT_TOKENS = 160
MAX_SOURCE_TOKENS = 128
SOURCE_PREFIX = 5  # >>cmn_Hans<<
EOS = 0
PAD = 65000
VOCAB_LIMIT = 65000  # pad token 65000 is never generated
DEADLINE_SECONDS = 20.0
THREADS = 2
CHRF_ORDER = 6
CHRF_BETA = 2.0

CJK = "\u3400-\u9fff"
CJK_LAST = re.compile(rf"[{CJK}]$")
TERMINATORS = set("。！？；：….,!?;:）】》”』")
NEGATION_SOURCE = re.compile(
    r"\b(not|no|never|without|neither|nor|none|nothing|cannot|can't|don't|"
    r"doesn't|didn't|isn't|aren't|wasn't|weren't|won't|wouldn't|shouldn't|"
    r"mustn't|do not|does not|did not|is not|are not|will not)\b",
    re.IGNORECASE,
)
NEGATION_OUTPUT = re.compile(r"[不未无没勿非否]")
DIGITS = re.compile(r"\d+(?:[.,:]\d+)*")


# ---------------------------------------------------------------------------
# Metrics
# ---------------------------------------------------------------------------
def chrf(hypothesis: str, reference: str, order: int = CHRF_ORDER, beta: float = CHRF_BETA) -> float:
    """chrF (char n-gram F-score), whitespace removed, beta=2, orders 1..6.

    This is the standard chrF definition used by sacrebleu with its default
    whitespace handling. Character n-grams are extracted after deleting all
    whitespace; the score is the mean of the per-order F scores.
    """
    hyp = "".join(hypothesis.split())
    ref = "".join(reference.split())
    if not hyp or not ref:
        return 0.0
    beta2 = beta * beta
    total = 0.0
    for n in range(1, order + 1):
        hyp_counts: dict[str, int] = {}
        for i in range(len(hyp) - n + 1):
            gram = hyp[i : i + n]
            hyp_counts[gram] = hyp_counts.get(gram, 0) + 1
        ref_counts: dict[str, int] = {}
        for i in range(len(ref) - n + 1):
            gram = ref[i : i + n]
            ref_counts[gram] = ref_counts.get(gram, 0) + 1
        matches = 0
        for gram, count in hyp_counts.items():
            matches += min(count, ref_counts.get(gram, 0))
        hyp_total = sum(hyp_counts.values())
        ref_total = sum(ref_counts.values())
        if matches == 0 or hyp_total == 0 or ref_total == 0:
            continue  # zero contribution, also when the hypothesis is shorter than n
        precision = matches / hyp_total
        recall = matches / ref_total
        total += (1 + beta2) * precision * recall / (beta2 * precision + recall)
    return round(100.0 * total / order, 2)


def repetition_stats(text: str) -> dict:
    """Character n-gram repetition metrics on the decoded text (whitespace removed)."""
    chars = "".join(text.split())
    result = {}
    for n in (2, 4):
        grams = [chars[i : i + n] for i in range(max(0, len(chars) - n + 1))]
        total = len(grams)
        distinct = len(set(grams))
        result[f"repeat{n}Ratio"] = round(1.0 - distinct / total, 4) if total else 0.0
    best = 0
    for start in range(len(chars)):
        limit = (len(chars) - start) // 2
        for length in range(1, limit + 1):
            if chars[start : start + length] == chars[start + length : start + 2 * length]:
                best = max(best, length)
    result["maxRepeatedBlock"] = best
    return result


def digit_sequences(text: str) -> list[str]:
    return DIGITS.findall(text)


def digits_preserved(source: str, translation: str) -> bool:
    """Digits must survive decoding. Clock values may be re-punctuated by the model
    (9:30 -> 9时30分), so a colon-separated time only needs its components."""
    for value in digit_sequences(source):
        if ":" in value:
            if not all(part in translation for part in value.split(":")):
                return False
        elif value not in translation:
            return False
    return True


def negation_present(source: str) -> bool:
    return bool(NEGATION_SOURCE.search(source))


def negation_preserved(source: str, translation: str) -> bool:
    return (not negation_present(source)) or bool(NEGATION_OUTPUT.search(translation))


# ---------------------------------------------------------------------------
# Output post-processing (mirrored in OfflineTranslator.kt)
# ---------------------------------------------------------------------------
PUNCT_MAP = {",": "，", ";": "；", "!": "！", "?": "？", ":": "：", ".": "。"}
CJK_CLASS = rf"[{CJK}]"


def postprocess(text: str) -> str:
    """Normalize Chinese punctuation/spacing without touching digits or Latin names."""
    if not text:
        return ""
    # Collapse whitespace, then remove spaces that touch a CJK character. Digits and
    # Latin words are never rewritten; only surrounding spaces are dropped.
    value = re.sub(r"[ \t\u00a0]+", " ", text)
    value = re.sub(rf"\s*({CJK_CLASS})\s*", r"\1", value)
    # No space before ASCII punctuation (the model occasionally emits one).
    value = re.sub(r"\s+([,.;:!?])", r"\1", value)
    # ASCII punctuation touching a CJK character becomes full-width. A period is only
    # converted when it follows CJK, so decimals/abbreviations keep their ASCII form.
    value = re.sub(rf"(?<={CJK_CLASS})\s*([,;:!?])", lambda m: PUNCT_MAP[m.group(1)], value)
    value = re.sub(rf"([,;:!?])(?={CJK_CLASS})", lambda m: PUNCT_MAP[m.group(1)], value)
    value = re.sub(rf"(?<={CJK_CLASS})\.", "。", value)
    # Merge blank lines and trim each line.
    value = re.sub(r"\n\s*\n+", "\n", value)
    value = "\n".join(line.strip() for line in value.splitlines() if line.strip())
    if not value:
        return ""
    last = value[-1]
    if (CJK_LAST.search(value) or last.isalnum() or last in ")]}") and last not in TERMINATORS:
        value += "。"
    return value


# ---------------------------------------------------------------------------
# Model wrapper
# ---------------------------------------------------------------------------
@dataclass
class Hypothesis:
    tokens: tuple
    score: float
    last: int
    decoder_cache: dict


@dataclass
class DecodeResult:
    translation: str
    tokens: tuple
    ended: bool
    timeout: bool
    error: str | None
    seconds: float


class Model:
    def __init__(self, assets: Path, source_spm: Path):
        ort.disable_telemetry_events()
        self.vocab = json.loads((assets / "vocab.json").read_text())
        self.inverse = {i: piece for piece, i in self.vocab.items()}
        self.tokenizer = sp.SentencePieceProcessor(model_file=str(source_spm))
        options = ort.SessionOptions()
        options.intra_op_num_threads = THREADS
        options.inter_op_num_threads = 1
        self.encoder = ort.InferenceSession(
            str(assets / "encoder.onnx"), options, providers=["CPUExecutionProvider"]
        )
        self.decoder = ort.InferenceSession(
            str(assets / "decoder.onnx"), options, providers=["CPUExecutionProvider"]
        )
        self.output_names = [output.name for output in self.decoder.get_outputs()]
        self.empty_cache = np.empty((1, 8, 0, 64), dtype=np.float32)

    def encode(self, text: str) -> tuple[list[int], np.ndarray]:
        ids = [SOURCE_PREFIX] + [
            self.vocab.get(piece, 1) for piece in self.tokenizer.encode(text, out_type=str)
        ] + [0]
        mask = np.ones((1, len(ids)), dtype=np.int64)
        return ids, mask

    def encode_source(self, ids: list[int], mask: np.ndarray) -> np.ndarray:
        encoded = self.encoder.run(
            None, {"input_ids": np.array([ids], dtype=np.int64), "attention_mask": mask}
        )[0]
        return encoded

    def forward(
        self,
        token: int,
        step: int,
        encoder_cache: dict,
        decoder_cache: dict,
        encoded: np.ndarray,
        mask: np.ndarray,
    ):
        feed = {
            "input_ids": np.array([[token]], dtype=np.int64),
            "encoder_attention_mask": mask,
            "encoder_hidden_states": encoded,
            "use_cache_branch": np.array([step > 0]),
        }
        for layer in range(6):
            for side in ("encoder", "decoder"):
                cache = encoder_cache if side == "encoder" else decoder_cache
                for kind in ("key", "value"):
                    name = f"past_key_values.{layer}.{side}.{kind}"
                    feed[name] = cache.get(name, self.empty_cache)
        values = self.decoder.run(None, feed)
        logits = values[0][0, -1, :VOCAB_LIMIT]
        new_encoder: dict = {}
        new_decoder: dict = {}
        for output_name, value in zip(self.output_names[1:], values[1:]):
            if ".encoder." in output_name:
                if step == 0:
                    new_encoder[output_name.replace("present.", "past_key_values.")] = value
            else:
                new_decoder[output_name.replace("present.", "past_key_values.")] = value
        return logits, (new_encoder or None), new_decoder

    def decode_tokens(self, tokens) -> str:
        return "".join(self.inverse[token] for token in tokens).replace("▁", " ").strip()

    # -- policy helpers ----------------------------------------------------
    @staticmethod
    def log_softmax(logits: np.ndarray) -> np.ndarray:
        peak = float(np.max(logits))
        values = logits - peak
        return values - math.log(float(np.sum(np.exp(values))))

    @staticmethod
    def apply_policy(logits: np.ndarray, tokens: tuple, policy: dict) -> np.ndarray:
        penalty = policy.get("repetitionPenalty", 1.0)
        if penalty > 1.0 and tokens:
            for token in set(tokens):
                if logits[token] > 0:
                    logits[token] /= penalty
                else:
                    logits[token] *= penalty
        logp = Model.log_softmax(logits)
        min_eos_tokens = policy.get("minEosTokens", 0)
        if len(tokens) < min_eos_tokens:
            logp[EOS] = -np.inf
        if policy.get("noRepeat4") and len(tokens) >= 3:
            prefix = tokens[-3:]
            blocked = set()
            for start in range(len(tokens) - 3):
                if tokens[start : start + 3] == prefix:
                    blocked.add(tokens[start + 3])
            for token in blocked:
                logp[token] = -np.inf
        return logp

    @staticmethod
    def top_tokens(scores: np.ndarray, count: int) -> list[int]:
        count = max(1, min(count, len(scores)))
        if count == len(scores):
            order = np.argsort(-scores)
        else:
            order = np.argpartition(-scores, count - 1)[:count]
            order = order[np.argsort(-scores[order])]
        return [int(token) for token in order]

    # -- decoding ----------------------------------------------------------
    def greedy(
        self, source: str, policy: dict, deadline: float
    ):
        started = time.monotonic()
        ids, mask = self.encode(source)
        if len(ids) > MAX_SOURCE_TOKENS:
            return None
        encoded = self.encode_source(ids, mask)
        tokens: list[int] = []
        encoder_cache: dict = {}
        decoder_cache: dict = {}
        next_token = PAD
        for step in range(MAX_OUTPUT_TOKENS):
            if time.monotonic() - started > deadline:
                return self._result(tokens, False, True, "deadline", started)
            logits, new_encoder, new_decoder = self.forward(
                next_token, step, encoder_cache, decoder_cache, encoded, mask
            )
            if step == 0 and new_encoder:
                encoder_cache = new_encoder
            decoder_cache = new_decoder
            logp = self.apply_policy(logits, tuple(tokens), policy)
            next_token = int(np.argmax(logp))
            if next_token == EOS:
                return self._result(tokens, True, False, None, started)
            tokens.append(next_token)
        return self._result(tokens, False, False, "output-token-budget", started)

    def beam(
        self, source: str, policy: dict, deadline: float
    ):
        started = time.monotonic()
        beams = int(policy["beam"])
        alpha = float(policy.get("lenAlpha", 0.0))
        ids, mask = self.encode(source)
        if len(ids) > MAX_SOURCE_TOKENS:
            return None
        encoded = self.encode_source(ids, mask)
        logits, encoder_cache, decoder_cache = self.forward(
            PAD, 0, {}, {}, encoded, mask
        )
        logp = self.apply_policy(logits, (), policy)
        alive: list[Hypothesis] = []
        finished: list[Hypothesis] = []
        for rank, token in enumerate(self.top_tokens(logp, beams + 1)):
            if token == EOS:
                if rank < beams:
                    finished.append(
                        Hypothesis((), float(logp[EOS]), EOS, decoder_cache)
                    )
            elif len(alive) < beams:
                alive.append(
                    Hypothesis((token,), float(logp[token]), token, decoder_cache)
                )
            if len(alive) >= beams:
                break

        def normalized(hypothesis: Hypothesis) -> float:
            length = max(1, len(hypothesis.tokens))
            return hypothesis.score / (length ** alpha) if alpha else hypothesis.score

        for step in range(1, MAX_OUTPUT_TOKENS):
            if not alive:
                break
            if time.monotonic() - started > deadline:
                best = max(finished, key=normalized) if finished else max(alive, key=normalized)
                return self._result(best.tokens, False, True, "deadline", started)
            candidates = []
            for index, hypothesis in enumerate(alive):
                logits, _, new_decoder = self.forward(
                    hypothesis.last,
                    step,
                    encoder_cache,
                    hypothesis.decoder_cache,
                    encoded,
                    mask,
                )
                logp = self.apply_policy(logits, hypothesis.tokens, policy)
                scores = hypothesis.score + logp
                for token in self.top_tokens(scores, beams + 1):
                    candidates.append(
                        (float(scores[token]), token, index, new_decoder)
                    )
            candidates.sort(key=lambda item: item[0], reverse=True)
            new_alive: list[Hypothesis] = []
            for rank, (score, token, index, new_decoder) in enumerate(candidates):
                if token == EOS:
                    if rank < beams:
                        finished.append(
                            Hypothesis(alive[index].tokens, score, EOS, new_decoder)
                        )
                elif len(new_alive) < beams:
                    new_alive.append(
                        Hypothesis(
                            alive[index].tokens + (token,), score, token, new_decoder
                        )
                    )
                if len(new_alive) >= beams:
                    break
            alive = new_alive
            # Enough completed hypotheses: beam search normally stops here instead of
            # running the surviving alternates to the 160-token cap.
            if len(finished) >= beams:
                break

        if finished:
            best = max(finished, key=normalized)
            return self._result(best.tokens, True, False, None, started)
        if alive:
            best = max(alive, key=normalized)
            return self._result(best.tokens, False, False, "output-token-budget", started)
        return self._result((), False, False, "empty-output", started)

    def _result(
        self, tokens, ended: bool, timeout: bool, error: str | None, started: float
    ) -> DecodeResult:
        raw = self.decode_tokens(tokens)
        if error is None and "<unk>" in raw:
            error = "unknown-output-token"
        if error is None and not re.search(f"[{CJK}]", raw):
            error = "no-chinese"
        return DecodeResult(
            translation=raw,
            tokens=tuple(tokens),
            ended=ended,
            timeout=timeout,
            error=error,
            seconds=time.monotonic() - started,
        )

    def translate(self, source: str, policy: dict, deadline: float):
        if policy.get("beam", 1) > 1:
            return self.beam(source, policy, deadline)
        return self.greedy(source, policy, deadline)


# ---------------------------------------------------------------------------
# Variants
# ---------------------------------------------------------------------------
VARIANTS = [
    {
        "name": "greedy",
        "description": "Current shipped baseline: greedy argmax over tokens <65000, stop at EOS, 160-token cap.",
        "beam": 1,
        "lenAlpha": 0.0,
        "repetitionPenalty": 1.0,
        "noRepeat4": False,
        "minEosTokens": 0,
    },
    {
        "name": "no_repeat4",
        "description": "Greedy with fairseq-style no-repeat 4-gram blocking: a token completing a 4-gram that already occurred is banned.",
        "beam": 1,
        "lenAlpha": 0.0,
        "repetitionPenalty": 1.0,
        "noRepeat4": True,
        "minEosTokens": 0,
    },
    {
        "name": "repetition_penalty_1.2",
        "description": "Greedy with HuggingFace-style repetition penalty 1.2 on logits of already generated tokens.",
        "beam": 1,
        "lenAlpha": 0.0,
        "repetitionPenalty": 1.2,
        "noRepeat4": False,
        "minEosTokens": 0,
    },
    {
        "name": "eos_guard_min4",
        "description": "Greedy with an early-EOS guard: EOS (token 0) is masked until 4 output tokens exist.",
        "beam": 1,
        "lenAlpha": 0.0,
        "repetitionPenalty": 1.0,
        "noRepeat4": False,
        "minEosTokens": 4,
    },
    {
        "name": "guard_combo",
        "description": "Cheap guard combination: no-repeat 4-gram + repetition penalty 1.2 + early-EOS guard (min 4 tokens).",
        "beam": 1,
        "lenAlpha": 0.0,
        "repetitionPenalty": 1.2,
        "noRepeat4": True,
        "minEosTokens": 4,
    },
    {
        "name": "beam2_len0.6",
        "description": "Beam search width 2 with length normalization score/len^0.6 at final selection.",
        "beam": 2,
        "lenAlpha": 0.6,
        "repetitionPenalty": 1.0,
        "noRepeat4": False,
        "minEosTokens": 0,
    },
    {
        "name": "beam4_len0.6",
        "description": "Beam search width 4 with length normalization score/len^0.6 at final selection.",
        "beam": 4,
        "lenAlpha": 0.6,
        "repetitionPenalty": 1.0,
        "noRepeat4": False,
        "minEosTokens": 0,
    },
    {
        "name": "beam4_len0.6_guards",
        "description": "Beam 4 + length normalization 0.6 + no-repeat 4-gram + repetition penalty 1.2 + early-EOS guard (min 4).",
        "beam": 4,
        "lenAlpha": 0.6,
        "repetitionPenalty": 1.2,
        "noRepeat4": True,
        "minEosTokens": 4,
    },
]


# ---------------------------------------------------------------------------
# Report
# ---------------------------------------------------------------------------
def summarize(cases: list[dict], greedy_total: float | None, greedy_chrf: float | None) -> dict:
    measured = [case for case in cases if case["seconds"] is not None]
    seconds = [case["seconds"] for case in measured]
    chrfs = [case["chrF"] for case in cases]
    raw_chrfs = [case["rawChrF"] for case in cases]
    aggregate = {
        "cases": len(cases),
        "meanChrF": round(statistics.mean(chrfs), 2),
        "medianChrF": round(statistics.median(chrfs), 2),
        "minChrF": round(min(chrfs), 2),
        "rawMeanChrF": round(statistics.mean(raw_chrfs), 2),
        "meanRepeat2Ratio": round(
            statistics.mean(case["repeat2Ratio"] for case in cases), 4
        ),
        "meanRepeat4Ratio": round(
            statistics.mean(case["repeat4Ratio"] for case in cases), 4
        ),
        "maxRepeatedBlockChars": max(case["maxRepeatedBlock"] for case in cases),
        "repeat4Cases": sum(1 for case in cases if case["repeat4Ratio"] > 0.1),
        "ended": sum(1 for case in cases if case["ended"]),
        "endedRate": round(100.0 * sum(1 for case in cases if case["ended"]) / len(cases), 1),
        "truncatedCases": sum(1 for case in cases if not case["ended"]),
        "timeoutCases": sum(1 for case in cases if case["timeout"]),
        "unknownTokenCases": sum(1 for case in cases if case["error"] == "unknown-output-token"),
        "meanSeconds": round(statistics.mean(seconds), 4),
        "medianSeconds": round(statistics.median(seconds), 4),
        "p95Seconds": round(
            sorted(seconds)[max(0, math.ceil(0.95 * len(seconds)) - 1)], 4
        ),
        "totalSeconds": round(sum(seconds), 3),
        "digitPreservedRate": round(
            100.0
            * sum(1 for case in cases if case["hasDigits"] and case["digitsPreserved"])
            / max(1, sum(1 for case in cases if case["hasDigits"])),
            1,
        ),
        "negationPreservedRate": round(
            100.0
            * sum(
                1
                for case in cases
                if case["hasNegation"] and case["negationPreserved"]
            )
            / max(1, sum(1 for case in cases if case["hasNegation"])),
            1,
        ),
    }
    if greedy_total is not None and greedy_chrf is not None:
        aggregate["chrFGainVsGreedy"] = round(aggregate["meanChrF"] - greedy_chrf, 2)
        aggregate["secondsMultiplierVsGreedy"] = round(
            aggregate["totalSeconds"] / greedy_total, 2
        )
    return aggregate


def run_variant(model: Model, variant: dict, cases: list[dict], deadline: float) -> dict:
    rows = []
    for case in cases:
        result = model.translate(case["source"], variant, deadline)
        if result is None:
            rows.append(
                {
                    "id": case["id"],
                    "domain": case["domain"],
                    "source": case["source"],
                    "reference": case["reference"],
                    "translation": None,
                    "normalized": None,
                    "chrF": 0.0,
                    "rawChrF": 0.0,
                    "ended": False,
                    "timeout": False,
                    "outputTokens": 0,
                    "seconds": None,
                    "error": "source-token-budget",
                    "repeat2Ratio": 0.0,
                    "repeat4Ratio": 0.0,
                    "maxRepeatedBlock": 0,
                    "digitsPreserved": False,
                    "hasDigits": bool(digit_sequences(case["source"])),
                    "negationPreserved": False,
                    "hasNegation": negation_present(case["source"]),
                }
            )
            continue
        normalized = postprocess(result.translation)
        repeat = repetition_stats(normalized)
        rows.append(
            {
                "id": case["id"],
                "domain": case["domain"],
                "source": case["source"],
                "reference": case["reference"],
                "translation": result.translation,
                "normalized": normalized,
                "chrF": chrf(normalized, case["reference"]),
                "rawChrF": chrf(result.translation, case["reference"]),
                "ended": result.ended,
                "timeout": result.timeout,
                "outputTokens": len(result.tokens),
                "seconds": round(result.seconds, 4),
                "error": result.error,
                "repeat2Ratio": repeat["repeat2Ratio"],
                "repeat4Ratio": repeat["repeat4Ratio"],
                "maxRepeatedBlock": repeat["maxRepeatedBlock"],
                "digitsPreserved": digits_preserved(case["source"], normalized),
                "hasDigits": bool(digit_sequences(case["source"])),
                "negationPreserved": negation_preserved(case["source"], normalized),
                "hasNegation": negation_present(case["source"]),
            }
        )

    failures = []
    for row in rows:
        reasons = []
        if row["timeout"]:
            reasons.append("timeout")
        if not row["ended"]:
            reasons.append("not-finished-eos")
        if row["error"] == "unknown-output-token":
            reasons.append("unknown-output-token")
        if row["repeat4Ratio"] > 0.1:
            reasons.append("repeat4>0.1")
        if row["maxRepeatedBlock"] >= 4:
            reasons.append("repeated-block>=4")
        if row["chrF"] < 25:
            reasons.append("chrF<25")
        if reasons:
            failures.append(
                {
                    "id": row["id"],
                    "source": row["source"],
                    "reference": row["reference"],
                    "translation": row["normalized"],
                    "chrF": row["chrF"],
                    "reasons": reasons,
                }
            )
    failures.sort(key=lambda item: item["chrF"])
    return {"rows": rows, "failures": failures[:10]}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--assets",
        type=Path,
        default=Path("android/app/src/main/assets/translation"),
    )
    parser.add_argument(
        "--source-spm", type=Path, default=Path("/tmp/annota-dict/opus/source.spm")
    )
    parser.add_argument(
        "--cases",
        type=Path,
        default=Path(__file__).parent / "evaluation/sentence-quality.json",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path(__file__).parent / "evaluation/variant-report.json",
    )
    parser.add_argument(
        "--variants",
        default="",
        help="Comma-separated subset of variant names (default: all)",
    )
    parser.add_argument("--deadline-seconds", type=float, default=DEADLINE_SECONDS)
    args = parser.parse_args()
    deadline = args.deadline_seconds

    cases = json.loads(args.cases.read_text())["cases"]
    wanted = {name.strip() for name in args.variants.split(",") if name.strip()}
    variants = [variant for variant in VARIANTS if not wanted or variant["name"] in wanted]
    if not variants:
        raise SystemExit(f"No matching variants in {sorted(wanted)}")

    started = time.monotonic()
    model = Model(args.assets, args.source_spm)
    load_seconds = time.monotonic() - started
    print(
        f"loaded model in {load_seconds:.3f}s, {len(cases)} cases, "
        f"{len(variants)} variants",
        flush=True,
    )

    report_variants = []
    greedy_total = None
    greedy_chrf = None
    for variant in variants:
        run_started = time.monotonic()
        result = run_variant(model, variant, cases, deadline)
        aggregate = summarize(
            result["rows"],
            greedy_total if variant["name"] != "greedy" else None,
            greedy_chrf if variant["name"] != "greedy" else None,
        )
        if variant["name"] == "greedy":
            greedy_total = aggregate["totalSeconds"]
            greedy_chrf = aggregate["meanChrF"]
        report_variants.append(
            {
                "name": variant["name"],
                "description": variant["description"],
                "params": {
                    key: value for key, value in variant.items() if key not in ("name", "description")
                },
                "aggregate": aggregate,
                "failures": result["failures"],
                "cases": result["rows"],
            }
        )
        print(
            f"{variant['name']:<24} chrF={aggregate['meanChrF']:6.2f} "
            f"repeat4={aggregate['meanRepeat4Ratio']:.3f} "
            f"ended={aggregate['endedRate']:5.1f}% "
            f"trunc={aggregate['truncatedCases']} "
            f"time={aggregate['totalSeconds']:7.2f}s "
            f"mean={aggregate['meanSeconds']:.3f}s "
            f"(ran {time.monotonic() - run_started:.1f}s)",
            flush=True,
        )

    report = {
        "title": "1.2.1 offline en→zh decoding-variant comparison (development set)",
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "dataset": {
            "path": "evaluation/sentence-quality.json",
            "cases": len(cases),
            "declaration": (
                "Fixed development set authored while implementing the 1.2.1 decoding "
                "changes. It is not a random held-out corpus and NOT an accuracy "
                "benchmark or generalization claim; chrF is measured against one "
                "human reference wording per sentence."
            ),
        },
        "model": {
            "name": "Helsinki-NLP/opus-mt-en-zh, Xenova 046f55aec303cdee3e0318604406d4df20f1e8ea",
            "quantization": "int8 ONNX (bundled assets)",
            "sourceSpmSha256": hashlib.sha256(args.source_spm.read_bytes()).hexdigest(),
            "modelSha256": {
                name: hashlib.sha256((args.assets / name).read_bytes()).hexdigest()
                for name in ("encoder.onnx", "decoder.onnx")
            },
        },
        "environment": {
            "platform": platform.platform(),
            "onnxruntime": ort.__version__,
            "provider": "CPUExecutionProvider",
            "intraOpThreads": THREADS,
            "sessionLoadSeconds": round(load_seconds, 4),
        },
        "decoding": {
            "sourcePrefix": SOURCE_PREFIX,
            "eosToken": EOS,
            "padToken": PAD,
            "maxOutputTokens": MAX_OUTPUT_TOKENS,
            "maxSourceTokens": MAX_SOURCE_TOKENS,
            "perSentenceDeadlineSeconds": deadline,
            "lengthNormalization": "score / len^alpha at final selection (beam variants)",
            "repetitionPenalty": "HuggingFace-style divide/multiply on logits of generated tokens",
            "noRepeat4": "fairseq-style 4-gram blocking",
            "earlyEosGuard": "EOS masked until minEosTokens output tokens",
        },
        "postprocess": {
            "note": (
                "Same normalization is implemented in OfflineTranslator.kt: collapse "
                "whitespace, drop spaces around CJK, ASCII→full-width punctuation only "
                "next to CJK, add a final full stop, merge blank lines. Digits and "
                "Latin/proper names are never rewritten."
            ),
            "digitPreservedCheck": "all digit sequences in the source appear in the output (clock values may be re-punctuated, e.g. 9:30 -> 9时30分)",
        },
        "variants": report_variants,
        "limitations": [
            "Development set, not an accuracy benchmark: sentences were authored while developing these changes.",
            "chrF against one human reference wording measures similarity, not semantic correctness.",
            "The frozen int8 model still mistranslates idioms, legal terms and some multi-word terms; decoding changes do not fix modelling errors.",
            "macOS onnxruntime CPU timings are not Android/tablet timings.",
            "No variant is allowed to override model output with hard-coded sentence translations.",
        ],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {args.output}", flush=True)


if __name__ == "__main__":
    main()
