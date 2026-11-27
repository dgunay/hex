# Neural autocorrect: fine-tuning and benchmarks (fork)

How the fork's fine-tuned model for Labs › Keyboard › Neural autocorrect was made and measured. Results and the
choice of checkpoint are in `android/FORK_CHANGES.md` (Neural autocorrect › Fine-tuned model).

The scripts and the test sets are here. The data, base models, checkpoints, llama.cpp and the Python venv are large
(about 16 GB), so they live in a work folder outside git: `android/.dev/lm`, or wherever `HEX_LM_WORK` points.

## Data

Everything is public; no one's typing logs are used.

| Source | Licence | Used for | Goes in the work folder at |
|---|---|---|---|
| [mookiezi/Discord-Dialogues](https://huggingface.co/datasets/mookiezi/Discord-Dialogues) | Apache-2.0 | training; test set (exchanges whose index is 0 mod 100, never trained on) | `data/Discord-Dialogues/data/train.parquet` |
| [allenai/soda](https://huggingface.co/datasets/allenai/soda) | CC BY 4.0 | training (train split); test set (test split) | `data/soda/{train,test}.parquet` |
| [ucirvine/sms_spam](https://huggingface.co/datasets/ucirvine/sms_spam) | CC BY 4.0 | test set only (ham messages) | `data/sms_spam/plain_text/train-00000-of-00001.parquet` |
| [HuggingFaceTB/smollm-corpus](https://huggingface.co/datasets/HuggingFaceTB/smollm-corpus), `fineweb-edu-dedup` | ODC-By | training (keeps tidy prose); the run used shards 00007 and 00011 | `data/smollm-corpus/fineweb-edu-dedup/*.parquet` |
| [HuggingFaceTB/SmolLM2-135M](https://huggingface.co/HuggingFaceTB/SmolLM2-135M) | Apache-2.0 | base model | `hf/SmolLM2-135M/` |

`data/en-vocab.txt` is the English gate's word list, dumped with `benchmark/typing/GateLexicon.java`; test
sentences keep only words in it.

The test sets in `sets/` are committed: `*.txt` are the sentences (`extract.py tests`), `*.tsv.gz` the same
sentences typed with the English gate's measured tap noise (`make-set.py`).

## Steps

```sh
cd android
W=.dev/lm                     # or $HEX_LM_WORK
# Python 3 with torch (ROCm or CUDA), transformers, pandas, pyarrow, numpy, in $W/ftvenv.
python benchmark/neural/extract.py train        # -> $W/train/messages.txt (Discord turns and SODA lines)
python benchmark/neural/extract.py tests        # -> benchmark/neural/sets/*.txt (already committed)
python benchmark/neural/make-set.py benchmark/neural/sets/discord.txt benchmark/neural/sets/discord.tsv.gz

# The run on the phone (checkpoints at 5, 10, 20 and 40M tokens; 5M is the one used):
cd $W && python ../../benchmark/neural/train.py --base hf/SmolLM2-135M --out ft/smol135-mix --messages 4000000 \
  --soda-share 0.25 --general-share 0.3 --lr 1e-4 --batch-tokens 6144 --milestones 5,10,20,40

# Convert each checkpoint to q8_0 GGUF and benchmark it (llama.cpp cloned into $W/llama.cpp;
# the host build of the keyboard's scorer, from host/CMakeLists.txt, into $W/host/build):
cmake -S benchmark/neural/host -B $W/host/build && cmake --build $W/host/build -j
benchmark/neural/ckpt-queue.sh smol135-mix 1.0,1.5   # appends to $W/ckpt-results.tsv
```

`model-bench.py` scores a GGUF on the English gate's held-out sentences and on the sets here, at each autocorrect
weight: word errors, sentences right, and how often a word already typed right was changed.

To put a model on the phone (debug build), see Neural autocorrect › Model choice in `FORK_CHANGES.md`.
