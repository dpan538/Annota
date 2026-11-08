"""Regenerate design/dictionary/sources-build/ from downloaded upstream files.

This script is NOT needed for a normal offline rebuild: the committed .gz files
in sources-build/ are the build inputs. It documents how those files were
derived, so a maintainer can refresh them from upstream intentionally:

  python3 design/dictionary/build-sources.py --source-dir /path/to/downloads

The download directory must contain (network is only used by the maintainer to
fetch these, never by build.py):
  oewn.xml                 English WordNet 2025 WN-LMF XML (uncompressed)
  ili-map-pwn30.tab        CILI ILI -> PWN30 map
  wn30/dict/data.{noun,verb,adj,adv} and index.sense   Princeton WordNet 3.0
  wn-data-qcn.tab          OMW wns/cwn/wn-data-qcn.tab
  wn-wikt-cmn.tab          OMW wns/wikt/wn-wikt-cmn.tab
  TSCharacters.txt         OpenCC data/dictionary/TSCharacters.txt

All gzip output uses mtime=0 so repeated runs are byte-identical. This script
writes only into sources-build/; it does not touch the APK assets.
"""
import argparse
import gzip
import hashlib
import io
import sys
from pathlib import Path

OUT = Path(__file__).resolve().parent / 'sources-build'
SRC = None

# data.* store the synset type as a letter (n, v, a, r, s); satellite
# adjectives ('s') keep the -s synset id but are stored as POS 'a'.
POS_BY_SS = {'n': 'n', 'v': 'v', 'a': 'a', 'r': 'r', 's': 'a'}
ID_BY_SS = {'n': 'n', 'v': 'v', 'a': 'a', 'r': 'r', 's': 's'}
DATA_FILES = ('data.noun', 'data.verb', 'data.adj', 'data.adv')


def gzip_bytes(data, level=9):
    buf = io.BytesIO()
    with gzip.GzipFile(filename='', mode='wb', fileobj=buf, compresslevel=level, mtime=0) as gz:
        gz.write(data)
    return buf.getvalue()


def compress_file(src, dst, level=9):
    """Stream-compress a file with mtime=0, returning decompressed sha256 and sizes."""
    h = hashlib.sha256()
    with src.open('rb') as fsrc, dst.open('wb') as fdst:
        with gzip.GzipFile(filename='', mode='wb', fileobj=fdst, compresslevel=level,
                           mtime=0) as gz:
            for chunk in iter(lambda: fsrc.read(1 << 20), b''):
                h.update(chunk)
                gz.write(chunk)
    return h.hexdigest()


def sha256_file(path):
    h = hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def gzip_is_reproducible(path):
    header = path.read_bytes()[:10]
    if header[:2] != b'\x1f\x8b':
        raise SystemExit(f'not gzip: {path}')
    mtime = int.from_bytes(header[4:8], 'little')
    if mtime != 0:
        raise SystemExit(f'mtime not 0 in {path}: {mtime}')
    return True


def build_wn30_synsets():
    """Derive id, pos, lemmas, gloss from WordNet 3.0 data.* files."""
    lines = []
    ids = set()
    for name in DATA_FILES:
        path = SRC / 'wn30/dict' / name
        with path.open(encoding='utf-8') as f:
            for line in f:
                if line.startswith('  '):  # license header block
                    continue
                line = line.rstrip('\n')
                if not line:
                    continue
                head, sep, gloss = line.partition(' | ')
                if not sep:
                    raise SystemExit(f'missing gloss separator in {name}: {line[:80]}')
                tokens = head.split(' ')
                # data.* encode the lemma count (and lex_id) as two hex digits.
                offset, ss_type, w_cnt = tokens[0], tokens[2], int(tokens[3], 16)
                lemmas = [tokens[4 + 2 * i].replace('_', ' ') for i in range(w_cnt)]
                synset = f'{int(offset):08d}-{ID_BY_SS[ss_type]}'
                if synset in ids:
                    raise SystemExit(f'duplicate synset id {synset}')
                ids.add(synset)
                lines.append('\t'.join((synset, POS_BY_SS[ss_type],
                                        '|'.join(lemmas), gloss.rstrip())))
    assert len(lines) == 117659, len(lines)
    return '\n'.join(lines) + '\n', len(lines)


def build_wn30_sense_order():
    """Derive frequency order per (lemma, pos) from index.sense sense_number."""
    w2p = {1: 'n', 2: 'v', 3: 'a', 4: 'r', 5: 'a'}
    id_by_digit = {1: 'n', 2: 'v', 3: 'a', 4: 'r', 5: 'a'}
    groups = {}
    lines = 0
    with (SRC / 'wn30/dict/index.sense').open(encoding='utf-8') as f:
        for line in f:
            parts = line.split()
            if not parts:
                continue
            lines += 1
            sense_key, offset, sense_number = parts[0], parts[1], int(parts[2])
            lemma, rest = sense_key.split('%', 1)
            ss_type = int(rest.split(':', 1)[0])
            key = (lemma.replace('_', ' '), w2p[ss_type])
            groups.setdefault(key, []).append((sense_number, f'{int(offset):08d}-'
                                               f'{id_by_digit[ss_type]}'))
    out = []
    for (word, pos), entries in sorted(groups.items()):
        # sense_number is the WordNet frequency rank (1 = most frequent); this
        # reproduces the exact synset order of index.noun/verb/adj/adv.
        ids = [entry for _, entry in sorted(entries)]
        if len(ids) != len(set(ids)):
            raise SystemExit(f'duplicate synset in sense list {word}/{pos}')
        out.append('\t'.join((word, pos, ' '.join(ids))))
    assert len(out) == 155287, len(out)
    return '\n'.join(out) + '\n', {'groups': len(out), 'senses': lines}


def main():
    global SRC
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-dir', type=Path, required=True,
                        help='directory containing the upstream downloads listed in the docstring')
    SRC = parser.parse_args().source_dir
    if not SRC.is_dir():
        raise SystemExit(f'missing source directory: {SRC}')
    OUT.mkdir(parents=True, exist_ok=True)
    report = []

    def record(name, source, rows=None):
        dst = OUT / name
        entry = {'file': name, 'bytes': dst.stat().st_size,
                 'sha256': sha256_file(dst),
                 'source': source, 'rows': rows}
        gzip_is_reproducible(dst)
        report.append(entry)
        print(f'{name:32s} {entry["bytes"]:>10,d} B  sha256={entry["sha256"]}  rows={rows}')

    # 1. OEWN 2025 WN-LMF XML (recompressed so the gzip header is fixed).
    raw_hash = compress_file(SRC / 'oewn.xml', OUT / 'oewn-2025.xml.gz')
    record('oewn-2025.xml.gz', 'oewn.xml', rows=107519)

    # 2. CILI ILI -> PWN30 synset map.
    compress_file(SRC / 'ili-map-pwn30.tab', OUT / 'ili-map-pwn30.tab.gz')
    record('ili-map-pwn30.tab.gz', 'ili-map-pwn30.tab', rows=117659)

    # 3. WordNet 3.0 synsets derived from data.*.
    tsv, count = build_wn30_synsets()
    (OUT / 'wn30-synsets.tsv.gz').write_bytes(gzip_bytes(tsv.encode('utf-8')))
    record('wn30-synsets.tsv.gz', 'wn30/dict/data.{noun,verb,adj,adv}', rows=count)

    # 4. WordNet 3.0 per-(lemma,pos) frequency order derived from index.sense.
    tsv, stats = build_wn30_sense_order()
    (OUT / 'wn30-sense-order.tsv.gz').write_bytes(gzip_bytes(tsv.encode('utf-8')))
    record('wn30-sense-order.tsv.gz', 'wn30/dict/index.sense', rows=stats)

    # 5-6. OMW Chinese wordnets: keep the upstream bytes (including # headers).
    compress_file(SRC / 'wn-data-qcn.tab', OUT / 'omw-cwn.tsv.gz')
    record('omw-cwn.tsv.gz', 'OMW wns/cwn/wn-data-qcn.tab', rows=8069)
    compress_file(SRC / 'wn-wikt-cmn.tab', OUT / 'omw-wikt-cmn.tsv.gz')
    record('omw-wikt-cmn.tsv.gz', 'OMW wns/wikt/wn-wikt-cmn.tab', rows=19079)

    # 7. OpenCC traditional -> simplified character table (kept verbatim).
    compress_file(SRC / 'TSCharacters.txt', OUT / 'opencc-tscharacters.txt.gz')
    record('opencc-tscharacters.txt.gz', 'OpenCC data/dictionary/TSCharacters.txt', rows=4148)

    # Reproducibility check: decompress and compare the OEWN payload hash.
    check = hashlib.sha256()
    with gzip.open(OUT / 'oewn-2025.xml.gz', 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            check.update(chunk)
    if check.hexdigest() != raw_hash:
        raise SystemExit('OEWN round-trip hash mismatch')
    print(f'\nOEWN payload sha256={raw_hash}')
    total = sum(e['bytes'] for e in report)
    print(f'total sources-build bytes: {total:,d} ({total / 1e6:.2f} MB decimal)')
    for entry in report:
        if entry['bytes'] > 12 * 1000 * 1000:
            print(f'NOTE >12MB: {entry["file"]}')


if __name__ == '__main__':
    sys.exit(main())
