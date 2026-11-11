/* ECE 462/562 - replacement-policy sanity test (dcache-exact-fill patch).
 *
 * L1 D$ under test (LargeL1Config): 64 sets x 4 ways x 64 B = 16 KiB, so
 * addresses 4096 B apart map to the same set.
 *
 * Phase "fit4":   cycle over 4 same-set blocks. Fits in 4 ways, so every
 *                 policy should miss ~4 times total (cold), then always hit.
 * Phase "thrash5": cycle over 5 same-set blocks. True LRU misses on EVERY
 *                 access (5 * ITERS); random misses noticeably less. This
 *                 is the textbook pattern a smarter policy should beat.
 *
 * lru and lru-exact must print identical numbers.
 *
 * Build (inside the container, after `source /workspace/chipyard/env.sh`):
 *   riscv64-unknown-elf-gcc -std=gnu99 -O2 -march=rv64imafd -mabi=lp64d \
 *     -mcmodel=medany -specs=htif_nano.specs same_set_cycle.c \
 *     -o /workspace/output/same_set_cycle.riscv -static -T htif.ld
 */
#include <stdint.h>
#include <stdio.h>

#define SET_STRIDE 4096      /* nSets * blockBytes */
#define MAX_BLOCKS 5
#define ITERS      2000

#define EV_DCACHE_MISS ((1UL << (1 + 8)) | 2)  /* event set 2, bit 1 = 0x202 */

/* MAX_BLOCKS same-set blocks plus one spare page; 4 KiB aligned. */
static volatile uint8_t buf[(MAX_BLOCKS + 1) * SET_STRIDE] __attribute__((aligned(4096)));

static inline uint64_t rd_mcycle(void)  { uint64_t x; asm volatile("csrr %0, mcycle" : "=r"(x)); return x; }
static inline uint64_t rd_dmiss(void)   { uint64_t x; asm volatile("csrr %0, mhpmcounter3" : "=r"(x)); return x; }

static uint64_t sink;

static void run(const char *name, int nblocks)
{
    /* Offset by one 64 B block so we do not share set 0 with anything at a
     * page boundary; all nblocks lines still land in the same set. */
    volatile uint8_t *base = buf + 64;
    uint64_t acc = 0;

    uint64_t c0 = rd_mcycle(), m0 = rd_dmiss();
    for (int it = 0; it < ITERS; it++)
        for (int b = 0; b < nblocks; b++)
            acc += base[b * SET_STRIDE];
    uint64_t c1 = rd_mcycle(), m1 = rd_dmiss();

    sink += acc;
    printf("%-8s blocks=%d accesses=%d  dcache_miss=%lu  cycles=%lu\n",
           name, nblocks, ITERS * nblocks,
           (unsigned long)(m1 - m0), (unsigned long)(c1 - c0));
}

int main(void)
{
    asm volatile("csrw mhpmevent3, %0" :: "r"(EV_DCACHE_MISS));
    run("fit4", 4);
    run("thrash5", 5);
    return 0;
}
