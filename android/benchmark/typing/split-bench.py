#!/usr/bin/env python3
"""Run-together words: the English gate's held-out sentences with one space between two words missed ("dropped") or
typed as a stray letter ("stray": half a letter beside the space bar, c v b n m, half any letter, tapped near its
centre), beside the sentences as they are ("control"). Each set is replayed as the gate replays it, and scored as
word errors against the sentence meant. With --neural, Labs › Keyboard › Neural autocorrect is on.

  android/benchmark/typing/split-bench.py [--neural GGUF --neural-lib LIB] [--stray-cost NATS] [--sets control,dropped,stray]
"""
import argparse
import gzip
import importlib.util
import os
from pathlib import Path
import random
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('gate', HERE / 'quality-gate.py')
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)

ROWS = ['qwertyuiop', 'asdfghjkl', 'zxcvbnm']


def centre(letter):
    """The letter's centre on the gate's original geometry (Replay.originalGeometry, English)."""
    for r, row in enumerate(ROWS):
        if letter in row:
            return 201 + (row.index(letter) - (len(row) - 1) / 2) * 39.5, 66.5 + 56 * r


def variants(rows, seed=2026):
    """For each sentence, one space between two words of two letters or more, as (control, dropped, stray) inputs."""
    rng = random.Random(seed)
    sets = {'control': [], 'dropped': [], 'stray': []}
    for _, _, _, typed, coords in rows:
        points = coords.split(' ')
        sets['control'].append(typed + '\t' + coords)
        joins = [j for j in range(1, len(typed) - 1) if typed[j] == ' ' and typed[j - 1].isalpha() and typed[j + 1].isalpha()
                 and j >= 2 and typed[j - 2].isalpha() and j + 2 < len(typed) and typed[j + 2].isalpha()]
        if not joins:
            sets['dropped'].append(typed + '\t' + coords); sets['stray'].append(typed + '\t' + coords)
            continue
        j = rng.choice(joins)
        sets['dropped'].append(typed[:j] + typed[j + 1:] + '\t' + ' '.join(points[:j] + points[j + 1:]))
        letter = rng.choice('cvbnm') if rng.random() < 0.5 else rng.choice(''.join(ROWS))
        x, y = centre(letter)
        x += rng.gauss(0, 39.5 * 0.2); y += rng.gauss(0, 56 * 0.15)
        sets['stray'].append(typed[:j] + letter + typed[j + 1:] + '\t' + ' '.join(points[:j] + [f'{x:.2f},{y:.2f}'] + points[j + 1:]))
    return sets


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--neural', type=Path)
    parser.add_argument('--neural-lib', type=Path)
    parser.add_argument('--stray-cost', type=float, help="a stray letter's cost beside a missed space (default 4)")
    parser.add_argument('--no-splits', action='store_true', help="the model without its own run-together readings, to compare")
    parser.add_argument('--sets', default='control,dropped,stray')
    parser.add_argument('--out', type=Path, default=gate.ANDROID / 'build/split-bench')
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    with gzip.open(HERE / 'held-out' / 'en.tsv.gz', 'rt', encoding='utf-8') as f:
        rows = [line.rstrip('\n').split('\t') for line in f]
    refs = [text.lower().split() for _, _, text, _, _ in rows]
    sets = variants(rows)
    with tempfile.TemporaryDirectory() as scratch:
        tmp = Path(scratch)
        cp = tmp / 'classpath'
        subprocess.run([gate.ANDROID / 'gradlew', '-q', '-I', HERE / 'gate-classpath.gradle', ':app:typingGateClasspath',
                        '-x', 'prepareSpeechModel', '-PgateClasspath=' + str(cp)], cwd=gate.ANDROID, check=True)
        jdk = Path(os.environ['JAVA_HOME']) / 'bin' if 'JAVA_HOME' in os.environ else Path('')
        subprocess.run([jdk / 'javac', '-encoding', 'UTF-8', '-cp', cp.read_text(), '-d', tmp, HERE / 'Replay.java'], check=True)
        command = [jdk / 'java', '-Xmx768m', '-Dfile.encoding=UTF-8', '-cp', str(tmp) + os.pathsep + cp.read_text()]
        if args.neural:
            command[1:1] = ['-Dhex.typing.model=' + str(args.neural.resolve()), '-Dhex.typing.lib=' + str(args.neural_lib.resolve())]
        if args.no_splits:
            command[1:1] = ['-Dhex.typing.splits=false']
        if args.stray_cost is not None:
            command[1:1] = [f'-Dhex.typing.stray={args.stray_cost}']
        model = gate.MODELS / 'en.hextm'
        words = sum(map(len, refs))
        tag = ('neural' if args.neural else 'pairs') + (f'-{args.stray_cost:g}' if args.stray_cost is not None else '') + ('-nosplits' if args.no_splits else '')
        for name in args.sets.split(','):
            path = tmp / f'{name}.tsv'
            path.write_text('\n'.join(sets[name]) + '\n', encoding='utf-8')
            outputs = gate.replay_gate(command, model, path, sets[name], 'en', tmp)
            (args.out / f'{name}-{tag}.txt').write_text('\n'.join(outputs) + '\n', encoding='utf-8')
            errors = gate.word_errors(refs, outputs)
            right = sum(ref == out.lower().split() for ref, out in zip(refs, outputs))
            print(f'{tag:14} {name:8} word errors {100 * errors / words:5.2f}%  sentences right {100 * right / len(refs):5.1f}%', flush=True)


main()
