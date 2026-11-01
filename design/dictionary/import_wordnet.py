"""Offline importer. Supply the official WordNet 3.0 archive and COW tab file."""
import argparse
import gzip
import hashlib
import json
import tarfile
from collections import defaultdict
from pathlib import Path


def convert(archive, cow):
    chinese = defaultdict(list)
    for line in cow.read_text().splitlines():
        columns = line.split('\t')
        if len(columns) == 3 and columns[1] == 'cmn:lemma':
            chinese[columns[0]].append(columns[2])
    senses, exceptions = [], []
    with tarfile.open(archive) as tar:
        def lines(name):
            return tar.extractfile('WordNet-3.0/dict/' + name).read().decode().splitlines()
        for pos, part in [('n', 'noun'), ('v', 'verb'), ('a', 'adj'), ('r', 'adv')]:
            glosses = {}
            for line in lines('data.' + part):
                if not line or line[0].isspace():
                    continue
                fields, gloss = line.split('|', 1)
                fields = fields.split()
                glosses[fields[0]] = (fields[2], gloss.strip())
            for line in lines('index.' + part):
                if not line or line[0].isspace():
                    continue
                fields = line.split()
                count, pointers = int(fields[2]), int(fields[3])
                for rank, offset in enumerate(fields[6 + pointers:6 + pointers + count]):
                    synset = offset + '-' + pos
                    if chinese.get(synset):
                        _, gloss = glosses[offset]
                        senses.append([fields[0], pos, rank, synset,
                                       sorted(set(chinese[synset])), gloss])
            for line in lines(part + '.exc'):
                form, *lemmas = line.split()
                exceptions.extend([form, lemma, pos] for lemma in lemmas)
    return {'cow_revision': '406bf83b3c507a3d1f26e88252d5d66893fd36bf',
            'senses': sorted(senses), 'exceptions': sorted(exceptions)}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('wordnet', type=Path)
    parser.add_argument('cow', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    data = convert(args.wordnet, args.cow)
    args.output.write_bytes(gzip.compress(json.dumps(data, ensure_ascii=False,
        separators=(',', ':')).encode(), mtime=0))
    print(json.dumps({'senses': len(data['senses']), 'exceptions': len(data['exceptions']),
        'inputs': {str(p): hashlib.sha256(p.read_bytes()).hexdigest()
                   for p in (args.wordnet, args.cow)}}, indent=2))
