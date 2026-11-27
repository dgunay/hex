#!/usr/bin/env python3
"""Neural autocorrect models on the English gate's held-out sentences and on other sets in held-out/'s format.

For each set: word errors after decoding noisy taps (all, even rows, odd rows) and correct words changed when the
reference is typed on key centres. Each (model, weight) is one Replay run per set.

  model-bench.py --model NAME=GGUF ... --weights 1.0,1.5 [--sets gate,msgs:PATH.tsv.gz] [--out results.tsv]
"""
import argparse, gzip, importlib.util, os, subprocess, sys, tempfile, time
from pathlib import Path

ANDROID = Path(__file__).resolve().parents[2]
HERE = ANDROID / 'benchmark' / 'typing'
spec = importlib.util.spec_from_file_location('gate', HERE / 'quality-gate.py')
gate = importlib.util.module_from_spec(spec); spec.loader.exec_module(gate)
if os.environ.get('HEX_BENCH_CPUS'):
    # Replay's shards: (cpus - 2) // 4 processes of four threads each.
    gate.os.cpu_count = lambda: int(os.environ['HEX_BENCH_CPUS'])
WORK = Path(os.environ.get('HEX_LM_WORK') or ANDROID / '.dev/lm')
LIB = WORK / 'host/build/libhextyping.so'


def load(path):
    with gzip.open(path, 'rt', encoding='utf-8') as f:
        return [line.rstrip('\n').split('\t') for line in f if line.strip()]


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--model', action='append', default=[], help='NAME=GGUF, or NAME=none for the word pairs alone')
    p.add_argument('--weights', default='1.5')
    p.add_argument('--sets', default='gate')
    p.add_argument('--out', type=Path, default=WORK / 'bench-results.tsv')
    p.add_argument('--limit', type=int, default=0)
    a = p.parse_args()
    sets = {}
    for s in a.sets.split(','):
        name, _, path = s.partition(':')
        rows = load(Path(path) if path else HERE / 'held-out' / 'en.tsv.gz')
        sets[name] = rows[:a.limit] if a.limit else rows
    with tempfile.TemporaryDirectory() as scratch:
        tmp = Path(scratch)
        cp = tmp / 'classpath'
        subprocess.run([ANDROID / 'gradlew', '-q', '-I', HERE / 'gate-classpath.gradle', ':app:typingGateClasspath',
                        '-x', 'prepareSpeechModel', '-PgateClasspath=' + str(cp)], cwd=ANDROID, check=True)
        jdk = Path(os.environ['JAVA_HOME']) / 'bin' if 'JAVA_HOME' in os.environ else Path('')
        subprocess.run([jdk / 'javac', '-encoding', 'UTF-8', '-cp', cp.read_text(), '-d', tmp, HERE / 'Replay.java'], check=True)
        base = [str(jdk / 'java'), '-Xmx768m', '-Dfile.encoding=UTF-8', '-cp', str(tmp) + os.pathsep + cp.read_text()]
        hextm = gate.MODELS / 'en.hextm'
        new = not a.out.exists()
        out = a.out.open('a', encoding='utf-8')
        if new:
            out.write('model\tweight\tset\twer\twer_even\twer_odd\tchanged\tsentences_right\tseconds\n')
        for spec_ in a.model:
            name, _, gguf = spec_.partition('=')
            for weight in ([None] if gguf == 'none' else a.weights.split(',')):
                command = list(base)
                if gguf != 'none':
                    command[1:1] = ['-Dhex.typing.model=' + str(Path(gguf).resolve()), '-Dhex.typing.lib=' + str(LIB),
                                    f'-Dhex.typing.weight={weight}']
                for set_name, rows in sets.items():
                    refs = [r[2].lower().split() for r in rows]
                    inputs = [r[3] + '\t' + r[4] for r in rows] + [r[2] + ' ' for r in rows]
                    path = tmp / 'input.tsv'
                    path.write_text('\n'.join(inputs) + '\n', encoding='utf-8')
                    t = time.time()
                    outputs = gate.replay_gate(command, hextm, path, inputs, 'en', tmp)
                    seconds = time.time() - t
                    n = len(rows)
                    noisy, clean = outputs[:n], outputs[n:2 * n]
                    def wer(idx):
                        e = sum(gate.align(refs[i], noisy[i].lower().split())[0] for i in idx)
                        return 100 * e / sum(len(refs[i]) for i in idx)
                    changed = total = 0
                    for ref, hyp in zip(refs, clean):
                        h = hyp.lower().split()
                        for w, pos in zip(ref, gate.align(ref, h)[1]):
                            total += 1; changed += pos is None or h[pos] != w
                    right = sum(ref == o.lower().split() for ref, o in zip(refs, noisy))
                    line = (f'{name}\t{weight or "-"}\t{set_name}\t{wer(range(n)):.2f}\t{wer(range(0, n, 2)):.2f}\t'
                            f'{wer(range(1, n, 2)):.2f}\t{100 * changed / total:.2f}\t{100 * right / n:.1f}\t{seconds:.0f}')
                    print(line, flush=True); out.write(line + '\n'); out.flush()


main()
