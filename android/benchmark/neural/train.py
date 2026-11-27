#!/usr/bin/env python3
"""Fine-tune a small causal LM on messages, each one a text of its own starting at the token the keyboard's scorer
starts its context with (BOS, else end-of-text), as the keyboard reads a field: the text before a word.

  train.py --base hf/SmolLM2-135M --out ft/smol135-chat --messages 4000000 --lr 1e-4
Checkpoints at token milestones go to OUT/step-<tokens>M (Hugging Face format).
"""
import argparse, math, os, random, time
from pathlib import Path
import numpy as np
import torch
from transformers import AutoModelForCausalLM, AutoTokenizer

HERE = Path(__file__).resolve().parent
# The data, base models, checkpoints and venv: large, kept out of git (android/.dev/lm unless HEX_LM_WORK says).
WORK = Path(os.environ.get('HEX_LM_WORK') or HERE.parents[1] / '.dev/lm')


def general(n, rng):
    """[n] runs of 1-3 sentences from FineWeb-Edu paragraphs, at most 400 characters."""
    import glob, re
    import pandas as pd
    out = []
    import pyarrow.parquet as pq
    def docs():
        # A batch at a time: a shard is 3.7 GB of text.
        for f in sorted(glob.glob(str(WORK / 'data/smollm-corpus/fineweb-edu-dedup/*.parquet'))):
            for batch in pq.ParquetFile(f).iter_batches(batch_size=2000, columns=['text']):
                yield from batch.column(0).to_pylist()
    for doc in docs():
        if True:
            for para in doc.split('\n'):
                sents = re.split(r'(?<=[.!?])\s+', para.strip())
                if len(sents) < 1 or not sents[0]:
                    continue
                k = rng.randint(1, 3); i = rng.randrange(max(1, len(sents) - k + 1))
                chunk = ' '.join(sents[i:i + k])
                if 20 <= len(chunk) <= 400:
                    out.append(chunk)
                    break
            if len(out) >= n:
                return out
    return out


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--base', required=True); p.add_argument('--out', required=True)
    p.add_argument('--messages', type=int, default=4_000_000)
    p.add_argument('--soda-share', type=float, default=0.35)
    p.add_argument('--general-share', type=float, default=0.0,
                   help="SmolLM2's own pretraining text (FineWeb-Edu), as runs of 1-3 sentences, so tidy writing isn't forgotten")
    p.add_argument('--lr', type=float, default=1e-4)
    p.add_argument('--batch-tokens', type=int, default=16384)
    p.add_argument('--max-len', type=int, default=128)
    p.add_argument('--milestones', default='5,10,20,40,60,80,100')
    p.add_argument('--seed', type=int, default=1)
    a = p.parse_args()
    out = Path(a.out); out.mkdir(parents=True, exist_ok=True)
    rng = random.Random(a.seed)
    tok = AutoTokenizer.from_pretrained(a.base)
    start = tok.bos_token_id if getattr(tok, 'add_bos_token', False) and tok.bos_token_id is not None else tok.eos_token_id
    if 'gemma' in a.base.lower():
        start = tok.bos_token_id
    eos = tok.eos_token_id
    print('start token', start, 'eos', eos, flush=True)

    cache = out / 'tokens.npz'
    if cache.exists():
        z = np.load(cache, allow_pickle=True); seqs, val = list(z['seqs']), list(z['val'])
    else:
        # Two passes over the 25M messages, keeping only the ones drawn: never all of them in memory.
        counts = {'D': 0, 'S': 0}
        with open(WORK / 'train/messages.txt', encoding='utf-8') as f:
            for line in f:
                counts[line[0]] += 1
        ns = int(a.messages * a.soda_share); ng = int(a.messages * a.general_share); nd = a.messages - ns - ng
        keep = {'D': set(rng.sample(range(counts['D']), min(nd, counts['D']))),
                'S': set(rng.sample(range(counts['S']), min(ns, counts['S'])))}
        d, s, seen = [], [], {'D': 0, 'S': 0}
        with open(WORK / 'train/messages.txt', encoding='utf-8') as f:
            for line in f:
                k = line[0]
                if seen[k] in keep[k]:
                    (d if k == 'D' else s).append(line[2:].rstrip('\n'))
                seen[k] += 1
        del keep
        rng.shuffle(d); rng.shuffle(s)
        g = general(ng, rng) if ng else []
        texts = d[:nd] + s[:ns] + g
        del d, s, g
        rng.shuffle(texts)
        val_texts, texts = texts[:3000], texts[3000:]
        def encode(batch):
            ids = tok(batch, add_special_tokens=False)['input_ids']
            return [np.array([start] + x[:a.max_len - 2] + [eos], dtype=np.int32) for x in ids]
        seqs = []
        for i in range(0, len(texts), 20000):
            seqs += encode(texts[i:i + 20000])
        val = encode(val_texts)
        np.savez(cache, seqs=np.array(seqs, dtype=object), val=np.array(val, dtype=object))
    total = sum(len(x) for x in seqs)
    print(f'{len(seqs)} messages, {total / 1e6:.1f}M tokens', flush=True)

    def batches(data, shuffle=True):
        idx = list(range(len(data)))
        if shuffle: rng.shuffle(idx)
        out_b = []
        for g in range(0, len(idx), 50000):
            group = sorted(idx[g:g + 50000], key=lambda i: len(data[i]))
            cur, longest = [], 0
            for i in group:
                l = len(data[i])
                if cur and max(longest, l) * (len(cur) + 1) > a.batch_tokens:
                    out_b.append(cur); cur, longest = [], 0
                cur.append(i); longest = max(longest, l)
            if cur: out_b.append(cur)
        if shuffle: rng.shuffle(out_b)
        return out_b

    def tensors(data, b):
        L = max(len(data[i]) for i in b)
        x = torch.full((len(b), L), eos, dtype=torch.long); m = torch.zeros((len(b), L), dtype=torch.long)
        for r, i in enumerate(b):
            x[r, :len(data[i])] = torch.from_numpy(data[i].astype(np.int64)); m[r, :len(data[i])] = 1
        y = x.clone(); y[m == 0] = -100
        return x.cuda(), m.cuda(), y.cuda()

    model = AutoModelForCausalLM.from_pretrained(a.base, torch_dtype=torch.float32).cuda()
    model.config.use_cache = False
    opt = torch.optim.AdamW(model.parameters(), lr=a.lr, betas=(0.9, 0.95), weight_decay=0.0, fused=True)
    plan = batches(seqs)
    steps = len(plan); warm = min(300, steps // 20)
    sched = torch.optim.lr_scheduler.LambdaLR(opt, lambda s: min(1, (s + 1) / warm) * (0.1 + 0.9 * 0.5 * (1 + math.cos(math.pi * min(1, s / steps)))))
    milestones = [int(m) * 1_000_000 for m in a.milestones.split(',')]

    @torch.no_grad()
    def evaluate():
        model.eval(); loss = n = 0
        for b in batches(val, False):
            x, m, y = tensors(val, b)
            with torch.autocast('cuda', dtype=torch.bfloat16):
                o = model(input_ids=x, attention_mask=m, labels=y)
            k = int((y[:, 1:] != -100).sum()); loss += o.loss.item() * k; n += k
        model.train(); return loss / n

    print('val loss before', round(evaluate(), 4), flush=True)
    seen, t0, run = 0, time.time(), 0.0
    for step, b in enumerate(plan):
        x, m, y = tensors(seqs, b)
        with torch.autocast('cuda', dtype=torch.bfloat16):
            loss = model(input_ids=x, attention_mask=m, labels=y).loss
        loss.backward()
        torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
        opt.step(); sched.step(); opt.zero_grad(set_to_none=True)
        seen += int(m.sum()); run = 0.98 * run + 0.02 * loss.item() if step else loss.item()
        if step % 200 == 0:
            print(f'step {step}/{steps} tokens {seen / 1e6:.1f}M loss {run:.4f} lr {sched.get_last_lr()[0]:.2e} '
                  f'{seen / (time.time() - t0):.0f} tok/s', flush=True)
        if milestones and seen >= milestones[0] or step == steps - 1:
            tag = f'step-{seen // 1_000_000}M' if step < steps - 1 else 'final'
            while milestones and seen >= milestones[0]: milestones.pop(0)
            print('val loss', tag, round(evaluate(), 4), flush=True)
            model.save_pretrained(out / tag, safe_serialization=True); tok.save_pretrained(out / tag)


main()
