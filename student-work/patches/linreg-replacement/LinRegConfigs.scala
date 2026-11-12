package chipyard

import org.chipsalliance.cde.config._

// 5 features: frequency, age, lru_rank, last_reuse, set_pressure
class LinReg5Int8Config extends Config(new WithDCachePolicyExact("linreg5-int8") ++ new LargeL1Config)
class LinReg5Fp32Config extends Config(new WithDCachePolicyExact("linreg5-fp32") ++ new LargeL1Config)

// 2 features: frequency, lru_rank
class LinReg2Int8Config extends Config(new WithDCachePolicyExact("linreg2-int8") ++ new LargeL1Config)
class LinReg2Fp32Config extends Config(new WithDCachePolicyExact("linreg2-fp32") ++ new LargeL1Config)