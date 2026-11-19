# Linear Regression Cache Replacement (ECE 562)

Student work for a linear-regression-based L1 D$ replacement policy on Rocket (Chipyard), compared against LRU and Random. Covers the latest `main` (commits `80fa00b` "integrate exact features from datasci training" and `dac974e` cleanup, plus the earlier `cc511e9` LRU/Random configs).

## What's new

| Item | Location | Purpose |
|---|---|---|
| LRU / Random configs | `cache-replacement/ReplacementConfigs.scala` | Baselines: `LruL1Config`, `RandomL1Config` |
| Exact-fill patch | `patches/dcache-exact-fill/` | Makes the D$ report true line insertions to the policy |
| Linreg policy | `patches/linreg-replacement/` | The regression-based replacement policy and its configs |
| `.gitignore` | repo root | Ignores `.metals/`, `.scala-build/`, `.bloop/`, `.bsp/`, `.vscode/` |

## Configurations

All configs sit on top of `LargeL1Config` (16 KiB, 4-way L1 I$ and D$; 64 sets x 4 ways x 64 B).

| Config | Policy string | Notes |
|---|---|---|
| `LruL1Config` | `lru` | Stock true LRU |
| `RandomL1Config` | `random` | Stock random |
| `LruExactL1Config` | `lru-exact` | True LRU through the fill-aware path. Sanity check: cycles and misses must match `LruL1Config` exactly |
| `LinReg5Int8Config` | `linreg5-int8` | 5 features, int8 weights |
| `LinReg5Fp32Config` | `linreg5-fp32` | 5 features, fp32 weights |
| `LinReg2Int8Config` | `linreg2-int8` | 2 features, int8 weights |
| `LinReg2Fp32Config` | `linreg2-fp32` | 2 features, fp32 weights |

Policy strings have the form `<model>-int8` or `<model>-fp32`. Models are defined in `LinRegWeights.scala`.

## Implementation

### Exact-fill patch (`dcache-exact-fill`)
- `rocket-chip.patch` edits `DCache.scala` and adds `FillAwareReplacement.scala`.
- Stock Rocket only tells the replacer about hits. The patch remembers the (set, way) of each fired cached Acquire, and the first hit on that (set, way) afterward is flagged `isInsert`. Permission upgrades and nacked or retried misses are not counted as insertions.
- Policies that mix in `FillAwareReplacement` get `get_next_state_touch(state, way, isInsert, isWrite)`. Other policies use the normal path.
- Debug trace, off by default: run the simulator with `+dcache_fill_trace=1` to print refill/insert events.

### Linreg policy (`linreg-replacement`)
Score per way is `sum(w_i * x_i)`. Empty ways are filled first (lowest index). Otherwise the way with the **lowest** score is evicted, and ties go to the lower way. Intercept and un-standardizing constants are dropped because they are identical across ways.

| Feature | Range | Meaning |
|---|---|---|
| `frequency` | 0-15 | Hits since the line was loaded |
| `age` | 0-255 | Set accesses since the line was loaded |
| `lru_rank` | 0..ways-1 | 0 = most recently used |
| `last_reuse` | 0-255 | Set accesses between the line's last two touches (255 = none yet) |
| `set_pressure` | 0-15 | Misses in the current 256-access window (tumbling window) |

Models (weights are the Lasso-trained values, used as-is):

| Model | Features | Weights |
|---|---|---|
| `linreg5` | frequency, age, lru_rank, last_reuse, set_pressure | 0.289275, -0.019032, -0.329128, 0.022885, 0.064638 |
| `linreg2` | frequency, lru_rank | 0.187185, -0.239058 |

Arithmetic:
- **int8**: weights scaled so the largest |w| is 127 and rounded (`linreg5`: 112, -7, -127, 9, 25; `linreg2`: 99, -127), then an exact integer multiply-add.
- **fp32**: IEEE single precision. Each `w_i * x_i` is a constant lookup table built at elaboration, summed with HardFloat adders and compared with HardFloat comparators.

Notes:
- `set_pressure` is the same for every way in a set, so it never changes the victim. It is kept only to match the trained model.
- Only per-set use in the D$ is supported, and ways must be a power of two greater than 1.
- Elaboration prints the model, weights and state bits per set. It warns if an int8 weight rounds to 0.

## How to run

All commands run **inside the course container**. `/workspace/chipyard` is reset every time the container exits, so redo steps 2-3 each session.

1. Get the latest `main` on the host and start the container:
   ```bash
   git pull
   docker compose run --rm chipyard
   ```
   Startup links the `.scala` files under `student-work/` into Chipyard (it skips `patches/`, and skips any file whose name already exists in Chipyard).

2. Apply the patches (safe to re-run; it also applies `dcache-exact-fill`):
   ```bash
   bash /workspace/student-work/patches/linreg-replacement/apply.sh
   ```
   To apply only the exact-fill patch and `LruExactL1Config`, run `patches/dcache-exact-fill/apply.sh` instead. Both scripts delete the cached `chipyard.jar`, so the next build recompiles.

3. Build the benchmark once:
   ```bash
   cd /workspace/chipyard && source env.sh
   cd /workspace/course/benchmarks && make
   ```

4. Build a simulator, then run it (replace the config name):
   ```bash
   cd /workspace/chipyard/sims/verilator
   make CONFIG=LinReg5Int8Config
   ./simulator-chipyard.harness-LinReg5Int8Config /workspace/output/isp_bench.riscv
   ```
   The `quiz-run <Config>` shortcut runs the same simulator on the ISP benchmark.
   Confirm a config is visible with `make find-configs`.

5. Verify the patch before trusting any results:
   ```bash
   make CONFIG=LruL1Config
   make CONFIG=LruExactL1Config
   ```
   Run `isp_bench` on both. Cycle and miss counts must be identical. Any difference means the patch broke something.

6. Compare policies: run `isp_bench` on `LruL1Config`, `RandomL1Config`, then each `LinReg*` config, and compare cycles and D$ misses.

## Changing weights or adding a model
Edit `patches/linreg-replacement/LinRegWeights.scala` (add a `LinRegModel` and a case in `model(name)`), add a config line in `LinRegConfigs.scala`, then re-run `apply.sh` and rebuild. Each new config is a full hardware rebuild, so batch changes where you can.

## Troubleshooting
- **Config not found**: rerun `apply.sh` (the symlink is lost on container exit), then `make find-configs`.
- **Edits not picked up**: the cached jar was not removed. `rm -f /workspace/chipyard/.classpath_cache/chipyard.jar` and rebuild.
- **`SKIPPED ... already exists in Chipyard`** at startup: two files share a name, so rename yours.
- **`unknown LinReg model`**: the policy name must be `linreg5` or `linreg2` plus `-int8` or `-fp32`.
