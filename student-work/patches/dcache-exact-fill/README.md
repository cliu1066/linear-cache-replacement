# dcache-exact-fill

Gives a `perSet` replacement policy **exact** insertion information. This
replaces the "touch on the current victim way = insertion" approximation.

Layout follows the course's `course/patches/clamp8/` convention: a
`rocket-chip.patch`, plus a config file that lives here because it only
compiles once the patch is applied.

| File | What |
|---|---|
| `rocket-chip.patch` | `rocket/DCache.scala` hook + new `util/FillAwareReplacement.scala` |
| `apply.sh` | applies the patch, registers `"lru-exact"`, links the configs, deletes the cached jar (idempotent) |
| `ExactFillConfigs.scala` | `WithDCachePolicyExact(policy)`, `LruExactL1Config` |
| `test/same_set_cycle.c` | 4-block (fits) and 5-block (thrash) same-set test |

## What the hardware change does

Stock DCache calls `get_next_state(state, hitWay)` on hits only. If the policy
mixes in `FillAwareReplacement`, DCache instead calls

```scala
get_next_state_touch(state, touch_way, isInsert: Bool, isWrite: Bool)
```

- When a cached Acquire for a real miss **fires** on TileLink, DCache records
  `(set, victim way)`. Permission upgrades are skipped, and so are misses
  that are nacked and retried (e.g. a dirty victim being written back first).
- The next hit on that `(set, way)` is the replayed miss. It is passed with
  `isInsert = true` and clears the record.
- `isWrite` = `s2_write` (store/AMO).
- Victim selection (`get_replace_way`) is unchanged. Policies that don't mix
  in the trait (lru, random, plru, rr, current linreg) elaborate exactly as
  before.
- Debug: the trace prints only with both `+dcache_fill_trace=1` and
  `+verbose` (Rocket gates all printfs on `+verbose`), and unknown plusargs
  must sit inside `+permissive ... +permissive-off`:

  ```bash
  ./simulator-chipyard.harness-<Config> +permissive +verbose +dcache_fill_trace=1 \
      +loadmem=prog.riscv +permissive-off prog.riscv 2>&1 | grep dcache-fill
  ```

  Each `acquire set=S way=W` should be followed by `insert set=S way=W`.
  Verified on `ExpRegressionConfig`: 2236 acquires, 2236 inserts.

## Each session

```bash
docker compose run --rm chipyard
bash /workspace/student-work/patches/apply-rocket-chip.sh          # linreg patch (if used)
bash /workspace/student-work/patches/dcache-exact-fill/apply.sh    # this patch (run second)
```

## Using it from LinRegReplacement

1. `class LinRegReplacement(...) extends ReplacementPolicy with FillAwareReplacement`
   (add an `exactFill: Boolean = false` constructor parameter).
2. Implement `get_next_state_touch`. Its body is the existing update with the
   heuristic `insert = (touch_way === get_replace_way(state))` replaced by
   `isInsert` when `exactFill` is true. Keep `get_next_state` as is (heuristic)
   for the non-exact variants.
3. Register `"linreg-int-exact"` / `"linreg-shift-exact"` in `fromString`
   (`exactFill = true`) and uncomment the two configs in `ExactFillConfigs.scala`.
4. In `tools/linreg_ref.py`, the exact variant's insertion rule is simply
   "this access missed". Bit-exact hardware/model agreement on miss counts is
   the end-to-end check.
