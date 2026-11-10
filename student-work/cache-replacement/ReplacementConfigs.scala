package chipyard

import org.chipsalliance.cde.config._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.rocket._

/** Set L1 DCache replacement policy on every Rocket tile
  * Valid strings (see ReplacementPolicy.fromString): "random", "lru", "plru" */
class WithDCacheReplacement(
    policy: String
) extends Config((site, here, up) => {
    case TilesLocated(InSubsystem) => up(TilesLocated(InSubsystem), site) map {
        case tp: RocketTileAttachParams => tp.copy(tileParams = tp.tileParams.copy(
            dcache = tp.tileParams.dcache.map(_.copy(replacementPolicy = policy))))
        case other => other
    }
})

class LruL1Config extends Config(
    new WithDCacheReplacement("lru") ++ 
    new LargeL1Config)

class RandomL1Config extends Config(
    new WithDCacheReplacement("random") ++ 
    new LargeL1Config)