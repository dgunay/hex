#!/usr/bin/env python3
"""Messages-style sentences for the typing sets, and the fine-tuning text, from three sources:

- discord: mookiezi/Discord-Dialogues (Apache-2.0). Exchanges whose index is 0 mod 100 are test only.
- sms: ucirvine/sms_spam's ham messages (real SMS, from the NUS SMS corpus). Test only, never trained on.
- soda: allenai/soda (CC BY 4.0). Its test split for tests, its train split for training.

A test sentence is a run of 3 to 14 words between punctuation, every word letters with at most one apostrophe inside,
as the gate's sentences are; its case is kept as written.

  extract.py tests     -> sets/{discord,sms,soda}.txt
  extract.py train     -> train/messages.txt (one message a line: a Discord turn or a SODA utterance)
"""
import os
import random, re, sys
from pathlib import Path
import pandas as pd

HERE = Path(__file__).resolve().parent
# The data, base models, checkpoints and venv: large, kept out of git (android/.dev/lm unless HEX_LM_WORK says).
WORK = Path(os.environ.get('HEX_LM_WORK') or HERE.parents[1] / '.dev/lm')
WORD = re.compile(r"^[A-Za-z]+(?:'[A-Za-z]+)?$")
SPLIT = re.compile(r"[.!?,;:…()\[\]\"“”*~_/\\|\n]+| - | -- |—")


def turns(chatml):
    return [t.split('\n', 1)[1] if '\n' in t else '' for t in re.split(r'<\|im_end\|>\s*', chatml)
            if t.startswith('<|im_start|>')] or []


def clean_turns(chatml):
    out = []
    for part in chatml.split('<|im_start|>')[1:]:
        body = part.split('\n', 1)[1] if '\n' in part else ''
        out.append(body.replace('<|im_end|>', '').strip())
    return [t for t in out if t]


def segments(text):
    for seg in SPLIT.split(text.replace('’', "'")):
        words = seg.split()
        if 3 <= len(words) <= 14 and all(WORD.match(w) and len(w) <= 18 for w in words):
            yield ' '.join(words)


VALID = {l.split('\t')[0].strip().lower() for l in open(WORK / 'data/en-vocab.txt', encoding='utf-8')}


def english(seg):
    """Mostly words the keyboard knows (so mostly English), and not shouted."""
    words = seg.split()
    letters = [c for c in seg if c.isalpha()]
    return (sum(w.lower().replace("'", '') in VALID or w.lower() in VALID for w in words) >= 0.75 * len(words)
            and sum(c.isupper() for c in letters) <= 0.3 * len(letters))


def pick(texts, n, seed):
    rng = random.Random(seed)
    pool, seen = [], set()
    for t in texts:
        segs = [g for g in segments(t) if english(g)]
        if segs:
            s = rng.choice(segs)   # one sentence a message, so no message dominates
            if s.lower() not in seen:
                seen.add(s.lower()); pool.append(s)
    rng.shuffle(pool)
    return pool[:n]


def discord():
    return pd.read_parquet(WORK / 'data/Discord-Dialogues/data/train.parquet', columns=['text'])['text']


def tests():
    (HERE / 'sets').mkdir(exist_ok=True)
    d = discord()
    test_turns = [t for i, row in enumerate(d) if i % 100 == 0 for t in clean_turns(row)]
    sms = pd.read_parquet(WORK / 'data/sms_spam/plain_text/train-00000-of-00001.parquet')
    soda = pd.read_parquet(WORK / 'data/soda/test.parquet', columns=['dialogue'])
    soda_turns = [u for dlg in soda['dialogue'] for u in dlg]
    for name, texts, seed in [('discord', test_turns, 1), ('sms', sms[sms.label == 0].sms.tolist(), 2), ('soda', soda_turns, 3)]:
        chosen = pick(texts, 700, seed)
        (HERE / 'sets' / f'{name}.txt').write_text('\n'.join(chosen) + '\n', encoding='utf-8')
        print(name, len(chosen), 'from', len(texts))


def train():
    (WORK / 'train').mkdir(exist_ok=True)
    d = discord()
    n = 0
    with (WORK / 'train/messages.txt').open('w', encoding='utf-8') as out:
        for i, row in enumerate(d):
            if i % 100 == 0:
                continue
            for t in clean_turns(row):
                t = ' '.join(t.split())
                if 2 <= len(t) <= 600:
                    out.write('D\t' + t + '\n'); n += 1
        soda = pd.read_parquet(WORK / 'data/soda/train.parquet', columns=['dialogue'])
        for dlg in soda['dialogue']:
            for u in dlg:
                u = ' '.join(u.split())
                if u:
                    out.write('S\t' + u + '\n'); n += 1
    print('messages', n)


{'tests': tests, 'train': train}[sys.argv[1]]()
