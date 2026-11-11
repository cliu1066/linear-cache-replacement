// ECE 462/562 - stand-alone regression replacement experiment configs.
//
// Requires student-work/patches/regression-standalone/apply.sh (which also
// applies dcache-exact-fill). All configs differ only in the L1 D$ policy
// string; everything else is LargeL1Config (64 sets x 4 ways x 64 B = 16 KiB).
//
// Policy strings (weight order: rank, reuse, store, idle, life):
//   "regression"                        default = regression-int8:2,-1,0,0,0
//   "regression-int8:w0,w1,w2,w3,w4"    integers in [-128, 127]
//   "regression-fp32:w0,w1,w2,w3,w4"    IEEE fp32 weights, fp32 arithmetic in hardware
// Preview any of them first: python3 test/model.py --policy=<string> <elf>
//
// Build from chipyard/sims/verilator:   make -j8 CONFIG=<name>
//
package chipyard

import org.chipsalliance.cde.config._

// Baselines
class ExpLruConfig    extends Config(new WithDCachePolicyExact("lru")    ++ new LargeL1Config)
class ExpRandomConfig extends Config(new WithDCachePolicyExact("random") ++ new LargeL1Config)

// int8, hand-picked: the 3-feature (2,-1,0) policy with idle/life unused.
class ExpRegressionConfig extends Config(new WithDCachePolicyExact("regression") ++ new LargeL1Config)

// int8, strong reuse protection: big wins on cyclic/scan, losses when the hot set changes.
class ExpRegressionAggrConfig extends Config(new WithDCachePolicyExact("regression-int8:2,-4,-1,0,0") ++ new LargeL1Config)

// fp32 with the same weights as ExpRegressionConfig: must give identical misses.
class ExpRegFp32BaseConfig extends Config(new WithDCachePolicyExact("regression-fp32:2,-1,0,0,0") ++ new LargeL1Config)

// 5-weight set from a random search in model.py on repl_bench (in-sample, NOT trained).
class ExpRegFp32Config extends Config(new WithDCachePolicyExact("regression-fp32:-1.33,-1.35,-1.17,1.62,-0.01") ++ new LargeL1Config)
// The same weights quantized to int8 (python3 model.py --quantize=-1.33,-1.35,-1.17,1.62,-0.01).
class ExpRegInt8Config extends Config(new WithDCachePolicyExact("regression-int8:-104,-106,-92,127,-1") ++ new LargeL1Config)
