# regression-standalone

A five-feature linear-score L1 D$ replacement policy (`RegressionReplacement`)
for Rocket, with **int8 or fp32 weights**, LRU / Random comparison configs, a
6-phase benchmark, and a Python reference model that predicts the hardware's
miss counts (bit-exact, including fp32 rounding).

Depends on `../dcache-exact-fill` (applied automatically by `apply.sh`).

| File | What |
|---|---|
| `RegressionReplacement.scala` | the policy (copied into rocket-chip `util/`) |
| `RegressionConfigs.scala` | `Exp*Config` experiment configs (see the file) |
| `apply.sh` | applies exact-fill, copies the policy, registers `"regression*"` in `fromString`, links configs |
| `test/repl_bench.c` | fit4, thrash5, stream, hotscan, random, mixed phases; prints `RESULT` lines |
| `test/model.py` | same address streams through LRU / Random / Regression models; `--quantize` |
| `results/` | hardware `RESULT` lines per config, and `model.txt` predictions |

## Features and weights

Five weights, one per feature, in this order:

| # | Feature | Bits | Meaning |
|---|---|---|---|
| 0 | `rank`  | 2 | recency rank in the set, 0 = most recently used |
| 1 | `reuse` | 2 | saturating reuse count since insertion; a touch on a line that is already MRU does not count |
| 2 | `store` | 1 | written since insertion |
| 3 | `idle`  | 4 | accesses to this set since the line was last touched, saturating at 15 |
| 4 | `life`  | 4 | accesses to this set since the line was inserted, saturating at 15 |

That's 13 bits per way, 52 per set (true LRU: 6). `score = sum(w_i * x_i)`.
The **highest** score is evicted, and ties go to the lower way. On reset, empty
ways decode as rank = way index, idle = life = 15.

Policy strings (use in a config via `WithDCachePolicyExact("...")`):

| String | Hardware |
|---|---|
| `regression` | int8 `2,-1,0,0,0` |
| `regression-int8:w0,w1,w2,w3,w4` | integers in [-128, 127]; exact integer multiply-add |
| `regression-fp32:w0,w1,w2,w3,w4` | IEEE fp32: per-feature product lookup tables computed in fp32 at build time, a left-to-right chain of 4 HardFloat fp32 adders per way (round to nearest even), 3 HardFloat fp32 comparators |

Weights are build-time constants, so a new weight set needs `make` (about
3 min). fp32 text is parsed as a double and rounded to fp32, the same way in
Scala and `model.py`. Bad strings fail at build time with a clear message:
wrong count, int8 out of range, or a non-finite or overflowing fp32 weight.

Convert fp32 weights to int8: `python3 test/model.py --quantize=-1.33,-1.35,-1.17,1.62,-0.01`
(scales so that max |w| = 127, then rounds).

## Results (RTL simulation, same binary on every config)

| Phase | LRU | Random | int8 `2,-1,0,0,0` | fp32 `2,-1,0,0,0` | fp32 tuned | int8 tuned |
|---|---|---|---|---|---|---|
| fit4 | 4 | 5 | 4 | 4 | 5 | 5 |
| thrash5 | 2500 | 974 | 2000 | 2000 | 2500 | 2500 |
| stream | 2049 | 2038 | 2049 | 2049 | 2049 | 2049 |
| hotscan | 5121 | 4409 | 5121 | 5121 | **2241** | **2241** |
| random | 2560 | 2658 | 2564 | 2564 | 2565 | 2565 |
| mixed | 1564 | 1914 | 1541 | 1541 | **1509** | **1509** |

"tuned" = fp32 `-1.33,-1.35,-1.17,1.62,-0.01` and its int8 quantization
`-104,-106,-92,127,-1`. These came from a random search in `model.py` on this
same benchmark: in-sample, **not trained**.

- int8 and fp32 with the same weights are cycle-identical in RTL.
- The tuned fp32 set and its int8 quantization are also cycle-identical.
- The tuned set cuts `hotscan` misses by 56% and `mixed` by 3.5%, and is
  within 0.2% of LRU elsewhere. Its large positive `idle` weight (evict what
  hasn't been touched in a while) does most of the work.
- **Model vs RTL:** within 4 misses everywhere except tuned `thrash5` (model
  999, RTL 2500). The `printf` between phases evicts all four `fit4` lines
  from set 1 in hardware. The model doesn't replay `printf`, so it kept them,
  with saturated reuse counts, and the search exploited that. Evicting those
  lines in the model reproduces 2500 exactly. Lesson: tune on data whose
  starting state matches the hardware.
- `results/ExpRegressionAggrConfig.txt` is from the earlier 3-feature hardware
  (string `regression:2,-4,-1`). It is equivalent to `regression-int8:2,-4,-1,0,0`
  but was not re-run on the 5-feature build.

## Run

```bash
docker compose run --rm chipyard
bash /workspace/student-work/patches/regression-standalone/apply.sh
source /workspace/chipyard/env.sh

cd /workspace/student-work/patches/regression-standalone/test
riscv64-unknown-elf-gcc -std=gnu99 -O2 -march=rv64imafd -mabi=lp64d -mcmodel=medany \
  -specs=htif_nano.specs repl_bench.c -o /workspace/output/repl_bench.riscv -static -T htif.ld
python3 model.py --policy=regression-fp32:-1.33,-1.35,-1.17,1.62,-0.01 \
                 /workspace/output/repl_bench.riscv                # predicted misses

cd /workspace/chipyard/sims/verilator
CFGS="ExpLruConfig ExpRandomConfig ExpRegressionConfig ExpRegFp32Config ExpRegInt8Config"
for C in $CFGS; do make -j8 CONFIG=$C; done
for C in $CFGS; do
  ./simulator-chipyard.harness-$C +permissive +loadmem=/workspace/output/repl_bench.riscv \
      +permissive-off /workspace/output/repl_bench.riscv > run-$C.log 2>&1 &
done
wait; grep RESULT run-*.log
```

**Always pass `+loadmem=<elf>`.** Without it, the binary is loaded over the
simulated serial link before the program can start, which takes 30+ minutes;
with it, the benchmark takes about 20 minutes. The simulator's output is
buffered until it exits, so the log stays empty while it runs. The build log
prints the parsed weights:
`[RegressionReplacement] 4 ways, weights fp32 -1.33,... (order: rank,reuse,store,idle,life)`.

To try new weights: preview with `model.py --policy=...` (note the `=`; it's
needed when the string starts with a minus sign), then add a line to
`RegressionConfigs.scala`:

```scala
class ExpRegMineConfig extends Config(new WithDCachePolicyExact("regression-fp32:0.5,-1,0,1.25,0") ++ new LargeL1Config)
```
