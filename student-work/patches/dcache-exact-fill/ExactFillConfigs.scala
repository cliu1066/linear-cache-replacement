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