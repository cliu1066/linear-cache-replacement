package freechips.rocketchip.util

object LinRegWeights {
  // lru_rank: 0 = most recently used
  val lruRankZeroIsMru = true
  // Evict the LOWEST score. lru_rank has the largest weight and it is negative,
  // so with 0 = MRU the score falls as a line gets older; frequency (+) raises it.
  val evictHighest     = false

  // 5 features
  lazy val linreg5: LinRegModel = LinRegModel(
    name             = "linreg5",
    features         = Seq("frequency", "age", "lru_rank", "last_reuse", "set_pressure"),
    rawWeights       = Seq(0.289275, -0.019032, -0.329128, 0.022885, 0.064638),
    evictHighest     = evictHighest,
    lruRankZeroIsMru = lruRankZeroIsMru)

  // 2 features
  lazy val linreg2: LinRegModel = LinRegModel(
    name             = "linreg2",
    features         = Seq("frequency", "lru_rank"),
    rawWeights       = Seq(0.187185, -0.239058),
    evictHighest     = evictHighest,
    lruRankZeroIsMru = lruRankZeroIsMru)

  def model(name: String): LinRegModel = name match {
    case "linreg5" => linreg5
    case "linreg2" => linreg2
    case other    => throw new IllegalArgumentException(s"unknown LinReg model '$other' (have: linreg5, linreg2)")
  }
}
