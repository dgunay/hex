#!/usr/bin/env python3
"""Typing sets in held-out/'s format from plain sentences, with the English gate's own noise.

The noise is measured from the gate's held-out taps (rows whose taps line up one-to-one with the reference without
apostrophes): each letter key's mean offset from its centre, each row's spread, the space bar's taps, and how often a
space is missed. Each tap of a new sentence is drawn from that, and what it types is the key it lands on.

  make-set.py SENTENCES.txt OUT.tsv.gz [--seed N]      # one sentence a line
  make-set.py --check                                  # the gate's own references, typed again, against its taps
"""
import argparse, gzip, random, statistics, sys
from pathlib import Path

ANDROID = Path(__file__).resolve().parents[2]
ROWS = ['qwertyuiop', 'asdfghjkl', 'zxcvbnm']
PITCH, ROW_Y = 39.5, [66.5, 122.5, 178.5]
SPACE_TOP, SPACE_X = 206.5, (64.76, 303.1)


def centre(c):
    for r, row in enumerate(ROWS):
        if c in row:
            return 201 + (row.index(c) - (len(row) - 1) / 2) * PITCH, ROW_Y[r]


def hit(x, y):
    """The key a tap lands on: a letter, or ' ' for the space bar."""
    if y >= SPACE_TOP and SPACE_X[0] <= x <= SPACE_X[1]:
        return ' '
    r = min(range(3), key=lambda i: abs(ROW_Y[i] - y))
    if y >= SPACE_TOP:
        r = 2
    return min(ROWS[r], key=lambda c: abs(centre(c)[0] - x))


def measure():
    rows = [l.rstrip('\n').split('\t') for l in gzip.open(ANDROID / 'benchmark/typing/held-out/en.tsv.gz', 'rt')]
    offs, rowd, space, spaces, missed = {}, {0: [], 1: [], 2: []}, [], 0, 0
    for _, _, ref, typed, coords in rows:
        want = ref.replace("'", '') + ' '
        pts = [tuple(map(float, p.split(','))) for p in coords.split()]
        if len(want) != len(pts):
            missed += len(want) - len(pts); spaces += want.count(' ')
            continue
        spaces += want.count(' ')
        for c, (x, y) in zip(want.lower(), pts):
            if c == ' ':
                space.append((x, y))
            elif centre(c):
                cx, cy = centre(c)
                offs.setdefault(c, []).append((x - cx, y - cy))
    mean = {c: (statistics.mean(d[0] for d in v), statistics.mean(d[1] for d in v)) for c, v in offs.items()}
    for c, v in offs.items():
        r = next(i for i, row in enumerate(ROWS) if c in row)
        rowd[r] += [(dx - mean[c][0], dy - mean[c][1]) for dx, dy in v]
    sd = {r: (statistics.pstdev(d[0] for d in v), statistics.pstdev(d[1] for d in v)) for r, v in rowd.items()}
    sx, sy = zip(*space)
    return mean, sd, (statistics.mean(sx), statistics.pstdev(sx), statistics.mean(sy), statistics.pstdev(sy)), missed / spaces


def typeit(ref, rng, noise):
    mean, sd, sp, omit = noise
    typed, pts = [], []
    want = ref.replace("'", '')
    for i, c in enumerate(want):
        if c == ' ':
            if rng.random() < omit:
                continue
            x, y = rng.gauss(sp[0], sp[1]), rng.gauss(sp[2], sp[3])
        else:
            k = c.lower(); cx, cy = centre(k); r = next(j for j, row in enumerate(ROWS) if k in row)
            x, y = cx + mean[k][0] + rng.gauss(0, sd[r][0]), cy + mean[k][1] + rng.gauss(0, sd[r][1])
        h = hit(x, y)
        typed.append(h.upper() if c.isupper() and h != ' ' else h); pts.append(f'{x:.2f},{y:.2f}')
    x, y = rng.gauss(sp[0], sp[1]), rng.gauss(sp[2], sp[3])
    typed.append(' '); pts.append(f'{x:.2f},{y:.2f}')   # the space that finishes the last word, always on the bar
    return ''.join(typed), ' '.join(pts)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('sentences', nargs='?'); p.add_argument('out', nargs='?')
    p.add_argument('--seed', type=int, default=20261126); p.add_argument('--check', action='store_true')
    a = p.parse_args()
    noise = measure()
    print('space missed', round(noise[3] * 100, 2), '% · row spread', {r: tuple(round(v, 1) for v in s) for r, s in noise[1].items()},
          file=sys.stderr)
    rng = random.Random(a.seed)
    if a.check:
        rows = [l.rstrip('\n').split('\t') for l in gzip.open(ANDROID / 'benchmark/typing/held-out/en.tsv.gz', 'rt')]
        def raw(typed_list):
            e = n = 0
            for ref, t in zip((r[2].lower().split() for r in rows), typed_list):
                hyp = t.lower().split(); n += len(ref)
                e += sum(w != h for w, h in zip(ref, hyp)) + abs(len(ref) - len(hyp))
            return 100 * e / n
        ours = [typeit(r[2], rng, noise)[0] for r in rows]
        print(f'raw word errors (rough): gate taps {raw([r[3] for r in rows]):.1f}% · ours {raw(ours):.1f}%')
        letters = lambda ts: sum(a != b for r, t in zip(rows, ts) for a, b in zip(r[2].replace("'", '').lower(), t.lower()))
        print('letter mismatches: gate', letters([r[3] for r in rows]), 'ours', letters(ours))
        return
    with gzip.open(a.out, 'wt', encoding='utf-8') as out:
        for i, line in enumerate(open(a.sentences, encoding='utf-8')):
            ref = line.strip()
            if ref:
                t, pts = typeit(ref, rng, noise)
                out.write(f'{i}\t-\t{ref}\t{t}\t{pts}\n')


main()
