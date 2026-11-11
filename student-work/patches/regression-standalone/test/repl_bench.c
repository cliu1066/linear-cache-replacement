/* ECE 462/562 - replacement-policy benchmark.
 *
 * L1 D$ under test (LargeL1Config): 64 sets x 4 ways x 64 B = 16 KiB.
 * Every measured access is one byte load from a distinct 64 B line, so each
 * phase's "accesses" count is exact and the miss counter (mhpmcounter3,
 * event 0x202 = D$ miss) measures the policy directly.
 *
 * Phases (the address streams are mirrored exactly by model.py):
 *   fit4     4 same-set lines, cyclic            sanity: ~0 misses after warm-up
 *   thrash5  5 same-set lines, cyclic            LRU worst case
 *   stream   64 KiB sequential, 2 passes         no reuse: every policy misses
 *   hotscan  12 KiB hot set read twice, then
 *            an 8 KiB chunk of a 64 KiB stream   temporal locality + scan pollution
 *   random   uniform over 32 KiB (2x cache)      no exploitable pattern
 *   mixed    75% to an 8 KiB hot region, 25% random over 64 KiB; the hot region
 *            moves halfway through               generalization / phase change
 *
 * Sizes are kept small because Verilator runs at roughly 1-10k cycles/s.
 *
 * Build (inside the container, after `source /workspace/chipyard/env.sh`):
 *   riscv64-unknown-elf-gcc -std=gnu99 -O2 -march=rv64imafd -mabi=lp64d \
 *     -mcmodel=medany -specs=htif_nano.specs repl_bench.c \
 *     -o /workspace/output/repl_bench.riscv -static -T htif.ld
 */
#include <stdint.h>
#include <stdio.h>

#define LINE   64
#define KiB    1024
#define SET_STRIDE (4 * KiB)   /* nSets * LINE: same set every 4 KiB */

#define EV_DCACHE_MISS ((1UL << (1 + 8)) | 2)

static volatile uint8_t same_buf[6 * SET_STRIDE] __attribute__((aligned(4096)));
static volatile uint8_t hot_buf[12 * KiB]        __attribute__((aligned(4096)));
static volatile uint8_t big_buf[64 * KiB]        __attribute__((aligned(4096)));
static volatile uint8_t hot2_buf[8 * KiB]        __attribute__((aligned(4096)));

static inline uint64_t rd_mcycle(void) { uint64_t x; asm volatile("csrr %0, mcycle" : "=r"(x)); return x; }
static inline uint64_t rd_dmiss(void)  { uint64_t x; asm volatile("csrr %0, mhpmcounter3" : "=r"(x)); return x; }

static uint64_t sink;
static uint64_t t_cyc, t_miss;

static inline void start(void) { t_cyc = rd_mcycle(); t_miss = rd_dmiss(); }
static void stop(const char *name, uint64_t accesses)
{
    uint64_t c = rd_mcycle() - t_cyc, m = rd_dmiss() - t_miss;
    /* miss rate in tenths of a percent: htif_nano printf has no floats */
    uint64_t permille = accesses ? (m * 1000 + accesses / 2) / accesses : 0;
    printf("RESULT %-8s accesses=%lu misses=%lu missrate=%lu.%lu%% cycles=%lu\n",
           name, (unsigned long)accesses, (unsigned long)m,
           (unsigned long)(permille / 10), (unsigned long)(permille % 10), (unsigned long)c);
}

static inline uint64_t lcg(uint64_t *x)
{
    *x = *x * 6364136223846793005ULL + 1442695040888963407ULL;
    return *x >> 33;
}

static void same_set(const char *name, int nblocks, int iters)
{
    volatile uint8_t *base = same_buf + LINE;   /* set 1 */
    uint64_t acc = 0;
    start();
    for (int it = 0; it < iters; it++)
        for (int b = 0; b < nblocks; b++)
            acc += base[b * SET_STRIDE];
    stop(name, (uint64_t)iters * nblocks);
    sink += acc;
}

static void stream(void)
{
    uint64_t acc = 0;
    start();
    for (int p = 0; p < 2; p++)
        for (uint32_t off = 0; off < sizeof(big_buf); off += LINE)
            acc += big_buf[off];
    stop("stream", 2 * sizeof(big_buf) / LINE);
    sink += acc;
}

static void hotscan(void)
{
    const int rounds = 16;
    const uint32_t chunk = 8 * KiB;
    uint64_t acc = 0;
    uint32_t pos = 0;
    start();
    for (int r = 0; r < rounds; r++) {
        for (int rep = 0; rep < 2; rep++)
            for (uint32_t off = 0; off < sizeof(hot_buf); off += LINE)
                acc += hot_buf[off];
        for (uint32_t off = 0; off < chunk; off += LINE)
            acc += big_buf[pos + off];
        pos = (pos + chunk) % sizeof(big_buf);
    }
    stop("hotscan", (uint64_t)rounds * (2 * sizeof(hot_buf) + chunk) / LINE);
    sink += acc;
}

static void random_phase(void)
{
    const int n = 5000;
    const uint32_t lines = (32 * KiB) / LINE;   /* 512 */
    uint64_t x = 12345, acc = 0;
    start();
    for (int i = 0; i < n; i++)
        acc += big_buf[(lcg(&x) & (lines - 1)) * LINE];
    stop("random", n);
    sink += acc;
}

static void mixed(void)
{
    const int n = 5000;
    const uint32_t hot_lines = (8 * KiB) / LINE;   /* 128 */
    const uint32_t big_lines = (64 * KiB) / LINE;  /* 1024 */
    uint64_t x = 999, acc = 0;
    start();
    for (int i = 0; i < n; i++) {
        uint64_t r = lcg(&x);
        volatile uint8_t *hot = (i < n / 2) ? hot_buf : hot2_buf;
        if (r & 3)
            acc += hot[((r >> 2) & (hot_lines - 1)) * LINE];
        else
            acc += big_buf[((r >> 2) & (big_lines - 1)) * LINE];
    }
    stop("mixed", n);
    sink += acc;
}

int main(void)
{
    asm volatile("csrw mhpmevent3, %0" :: "r"(EV_DCACHE_MISS));
    same_set("fit4", 4, 500);
    same_set("thrash5", 5, 500);
    stream();
    hotscan();
    random_phase();
    mixed();
    printf("DONE sink=%lu\n", (unsigned long)(sink & 0xff));
    return 0;
}
