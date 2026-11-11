package com.notebook.app.ui.editor.selection

import com.notebook.app.ui.editor.viewport.DuPoint
import java.math.BigInteger

/**
 * Thread-confined, closeable arithmetic arena. Successful values belong to this context and must
 * not be used after close. Arena accounting conservatively retains intermediates until close;
 * per-integer scratch reservations are released immediately. Neither is measured JVM heap.
 * Failure/cancellation closes the entire arena, invalidating earlier values, with no budget reset.
 */
internal class SelectionExactArithmetic(private val budget: SelectionQueryBudget) : AutoCloseable {
    sealed interface Rational {
        val numerator: BigInteger
        val denominator: BigInteger
        val context: SelectionExactArithmetic
    }
    private class Fraction(override val numerator: BigInteger, override val denominator: BigInteger,
        override val context: SelectionExactArithmetic) : Rational {
        override fun equals(other: Any?) = other is Rational &&
            numerator == other.numerator && denominator == other.denominator
        override fun hashCode() = 31 * numerator.hashCode() + denominator.hashCode()
    }
    sealed interface Point { val x: Rational; val y: Rational }
    private data class ExactPoint(override val x: Rational, override val y: Rational) : Point
    sealed interface Dyadic { val coefficient: BigInteger; val exponent: Int }
    private data class Binary(override val coefficient: BigInteger, override val exponent: Int) : Dyadic
    private class Stop(val failure: SelectionWorkloadFailure) : RuntimeException()
    private class Invalid : RuntimeException()

    private var open = true
    private var running = false
    private var arenaBytes = 0L

    fun <T> attempt(action: SelectionExactArithmetic.() -> T): SelectionPredicateResult<T> {
        check(open && !running) { "Closed or nested exact context" }
        running = true
        return try {
            budget.checkpoint()
            budget.failure?.let { throw Stop(it) }
            SelectionPredicateResult.Known(action())
        } catch (stop: Stop) {
            close()
            SelectionPredicateResult.WorkloadExceeded(stop.failure)
        } catch (_: Invalid) {
            close()
            SelectionPredicateResult.InvalidCoordinates
        } catch (failure: Throwable) {
            close()
            throw failure
        } finally { running = false }
    }

    override fun close() {
        if (open) {
            budget.releaseTemporary(arenaBytes)
            arenaBytes = 0
            open = false
        }
    }

    private fun active() { check(open && running) { "Arithmetic requires an active attempt" } }
    internal fun consume(failure: SelectionWorkloadFailure?) { active(); if (failure != null) throw Stop(failure) }
    private fun reserve(bytes: Long) {
        consume(budget.charge(SelectionQueryResource.TEMPORARY_BYTES, bytes))
        arenaBytes += bytes // query charge proved this addition fits
    }
    // Avoid allocating an absolute-value representation during admission. For negative values
    // this is exact for powers of two and conservatively one bit high otherwise.
    private fun bits(n: BigInteger) = n.bitLength().toLong() + if (n.signum() < 0) 1L else 0L
    private fun limbs(bits: Long) = maxOf(1L, (bits + 31) / 32)

    /** Admission covers operands, result, and scratch before the BigInteger operation. */
    private fun integer(bound: Long, cost: Long, action: () -> BigInteger): BigInteger {
        active()
        consume(budget.checkExactBits(bound))
        consume(budget.charge(SelectionQueryResource.WORK_UNITS, cost))
        val bytes = 64L + (bound + 7) / 8
        reserve(bytes)
        val scratch = 4 * bytes + 128
        consume(budget.charge(SelectionQueryResource.TEMPORARY_BYTES, scratch))
        return try { action() } finally { budget.releaseTemporary(scratch) }
    }
    private fun neg(a: BigInteger) = integer(bits(a), limbs(bits(a))) { a.negate() }
    private fun shift(a: BigInteger, n: Int): BigInteger {
        val bound = if (n >= 0) bits(a) + n.toLong() else bits(a)
        return integer(bound, limbs(bound) + limbs(bits(a))) { a.shiftLeft(n) }
    }
    private fun sum(a: BigInteger, b: BigInteger, subtract: Boolean = false): BigInteger {
        val bound = maxOf(bits(a), bits(b)) + 1
        return integer(bound, limbs(bits(a)) + limbs(bits(b)) + limbs(bound)) {
            if (subtract) a.subtract(b) else a.add(b)
        }
    }
    private fun product(a: BigInteger, b: BigInteger): BigInteger =
        integer(bits(a) + bits(b), limbs(bits(a)) * limbs(bits(b)) + limbs(bits(a) + bits(b))) {
            a.multiply(b)
        }
    private fun quotient(a: BigInteger, b: BigInteger): BigInteger {
        if (b.signum() == 0) throw Invalid()
        return integer(maxOf(bits(a), bits(b)), limbs(bits(a)) * limbs(bits(b)) + limbs(bits(a))) {
            a.divide(b)
        }
    }
    private fun remainder(a: BigInteger, b: BigInteger): BigInteger =
        integer(maxOf(bits(a), bits(b)), limbs(bits(a)) * limbs(bits(b)) + limbs(bits(b))) {
            a.remainder(b)
        }
    private fun gcd(first: BigInteger, second: BigInteger): BigInteger {
        var a = if (first.signum() < 0) neg(first) else first
        var b = if (second.signum() < 0) neg(second) else second
        while (b.signum() != 0) {
            budget.checkpoint()
            val r = remainder(a, b); a = b; b = r
        }
        return a
    }
    private fun normalizeBinary(n: BigInteger, exponent: Int): Dyadic {
        reserve(32)
        if (n.signum() == 0) return Binary(BigInteger.ZERO, 0)
        consume(budget.charge(SelectionQueryResource.WORK_UNITS, limbs(bits(n))))
        val trailing = n.lowestSetBit
        return Binary(shift(n, -trailing), exponent + trailing)
    }
    fun dyadic(value: Double): Dyadic {
        active()
        if (!value.isFinite()) throw Invalid()
        val raw = value.toRawBits()
        val e = ((raw ushr 52) and 0x7ff).toInt()
        var m = raw and 0x000fffffffffffffL
        if (e != 0) m = m or (1L shl 52)
        if (raw < 0) m = -m
        val bound = if (m == 0L) 0L else 64L - java.lang.Long.numberOfLeadingZeros(kotlin.math.abs(m))
        return normalizeBinary(integer(bound, 4) { BigInteger.valueOf(m) }, if (e == 0) -1074 else e - 1075)
    }
    private fun binarySubtract(a: Dyadic, b: Dyadic): Dyadic {
        val e = minOf(a.exponent, b.exponent)
        // Preserve the original predicate's conservative aligned carry bound.
        consume(budget.checkExactBits(maxOf(bits(a.coefficient) + a.exponent - e,
            bits(b.coefficient) + b.exponent - e) + 1))
        return normalizeBinary(sum(shift(a.coefficient, a.exponent - e),
            shift(b.coefficient, b.exponent - e), true), e)
    }
    private fun binaryMultiply(a: Dyadic, b: Dyadic): Dyadic =
        normalizeBinary(product(a.coefficient, b.coefficient), a.exponent + b.exponent)

    /** Predicate extraction preserves the binary path; rational normalization is unnecessary here. */
    fun orientation(a: DuPoint, b: DuPoint, c: DuPoint): SelectionRobustPredicates.Sign {
        val ax = dyadic(a.x); val ay = dyadic(a.y)
        val bx = dyadic(b.x); val by = dyadic(b.y)
        val cx = dyadic(c.x); val cy = dyadic(c.y)
        val abx = binarySubtract(bx, ax); val aby = binarySubtract(by, ay)
        val acx = binarySubtract(cx, ax); val acy = binarySubtract(cy, ay)
        return signOf(binarySubtract(binaryMultiply(abx, acy), binaryMultiply(aby, acx)).coefficient.signum())
    }
    fun inBounds(p: DuPoint, a: DuPoint, b: DuPoint): Boolean {
        consume(budget.charge(SelectionQueryResource.WORK_UNITS, 12))
        return p.x >= minOf(a.x, b.x) && p.x <= maxOf(a.x, b.x) &&
            p.y >= minOf(a.y, b.y) && p.y <= maxOf(a.y, b.y)
    }
    private fun checked(a: Rational) { active(); require(a.context === this) { "Foreign exact value" } }
    private fun fraction(n: BigInteger, d: BigInteger): Rational {
        if (d.signum() == 0) throw Invalid()
        consume(budget.checkExactBits(maxOf(bits(n), bits(d))))
        reserve(64)
        if (n.signum() == 0) return Fraction(BigInteger.ZERO, BigInteger.ONE, this)
        val divisor = gcd(n, d)
        var numerator = quotient(n, divisor); var denominator = quotient(d, divisor)
        if (denominator.signum() < 0) { numerator = neg(numerator); denominator = neg(denominator) }
        return Fraction(numerator, denominator, this)
    }
    fun rational(numerator: Long, denominator: Long = 1): Rational {
        fun longBits(value: Long): Long = when {
            value == 0L -> 0
            value == Long.MIN_VALUE -> 64
            else -> 64L - java.lang.Long.numberOfLeadingZeros(kotlin.math.abs(value))
        }
        val nBits = longBits(numerator)
        val dBits = longBits(denominator)
        val n = integer(nBits, 4) { BigInteger.valueOf(numerator) }
        val d = integer(dBits, 4) { BigInteger.valueOf(denominator) }
        return fraction(n, d)
    }
    fun fromDouble(value: Double): Rational {
        val b = dyadic(value)
        return if (b.exponent >= 0) fraction(shift(b.coefficient, b.exponent), BigInteger.ONE)
        else fraction(b.coefficient, shift(BigInteger.ONE, -b.exponent))
    }
    fun add(a: Rational, b: Rational): Rational {
        checked(a); checked(b)
        val g = gcd(a.denominator, b.denominator)
        val ad = quotient(a.denominator, g); val bd = quotient(b.denominator, g)
        return fraction(sum(product(a.numerator, bd), product(b.numerator, ad)),
            product(ad, b.denominator))
    }
    fun subtract(a: Rational, b: Rational): Rational {
        checked(a); checked(b)
        val g = gcd(a.denominator, b.denominator)
        val ad = quotient(a.denominator, g); val bd = quotient(b.denominator, g)
        return fraction(sum(product(a.numerator, bd), product(b.numerator, ad), true),
            product(ad, b.denominator))
    }
    fun multiply(a: Rational, b: Rational): Rational {
        checked(a); checked(b)
        val g1 = gcd(a.numerator, b.denominator); val g2 = gcd(b.numerator, a.denominator)
        return fraction(product(quotient(a.numerator, g1), quotient(b.numerator, g2)),
            product(quotient(a.denominator, g2), quotient(b.denominator, g1)))
    }
    fun divide(a: Rational, b: Rational): Rational {
        checked(a); checked(b)
        if (b.numerator.signum() == 0) throw Invalid()
        val g1 = gcd(a.numerator, b.numerator); val g2 = gcd(a.denominator, b.denominator)
        return fraction(product(quotient(a.numerator, g1), quotient(b.denominator, g2)),
            product(quotient(a.denominator, g2), quotient(b.numerator, g1)))
    }
    fun sign(a: Rational): SelectionRobustPredicates.Sign {
        checked(a); consume(budget.charge(SelectionQueryResource.WORK_UNITS))
        return signOf(a.numerator.signum())
    }
    fun compare(a: Rational, b: Rational): SelectionRobustPredicates.Sign {
        checked(a); checked(b)
        val g = gcd(a.denominator, b.denominator)
        val left = product(a.numerator, quotient(b.denominator, g))
        val right = product(b.numerator, quotient(a.denominator, g))
        consume(budget.charge(SelectionQueryResource.WORK_UNITS,
            maxOf(limbs(bits(left)), limbs(bits(right)))))
        return signOf(left.compareTo(right))
    }
    fun point(x: Rational, y: Rational): Point {
        checked(x); checked(y); reserve(32); return ExactPoint(x, y)
    }
    fun point(p: DuPoint): Point = point(fromDouble(p.x), fromDouble(p.y))
    fun compare(a: Point, b: Point): SelectionRobustPredicates.Sign {
        val x = compare(a.x, b.x)
        return if (x == SelectionRobustPredicates.Sign.ZERO) compare(a.y, b.y) else x
    }
    fun vector(a: Point, b: Point): Point = point(subtract(a.x, b.x), subtract(a.y, b.y))
    fun cross(a: Point, b: Point): Rational = subtract(multiply(a.x, b.y), multiply(a.y, b.x))
    /**
     * Admit one attempted atomic lasso fragment before allocating its record. Representation
     * includes the fragment and list growth/copy slots; it remains charged until arena disposal.
     */
    internal fun chargeAtomicLassoEdgeRecord() {
        active()
        consume(budget.charge(SelectionQueryResource.ARRANGEMENT_EDGES))
        consume(budget.charge(SelectionQueryResource.WORK_UNITS))
        retainRecord(160)
    }

    /** Meter endpoint visits, record preparation and ordering in coincident lasso construction. */
    internal fun chargeCoincidentLassoWork(units: Long = 1) {
        active()
        consume(budget.charge(SelectionQueryResource.WORK_UNITS, units))
    }

    /** Additional retained geometry group; atomic occurrence quota is never reset or reused. */
    internal fun chargeCoincidentLassoEdgeGroup() {
        active()
        consume(budget.charge(SelectionQueryResource.ARRANGEMENT_EDGES))
        chargeCoincidentLassoWork()
        retainRecord(160) // group, occurrence-list container and output slots
    }

    internal fun retainRecord(bytes: Long) { active(); reserve(bytes) }
    private fun signOf(n: Int) = when {
        n < 0 -> SelectionRobustPredicates.Sign.NEGATIVE
        n > 0 -> SelectionRobustPredicates.Sign.POSITIVE
        else -> SelectionRobustPredicates.Sign.ZERO
    }
}
