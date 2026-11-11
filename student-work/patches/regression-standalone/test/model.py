#!/usr/bin/env python3
"""Reference model for repl_bench.c on the 64-set, 4-way, 64 B Rocket L1 D$.

Replays the exact measured address stream of each benchmark phase through
software models of LRU, Random and the RegressionReplacement policy (same
features, update rules, weights and tie-breaking as the Chisel). It mirrors
the DCache timing: on a miss the victim is chosen from the current state, the
state is untouched by the refill, and the replayed access is the touch (an
insertion touch for the regression policy).

The hardware should match LRU and Regression to within a few misses per
phase (code outside the measured loops, e.g. printf, also uses the D$).
By default the model first replays the boot-time .bss memset, because that
history matters: it is what exposed the raw-hit-count bug in RTL.
Random uses Python's RNG instead of Rocket's LFSR, so it is indicative only.

Usage:  python3 model.py /workspace/output/repl_bench.riscv
        python3 model.py --policy regression-int8:2,-1,0,0,0 \\
                         --policy regression-fp32:1.5,-0.75,0,0.1,0 /workspace/output/repl_bench.riscv
        python3 model.py --quantize 1.5,-0.75,0,0.1,0        # -> int8 weights
"""
import argparse
import random
import subprocess

import numpy as np

SETS, WAYS, LINE = 64, 4, 64
KiB = 1024


# ----------------------------------------------------------------- policies
class LRU:
    def __init__(self):
        # TrueLRU's all-zero reset state makes way 3 the first victim.
        self.age = [[w for w in range(WAYS)] for _ in range(SETS)]

    def victim(self, s):
        a = self.age[s]
        return a.index(max(a))

    def touch(self, s, w, insert, write):
        a = self.age[s]
        t = a[w]
        for i in range(WAYS):
            if a[i] < t:
                a[i] += 1
        a[w] = 0


class Random:
    def __init__(self, seed=1):
        self.rng = random.Random(seed)

    def victim(self, s):
        return self.rng.randrange(WAYS)

    def touch(self, s, w, insert, write):
        pass


FEATURES = ("rank", "reuse", "store", "idle", "life")
FEAT_MAX = (WAYS - 1, 3, 1, 15, 15)          # saturation limits (2, 2, 1, 4, 4 bits)
DEFAULT_POLICY = "regression"                # = regression-int8:2,-1,0,0,0


def parse_policy(text):
    """Policy string -> ('int8'|'fp32', weights). Mirrors RegressionReplacement.fromString."""
    s = text.lower()
    if s == "regression":
        return "int8", (2, -1, 0, 0, 0)
    for kind in ("int8", "fp32"):
        prefix = f"regression-{kind}:"
        if s.startswith(prefix):
            vals = [v.strip() for v in s[len(prefix):].split(",") if v.strip()]
            if len(vals) != 5:
                raise ValueError(f"{text}: need 5 weights (rank,reuse,store,idle,life), got {len(vals)}")
            if kind == "int8":
                w = tuple(int(v) for v in vals)
                if any(not -128 <= x <= 127 for x in w):
                    raise ValueError(f"{text}: int8 weights must be in [-128, 127]")
            else:
                w = tuple(np.float32(float(v)) for v in vals)  # text -> double -> fp32, like Scala
                if not all(np.isfinite(w)):
                    raise ValueError(f"{text}: fp32 weights must be finite")
            return kind, w
    raise ValueError(f"unknown policy {text!r}")


class Regression:
    """Bit-exact model of RegressionReplacement (5 features, int8 or fp32 weights)."""

    def __init__(self, policy=DEFAULT_POLICY):
        self.kind, self.w = parse_policy(policy)
        # Zero reset state decodes as: rank = way index, reuse 0, store 0, idle 15, life 15.
        self.f = [[[w, 0, 0, 15, 15] for w in range(WAYS)] for _ in range(SETS)]

    def score(self, feats):
        if self.kind == "int8":
            return sum(w * x for w, x in zip(self.w, feats))
        # fp32: each product rounded to fp32 (the LUT), then ((((p0+p1)+p2)+p3)+p4) in fp32.
        prods = [np.float32(w) * np.float32(x) for w, x in zip(self.w, feats)]
        acc = prods[0]
        for p in prods[1:]:
            acc = np.float32(acc + p)
        return acc

    def victim(self, s):
        scores = [self.score(f) for f in self.f[s]]
        best = 0
        for i in range(1, WAYS):
            if scores[i] > scores[best]:  # strictly greater: ties -> lower index
                best = i
        return best

    def touch(self, s, w, insert, write):
        ways = self.f[s]
        t_rank = ways[w][0]
        for i, f in enumerate(ways):
            if i == w:
                was_mru = f[0] == 0
                f[0] = 0
                if insert:
                    f[1], f[2], f[4] = 0, int(write), 0
                else:
                    if not was_mru:                       # reuse only
                        f[1] = min(f[1] + 1, FEAT_MAX[1])
                    f[2] = f[2] or int(write)
                    f[4] = min(f[4] + 1, FEAT_MAX[4])
                f[3] = 0
            else:
                if f[0] < t_rank:
                    f[0] += 1
                f[3] = min(f[3] + 1, FEAT_MAX[3])
                f[4] = min(f[4] + 1, FEAT_MAX[4])


class Cache:
    def __init__(self, policy):
        self.p = policy
        self.tags = [[None] * WAYS for _ in range(SETS)]

    def access(self, addr, write=False):
        s, tag = (addr // LINE) % SETS, addr // (LINE * SETS)
        ways = self.tags[s]
        if tag in ways:
            self.p.touch(s, ways.index(tag), False, write)
            return False
        v = self.p.victim(s)
        ways[v] = tag
        self.p.touch(s, v, True, write)
        return True


# --------------------------------------------------------- address streams
def lcg(x):
    x = (x * 6364136223846793005 + 1442695040888963407) & (2**64 - 1)
    return x, x >> 33


def phases(sym):
    same, hot, big, hot2 = sym["same_buf"], sym["hot_buf"], sym["big_buf"], sym["hot2_buf"]

    def same_set(n, iters):
        return [same + LINE + b * 4 * KiB for _ in range(iters) for b in range(n)]

    def stream():
        return [big + off for _ in range(2) for off in range(0, 64 * KiB, LINE)]

    def hotscan():
        out, pos = [], 0
        for _ in range(16):
            for _ in range(2):
                out += [hot + off for off in range(0, 12 * KiB, LINE)]
            out += [big + pos + off for off in range(0, 8 * KiB, LINE)]
            pos = (pos + 8 * KiB) % (64 * KiB)
        return out

    def rand():
        out, x = [], 12345
        for _ in range(5000):
            x, r = lcg(x)
            out.append(big + (r & 511) * LINE)
        return out

    def mixed():
        out, x, n = [], 999, 5000
        for i in range(n):
            x, r = lcg(x)
            h = hot if i < n // 2 else hot2
            if r & 3:
                out.append(h + ((r >> 2) & 127) * LINE)
            else:
                out.append(big + ((r >> 2) & 1023) * LINE)
        return out

    return [("fit4", same_set(4, 500)), ("thrash5", same_set(5, 500)),
            ("stream", stream()), ("hotscan", hotscan()),
            ("random", rand()), ("mixed", mixed())]


def symbols(elf):
    out = subprocess.run(["riscv64-unknown-elf-nm", elf], capture_output=True, text=True, check=True).stdout
    return {f[2]: int(f[0], 16) for f in (l.split() for l in out.splitlines()) if len(f) == 3}


def quantize_int8(text):
    """fp32 weights -> int8 with the same ordering intent: scale so max |w| = 127, round."""
    w = [float(v) for v in text.split(",")]
    m = max(abs(x) for x in w)
    if m == 0:
        return "0,0,0,0,0"
    return ",".join(str(int(round(x * 127 / m))) for x in w)


def main():
    ap = argparse.ArgumentParser(
        description="Predict D$ misses for repl_bench.c. Policies use the same strings as the "
                    "Chipyard configs, e.g. regression-int8:2,-1,0,0,0 or regression-fp32:1.5,-0.75,0,0.1,0. "
                    "Weight order: rank,reuse,store,idle,life.")
    ap.add_argument("elf", nargs="?")
    ap.add_argument("--policy", action="append",
                    help="regression policy string (repeatable); default: regression")
    ap.add_argument("--quantize", metavar="W0,..,W4",
                    help="print the int8 weights for these fp32 weights and exit")
    ap.add_argument("--no-prelude", action="store_true",
                    help="start from an empty cache instead of after the boot-time bss memset")
    args = ap.parse_args()

    if args.quantize:
        print(quantize_int8(args.quantize))
        return
    if not args.elf:
        ap.error("elf is required")

    sym = symbols(args.elf)
    ph = phases(sym)
    caches = {"lru": Cache(LRU()), "random": Cache(Random())}
    for p in args.policy or [DEFAULT_POLICY]:
        caches[p] = Cache(Regression(p))
    if not args.no_prelude:
        # crt zeroes .bss with 8-byte stores before main(): 8 touches per line.
        for line in range(sym["__bss_start"] // LINE * LINE, sym["_end"], LINE):
            for c in caches.values():
                for _ in range(8):
                    c.access(line, write=True)
    names = list(caches)
    print("columns: " + "  ".join(f"[{i}] {n}" for i, n in enumerate(names)))
    print(f"{'phase':<9}{'accesses':>9}" + "".join(f"{'[' + str(i) + ']':>9}" for i in range(len(names))))
    for name, addrs in ph:
        row = [sum(c.access(a) for a in addrs) for c in caches.values()]
        print(f"{name:<9}{len(addrs):>9}" + "".join(f"{m:>9}" for m in row))


if __name__ == "__main__":
    main()
