// ECE 462/562 linear-regression cache replacement project: stand-alone policy.
// Requires the dcache-exact-fill patch (FillAwareReplacement).
package freechips.rocketchip.util

import chisel3._
import chisel3.util._

/** Five-feature linear-score replacement policy with int8 or fp32 weights.
  *
  * Per-way features, in weight order (all unsigned integers):
  *   0 rank   log2(ways) b  recency rank in the set, 0 = most recently used
  *   1 reuse  2 b           saturating count of reuses since insertion. Only a
  *                          touch on a line that is not already MRU counts, so
  *                          back-to-back accesses (memset, struct copies) don't.
  *   2 store  1 b           written since insertion
  *   3 idle   4 b           accesses to this set since this line was last
  *                          touched, saturating at 15
  *   4 life   4 b           accesses to this set since this line was inserted,
  *                          saturating at 15
  *
  * score = sum_i w_i * x_i; the way with the HIGHEST score is evicted, ties go
  * to the lower way index. There is no bias: it would be the same for every way.
  *
  * Reset: the D$ zeroes the per-set state. rank is stored XOR the way index and
  * idle/life are stored inverted, so the zero state decodes to a valid recency
  * order with every line "old and idle" (15), i.e. empty ways look evictable.
  *
  * Weight formats:
  *   Int8 : integer weights in [-128, 127]; exact integer arithmetic.
  *   Fp32 : IEEE-754 single precision. Each product w_i * x_i is a constant
  *          lookup table indexed by x_i (computed in fp32 at elaboration; one
  *          operand is a constant and x_i has at most 16 values). The five
  *          products are summed left to right, ((((p0+p1)+p2)+p3)+p4), with
  *          HardFloat fp32 adders (round to nearest even), and ways are
  *          compared with HardFloat fp32 comparators. model.py reproduces
  *          this bit for bit.
  */
sealed trait RegressionWeights {
  def describe: String
}
case class RegressionInt8(w: Seq[Int]) extends RegressionWeights {
  require(w.size == RegressionReplacement.nFeatures, s"int8 policy needs ${RegressionReplacement.nFeatures} weights, got ${w.size}")
  require(w.forall(x => x >= -128 && x <= 127), s"int8 weights must be in [-128, 127]: ${w.mkString(",")}")
  def describe = s"int8 ${w.mkString(",")}"
}
case class RegressionFp32(w: Seq[Float]) extends RegressionWeights {
  require(w.size == RegressionReplacement.nFeatures, s"fp32 policy needs ${RegressionReplacement.nFeatures} weights, got ${w.size}")
  require(w.forall(x => !x.isNaN && !x.isInfinite), s"fp32 weights must be finite: ${w.mkString(",")}")
  def describe = s"fp32 ${w.mkString(",")}"
}

class RegressionReplacement(n_ways: Int, val weights: RegressionWeights)
    extends ReplacementPolicy with FillAwareReplacement {
  require(n_ways > 1 && isPow2(n_ways), "RegressionReplacement needs a power-of-two number of ways > 1")

  import RegressionReplacement._

  private val rankBits = log2Ceil(n_ways)
  private val featBits = Seq(rankBits, reuseBits, 1, idleBits, lifeBits)
  private val wayBits  = featBits.sum
  def nBits  = n_ways * wayBits
  def perSet = true

  // Worst-case |score| must stay finite in fp32 (ints can't overflow: widths are inferred).
  weights match {
    case RegressionFp32(w) =>
      val maxAbs = (w zip featBits).map { case (wi, b) => math.abs(wi.toDouble) * ((1 << b) - 1) }.sum
      require(maxAbs < Float.MaxValue.toDouble, s"fp32 weights can overflow the score: max |score| = $maxAbs")
    case _ =>
  }

  private case class Way(rank: UInt, reuse: UInt, store: Bool, idle: UInt, life: UInt) {
    def feats: Seq[UInt] = Seq(rank, reuse, store.asUInt, idle, life)
  }

  // Field layout per way, LSB first: rank^i | reuse | store | ~idle | ~life
  private def unpack(state: UInt): Seq[Way] = (0 until n_ways).map { i =>
    val f = state((i + 1) * wayBits - 1, i * wayBits)
    var lsb = 0
    def take(b: Int): UInt = { val v = f(lsb + b - 1, lsb); lsb += b; v }
    val rank  = take(rankBits) ^ i.U(rankBits.W)
    val reuse = take(reuseBits)
    val store = take(1).asBool
    val idle  = ~take(idleBits)
    val life  = ~take(lifeBits)
    Way(rank, reuse, store, idle, life)
  }

  private def pack(ws: Seq[Way]): UInt =
    Cat(ws.zipWithIndex.reverse.map { case (w, i) =>
      Cat(~w.life, ~w.idle, w.store, w.reuse, w.rank ^ i.U(rankBits.W))
    })

  private def satInc(x: UInt): UInt = Mux(x.andR, x, x + 1.U)

  def get_next_state_touch(state: UInt, touch_way: UInt, isInsert: Bool, isWrite: Bool): UInt = {
    val ws = unpack(state)
    val tRank = Mux1H(UIntToOH(touch_way, n_ways), ws.map(_.rank))
    pack(ws.zipWithIndex.map { case (w, i) =>
      val me = touch_way === i.U
      Way(
        rank  = Mux(me, 0.U(rankBits.W), Mux(w.rank < tRank, w.rank + 1.U, w.rank)),
        reuse = Mux(me, Mux(isInsert, 0.U(reuseBits.W), Mux(w.rank === 0.U, w.reuse, satInc(w.reuse))), w.reuse),
        store = Mux(me, Mux(isInsert, isWrite, w.store || isWrite), w.store),
        idle  = Mux(me, 0.U(idleBits.W), satInc(w.idle)),
        life  = Mux(me && isInsert, 0.U(lifeBits.W), satInc(w.life)))
    })
  }

  def get_next_state(state: UInt, touch_way: UInt): UInt =
    get_next_state_touch(state, touch_way, false.B, false.B)

  def get_replace_way(state: UInt): UInt = {
    val ways = unpack(state)
    val idx = (0 until n_ways).map(_.U(rankBits.W))
    weights match {
      case RegressionInt8(w) =>
        val scores = ways.map(way => (way.feats zip w).map { case (x, wi) => x.zext * wi.S }.reduce(_ +& _))  // +& widens: no overflow
        (scores zip idx).reduce { (a, b) =>
          val takeB = b._1 > a._1
          (Mux(takeB, b._1, a._1), Mux(takeB, b._2, a._2))
        }._2
      case RegressionFp32(w) =>
        val scores = ways.map(way => fp32Score(way.feats, w))
        (scores zip idx).reduce { (a, b) =>
          val cmp = Module(new hardfloat.CompareRecFN(8, 24))
          cmp.io.a := b._1
          cmp.io.b := a._1
          cmp.io.signaling := false.B
          val takeB = cmp.io.gt          // strictly greater: ties keep the lower way
          (Mux(takeB, b._1, a._1), Mux(takeB, b._2, a._2))
        }._2
    }
  }

  /** Recoded-fp32 score: LUT products, then a left-to-right chain of fp32 adds. */
  private def fp32Score(feats: Seq[UInt], w: Seq[Float]): UInt = {
    val products = (feats zip (w zip featBits)).map { case (x, (wi, b)) =>
      val table = (0 until (1 << b)).map(v => fp32Bits(wi * v.toFloat))   // JVM float mul = IEEE fp32 RNE
      val bits = VecInit(table.map(_.U(32.W)))(x)
      hardfloat.recFNFromFN(8, 24, bits)
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

  // Only the per-set path (DCache) is supported; these keep the interface
  // without building an unused scoring tree on state_reg.
  private val state_reg = RegInit(0.U(nBits.W))
  def state_read = WireDefault(state_reg)
  def access(touch_way: UInt): Unit = { state_reg := get_next_state(state_reg, touch_way) }
  def access(touch_ways: Seq[Valid[UInt]]): Unit =
    when (touch_ways.map(_.valid).reduce(_ || _)) { state_reg := get_next_state(state_reg, touch_ways) }
  def way = get_replace_way(state_reg)
  def miss = {}
  def hit = {}
}

object RegressionReplacement {
  val nFeatures = 5
  val reuseBits = 2
  val idleBits  = 4
  val lifeBits  = 4
  val featureNames = Seq("rank", "reuse", "store", "idle", "life")

  /** Default: the 3-feature policy's (2,-1,0) weights, idle and life unused. */
  val defaultWeights = RegressionInt8(Seq(2, -1, 0, 0, 0))

  /** Policy strings (case-insensitive; DCacheParams lower-cases them):
    *   "regression"                          default int8 weights
    *   "regression-int8:w0,w1,w2,w3,w4"      int8 weights in [-128, 127]
    *   "regression-fp32:w0,w1,w2,w3,w4"      fp32 weights, e.g. 0.5,-1.25,0,1e-3,0
    * Weight order: rank, reuse, store, idle, life.
    * fp32 text is parsed as a double, then rounded to fp32 (model.py does the same).
    */
  def fromString(s: String, n_ways: Int): RegressionReplacement = {
    def nums(prefix: String): Seq[String] = {
      val body = s.stripPrefix(prefix)
      require(body.startsWith(":"), s"policy '$s': expected '$prefix:w0,w1,w2,w3,w4'")
      body.drop(1).split(",").map(_.trim).filter(_.nonEmpty).toSeq
    }
    val w =
      if (s == "regression") defaultWeights
      else if (s.startsWith("regression-int8")) RegressionInt8(nums("regression-int8").map(_.toInt))
      else if (s.startsWith("regression-fp32")) RegressionFp32(nums("regression-fp32").map(_.toDouble.toFloat))
      else throw new IllegalArgumentException(s"unknown regression policy '$s'; use regression, regression-int8:..., or regression-fp32:...")
    println(s"[RegressionReplacement] $n_ways ways, weights ${w.describe} (order: ${featureNames.mkString(",")})")
    new RegressionReplacement(n_ways, w)
  }
}

private object fp32Bits {
  def apply(f: Float): BigInt = BigInt(java.lang.Float.floatToRawIntBits(f).toLong & 0xffffffffL)
}
