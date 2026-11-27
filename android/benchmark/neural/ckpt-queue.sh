#!/bin/bash
# Converts each new checkpoint of RUN (ft/RUN/step-*M, final) to 8-bit GGUF and benchmarks it at WEIGHTS on 8 threads,
# with Gradle on its own home so it never waits on the comparison's. Stops after the final checkpoint.
#   ckpt-queue.sh RUN WEIGHTS
set -u
RUN=$1; WEIGHTS=${2:-1.5}
HERE=$(cd "$(dirname "$0")" && pwd)
LM=${HEX_LM_WORK:-$HERE/../../.dev/lm}
cd $LM
S="gate,discord:benchmark/neural/sets/discord.tsv.gz,sms:benchmark/neural/sets/sms.tsv.gz,soda:benchmark/neural/sets/soda.tsv.gz"
while true; do
  for d in $(ls -d ft/$RUN/step-*M ft/$RUN/final 2>/dev/null | sort -V); do
    tag=$(basename $d); g=models/$RUN-$tag-q8_0.gguf
    [ -f $d/model.safetensors ] || continue
    [ -f $g.done ] && continue
    sleep 20  # let the save finish
    ( . ftvenv/bin/activate && nice python llama.cpp/convert_hf_to_gguf.py $d --outtype q8_0 --outfile $g > $g.log 2>&1 ) || { echo "convert failed $d"; continue; }
    ( cd $HERE/../.. && HEXDROID_NO_SYNC=1 ./.dev/hexdroid shell -c "export HEX_BENCH_CPUS=10 GRADLE_USER_HOME=\$HOME/.gradle-b; python3 benchmark/neural/model-bench.py --sets $S --weights $WEIGHTS --model $RUN-$tag=$LM/$g --out $LM/ckpt-results.tsv" ) >> $LM/ckpt.log 2>&1
    touch $g.done
    [ $tag = final ] && exit 0
  done
  sleep 60
done
