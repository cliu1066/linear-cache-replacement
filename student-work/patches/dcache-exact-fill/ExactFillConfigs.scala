// ECE 462/562 - configs for the dcache-exact-fill patch.
//
// Requires student-work/patches/dcache-exact-fill/apply.sh to have run first:
// "lru-exact" only exists in the patched rocket-chip. That is why this file is
// under patches/ (not auto-linked); apply.sh links it into Chipyard.
//
// Build from chipyard/sims/verilator:
//   make CONFIG=LruExactL1Config
//
package chipyard

import org.chipsalliance.cde.config._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.rocket._

/** Sets the L1 D$ replacement policy string on every Rocket tile. */
class WithDCachePolicyExact(policy: String) extends Config((site, here, up) => {
  case TilesLocated(InSubsystem) => up(TilesLocated(InSubsystem), site) map {
    case tp: RocketTileAttachParams => tp.copy(tileParams = tp.tileParams.copy(
      dcache = tp.tileParams.dcache.map(_.copy(replacementPolicy = policy))))
    case t => t
  }
})

/** True LRU through the fill-aware path. Must match an "lru" config's cycle and
  * miss counts exactly; any difference means the patch broke something. */
class LruExactL1Config extends Config(new WithDCachePolicyExact("lru-exact") ++ new LargeL1Config)

// After LinRegReplacement mixes in FillAwareReplacement and its strings are
// registered in fromString (see README.md), uncomment:
// class LinRegIntExactL1Config   extends Config(new WithDCachePolicyExact("linreg-int-exact")   ++ new LargeL1Config)
// class LinRegShiftExactL1Config extends Config(new WithDCachePolicyExact("linreg-shift-exact") ++ new LargeL1Config)
