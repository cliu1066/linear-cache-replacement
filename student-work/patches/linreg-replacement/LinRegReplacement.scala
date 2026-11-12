package freechips.rocketchip.util

import chisel3._
import chisel3.util._

/** Linear Regression-Based L1 DCache Replacement
  *
  * Features:
  *   frequency     per way  0-15   hits on this line since it was loaded
  *   age           per way  0-255  set accesses since this line was loaded
  *   lru_rank      per way  0..ways-1, 0 = most recently used (by default;
  *                          see lruRankZeroIsMru)
  *   last_reuse    per way  0-255  set accesses between this line's two most
  *                          recent accesses; 255 = no measured reuse yet
  *   set_pressure  per set  0-15   misses in the current window of 256 set
  *                          accesses (a tumbling window: the script uses a
  *                          sliding one). Identical for every way in a set, so
  *                          it adds the same amount to every score and can
  *                          never change which way is evicted. Kept only to
  *                          match the model.
  *
  * Only the features a model uses are stored. Insertions come from the
  * exact-fill patch (isInsert), so "since inserted" resets are exact.
  *
  * score = sum_i w_i * x_i. Empty (never filled) ways are always chosen first,
  * lowest index first; otherwise the highest score is evicted if evictHighest,
  * else the lowest. Ties go to the lower way. The intercept, and the constant
  * that un-standardizing produces, are the same for every way, so they are
  * dropped: they cannot change the victim.
  *
  * Arithmetic:
  *   int8 : weights scaled so the largest |w| is 127, then rounded; exact
  *          integer multiply-add.
  *   fp32 : IEEE single precision. Each product w_i * x_i is a constant lookup
  *          table (computed in fp32 at elaboration); products are summed left to
  *          right with HardFloat adders and ways compared with HardFloat
  *          comparators.
  */
case class LinRegModel(
    name: String,
    features: Seq[String],        // subset of LinRegReplacement.allFeatures, in weight order
    rawWeights: Seq[Double],      // weights on the hardware feature values (not standardized)
    evictHighest: Boolean,
    lruRankZeroIsMru: Boolean) {
  require(features.nonEmpty && features.distinct.size == features.size, s"$name: features must be non-empty and distinct")
  require(features.forall(LinRegReplacement.allFeatures.contains),
    s"$name: unknown feature in ${features.mkString(",")}; known: ${LinRegReplacement.allFeatures.mkString(",")}")
  require(rawWeights.size == features.size, s"$name: ${features.size} features but ${rawWeights.size} weights")
  require(rawWeights.forall(w => !w.isNaN && !w.isInfinite), s"$name: weights must be finite: ${rawWeights.mkString(",")}")
  require(rawWeights.exists(_ != 0.0), s"$name: all weights are zero")
  def has(f: String): Boolean = features.contains(f)
}

object LinRegModel {
  def fromStandardized(
      name: String,
      features: Seq[String],
      stdWeights: Seq[Double],
      featureStd: Map[String, Double],
      evictHighest: Boolean,
      lruRankZeroIsMru: Boolean): LinRegModel = {
    val std = features.map(f => featureStd.getOrElse(f, Double.NaN))
    require(std.forall(s => !s.isNaN && s > 0),
      s"$name: fill in LinRegWeights.featureStd with the script's StandardScaler scale_ values " +
      s"(must be > 0); got ${features.zip(std).map { case (f, s) => s"$f=$s" }.mkString(", ")}")
    require(stdWeights.size == features.size, s"$name: ${features.size} features but ${stdWeights.size} weights")
    LinRegModel(name, features, stdWeights.zip(std).map { case (w, s) => w / s }, evictHighest, lruRankZeroIsMru)
  }
}

class LinRegReplacement(n_ways: Int, val model: LinRegModel, val fp32: Boolean)
    extends ReplacementPolicy with FillAwareReplacement {
  require(n_ways > 1 && isPow2(n_ways), "LinRegReplacement needs a power-of-two number of ways > 1")
  import LinRegReplacement._

  private val rankBits = log2Ceil(n_ways)
  private val useFreq  = model.has("frequency")
  private val useAge   = model.has("age")
  private val useReuse = model.has("last_reuse")
  private val usePress = model.has("set_pressure")

  // Per-way fields, LSB first. "rec" is recency (0 = MRU), stored XOR the way
  // index so the all-zero reset state decodes to a valid order. "idle" (set
  // accesses since the line was last touched) is not a feature; it is needed
  // to compute last_reuse.
  private val wayFields: Seq[(String, Int)] =
    Seq("valid" -> 1, "rec" -> rankBits) ++
    (if (useFreq)  Seq("frequency" -> freqBits) else Nil) ++
    (if (useAge)   Seq("age" -> timeBits) else Nil) ++
    (if (useReuse) Seq("idle" -> timeBits, "last_reuse" -> timeBits) else Nil)
  private val wayBits = wayFields.map(_._2).sum
  // set_pressure sits above all ways: miss count (low bits), then the window's access count.
  private val setBits = if (usePress) pressBits + windowBits else 0

  def nBits  = n_ways * wayBits + setBits
  def perSet = true

  private def fit(v: UInt, b: Int): UInt = if (v.getWidth >= b) v(b - 1, 0) else v.pad(b)
  private def satInc(x: UInt): UInt = Mux(x.andR, x, x + 1.U)

  private def unpackWay(state: UInt, i: Int): Map[String, UInt] = {
    var lsb = i * wayBits
    wayFields.map { case (n, b) =>
      val raw = state(lsb + b - 1, lsb)
      lsb += b
      n -> (if (n == "rec") raw ^ i.U(rankBits.W) else raw)
    }.toMap
  }

  private def packWay(f: Map[String, UInt], i: Int): UInt =
    Cat(wayFields.reverse.map { case (n, b) =>
      val v = fit(f(n), b)
      if (n == "rec") v ^ i.U(rankBits.W) else v
    })

  private val pressLsb = n_ways * wayBits
  private def pressure(state: UInt): UInt = state(pressLsb + pressBits - 1, pressLsb)
  private def window(state: UInt): UInt   = state(nBits - 1, pressLsb + pressBits)

  private def featureValue(f: String, w: Map[String, UInt], press: Option[UInt]): UInt = f match {
    case "frequency"    => w("frequency")
    case "age"          => w("age")
    case "lru_rank"     => if (model.lruRankZeroIsMru) w("rec") else (n_ways - 1).U(rankBits.W) - w("rec")
    case "last_reuse"   => w("last_reuse")
    case "set_pressure" => press.get
  }

  // ------------------------------------------------------------- state update
  def get_next_state_touch(state: UInt, touch_way: UInt, isInsert: Bool, isWrite: Bool): UInt = {
    val ways = (0 until n_ways).map(unpackWay(state, _))
    val tRec = Mux1H(UIntToOH(touch_way, n_ways), ways.map(_("rec")))
    val newWays = ways.zipWithIndex.map { case (w, i) =>
      val me = touch_way === i.U
      var n = Map[String, UInt](
        "valid" -> (w("valid").asBool || (me && isInsert)).asUInt,
        "rec"   -> Mux(me, 0.U(rankBits.W), Mux(w("rec") < tRec, w("rec") + 1.U, w("rec"))))
      if (useFreq) n += "frequency" -> Mux(me, Mux(isInsert, 0.U(freqBits.W), satInc(w("frequency"))), w("frequency"))
      if (useAge)  n += "age" -> Mux(me && isInsert, 0.U(timeBits.W), satInc(w("age")))
      if (useReuse) {
        // idle = set accesses since this line was last touched; on a re-touch it
        // becomes last_reuse. A newly loaded line has no measured reuse (255).
        n += "idle"       -> Mux(me, 0.U(timeBits.W), satInc(w("idle")))
        n += "last_reuse" -> Mux(me, Mux(isInsert, maxTime.U(timeBits.W), w("idle")), w("last_reuse"))
      }
      packWay(n, i)
    }
    val wayPart = Cat(newWays.reverse)
    if (usePress) {
      // Every touch is one set access; an insertion touch is a miss.
      val p = pressure(state)
      val acc = window(state)
      val windowEnd = acc.andR                       // 256th access of this window
      val nextP = Mux(windowEnd, isInsert.asUInt, Mux(isInsert, satInc(p), p))
      Cat(acc + 1.U, fit(nextP, pressBits), wayPart) // acc wraps 255 -> 0
    } else {
      wayPart
    }
  }

  def get_next_state(state: UInt, touch_way: UInt): UInt =
    get_next_state_touch(state, touch_way, false.B, false.B)

  // --------------------------------------------------------- victim selection
  private val int8Weights: Seq[Int] = {
    val m = model.rawWeights.map(math.abs).max
    model.rawWeights.map(w => math.round(w / m * 127.0).toInt)
  }

  def get_replace_way(state: UInt): UInt = {
    val ways  = (0 until n_ways).map(unpackWay(state, _))
    val press = if (usePress) Some(pressure(state)) else None
    val feats = ways.map(w => model.features.map(f => featureValue(f, w, press)))
    val idx   = (0 until n_ways).map(_.U(rankBits.W))

    val scored: UInt =
      if (!fp32) {
        val scores = feats.map(x => (x zip int8Weights).map { case (xi, wi) => xi.zext * wi.S }.reduce(_ +& _))
        (scores zip idx).reduce { (a, b) =>
          val takeB = if (model.evictHighest) b._1 > a._1 else b._1 < a._1
          (Mux(takeB, b._1, a._1), Mux(takeB, b._2, a._2))
        }._2
      } else {
        val w = model.rawWeights.map(_.toFloat)
        val scores = feats.map(x => fp32Score(x, w))
        (scores zip idx).reduce { (a, b) =>
          val cmp = Module(new hardfloat.CompareRecFN(8, 24))
          cmp.io.a := b._1
          cmp.io.b := a._1
          cmp.io.signaling := false.B
          val takeB = if (model.evictHighest) cmp.io.gt else cmp.io.lt   // strict: ties keep the lower way
          (Mux(takeB, b._1, a._1), Mux(takeB, b._2, a._2))
        }._2
      }

    val empty = VecInit(ways.map(w => !w("valid").asBool)).asUInt
    Mux(empty.orR, PriorityEncoder(empty), scored)
  }

  /** Recoded-fp32 score: LUT products, then a left-to-right chain of fp32 adds. */
  private def fp32Score(feats: Seq[UInt], w: Seq[Float]): UInt = {
    val products = (feats zip w).map { case (x, wi) =>
      val table = (0 until (1 << x.getWidth)).map(v => linregFp32Bits(wi * v.toFloat))
      hardfloat.recFNFromFN(8, 24, VecInit(table.map(_.U(32.W)))(x))
    }
    products.reduceLeft { (acc, p) =>
      val add = Module(new hardfloat.AddRecFN(8, 24))
      add.io.subOp := false.B
      add.io.a := acc
      add.io.b := p
      add.io.roundingMode := hardfloat.consts.round_near_even
      add.io.detectTininess := hardfloat.consts.tininess_afterRounding
      add.io.out
    }
  }

  // Only the per-set path (DCache) is supported; these keep the interface.
  private val state_reg = RegInit(0.U(nBits.W))
  def state_read = WireDefault(state_reg)
  def access(touch_way: UInt): Unit = { state_reg := get_next_state(state_reg, touch_way) }
  def access(touch_ways: Seq[Valid[UInt]]): Unit =
    when (touch_ways.map(_.valid).reduce(_ || _)) { state_reg := get_next_state(state_reg, touch_ways) }
  def way = get_replace_way(state_reg)
  def miss = {}
  def hit = {}

  println(s"[LinRegReplacement] ${model.name}-${if (fp32) "fp32" else "int8"}: $n_ways ways, $nBits state bits/set, " +
    s"features ${model.features.mkString(",")}, " +
    (if (fp32) s"fp32 weights ${model.rawWeights.map(_.toFloat).mkString(",")}" else s"int8 weights ${int8Weights.mkString(",")}") +
    s", evict ${if (model.evictHighest) "highest" else "lowest"}, lru_rank 0 = ${if (model.lruRankZeroIsMru) "MRU" else "LRU"}")
  if (!fp32 && int8Weights.contains(0))
    println(s"[LinRegReplacement] WARNING: a ${model.name} weight rounds to 0 in int8, so that feature has no effect")
}

object LinRegReplacement {
  val allFeatures = Seq("frequency", "age", "lru_rank", "last_reuse", "set_pressure")
  val freqBits   = 4   // frequency: 0-15
  val timeBits   = 8   // age, last_reuse (and hidden idle): 0-255
  val maxTime    = (1 << timeBits) - 1
  val pressBits  = 4   // set_pressure: 0-15
  val windowBits = 8   // set_pressure window: 256 set accesses

  /** Policy strings: "<model>-int8" or "<model>-fp32", e.g. "linreg5-int8".
    * Models are defined in LinRegWeights.scala. */
  def fromString(s: String, n_ways: Int): LinRegReplacement = {
    val (name, arith) = s.lastIndexOf('-') match {
      case -1 => throw new IllegalArgumentException(s"policy '$s': expected <model>-int8 or <model>-fp32")
      case i  => (s.take(i), s.drop(i + 1))
    }
    require(arith == "int8" || arith == "fp32", s"policy '$s': precision must be int8 or fp32")
    new LinRegReplacement(n_ways, LinRegWeights.model(name), fp32 = arith == "fp32")
  }
}

private object linregFp32Bits {
  def apply(f: Float): BigInt = BigInt(java.lang.Float.floatToRawIntBits(f).toLong & 0xffffffffL)
}
