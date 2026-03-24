/*
 *    _____ ______          SGen - A Generator of Streaming Hardware
 *   / ___// ____/__  ____  Department of Computer Science, ETH Zurich, Switzerland
 *   \__ \/ / __/ _ \/ __ \
 *  ___/ / /_/ /  __/ / / / Copyright (C) 2020-2025 François Serre (serref@inf.ethz.ch)
 * /____/\____/\___/_/ /_/  https://github.com/fserre/sgen
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301  USA
 *
 */

package ir.rtl.hardwaretype

import Utils.BigIterator
import ir.rtl.Component
import ir.rtl.signals.{Const, Minus, Plus, Sig, Times}

/**
 * Fixed point arithmetic representation
 *
 * @param magnitude Number of bits of the integer part
 * @param fractional Number of bits of the fractional part
 * @param rounding Rounding mode for fixed-point multiplications:
 *                 0 = truncation (default, cheapest, has systematic negative bias),
 *                 1 = half-LSB rounding (adds constant 2^(shift-1) before truncation,
 *                     eliminates bias, costs 1 adder per multiply),
 *                 2 = jamming / sticky-bit (ORs discarded bits into result LSB,
 *                     nearly eliminates bias, costs only OR gates — no adder/DSP).
 */
case class FixedPoint(magnitude: Int, fractional: Int, rounding: Int = 0) extends HW[Double](magnitude + fractional):
  override def plus(lhs: Sig[Double], rhs: Sig[Double]): Sig[Double] = FixPlus(lhs, rhs)

  override def minus(lhs: Sig[Double], rhs: Sig[Double]): Sig[Double] = FixMinus(lhs, rhs)

  override def times(lhs: Sig[Double], rhs: Sig[Double]): Sig[Double] = FixTimes(lhs, rhs)

  override def bitsOf(const: Double): BigInt = {
    require(const.isFinite)
    if const < 0 then
      val opposite = ((BigInt(1) << fractional).toDouble * BigDecimal(-const)).toBigInt
      if opposite == 0 then
        opposite
      else
        val res = (opposite ^ ((BigInt(1) << size) - 1)) + 1
        if res.bitLength != size then
          throw IllegalArgumentException(s"Overflow during the conversion of ${const} to a ${this}")
          BigInt(1) << (size - 1)
        else
          res
    else
      val res = ((BigInt(1) << fractional).toDouble * BigDecimal(const)).toBigInt
      if res.bitLength >= size then
        throw IllegalArgumentException(s"Overflow during the conversion of ${const} to a ${this}")
        (BigInt(1) << (size - 1)) - 1
      else
        res
  }


  override def valueOf(const: BigInt): Double = {
    require(const.bitLength <= size)
    if const.testBit(size - 1) then
      -((const ^ ((BigInt(1) << size) - 1)) + 1).toDouble / Math.pow(2, fractional)
    else
      const.toDouble / Math.pow(2, fractional)
  }

  override def description: String = if fractional == 0 then s"$magnitude-bits signed integer in two's complement format" else s"signed fixed-point number ($magnitude. $fractional bits representation)"

  private case class FixPlus(override val lhs: Sig[Double], override val rhs: Sig[Double]) extends Plus(lhs, rhs):
    override def pipeline = 1

    override def implement(implicit cp: Sig[?] => Component) = ir.rtl.Plus(Seq(cp(this.lhs), cp(this.rhs)))

  private case class FixMinus(override val lhs: Sig[Double], override val rhs: Sig[Double]) extends Minus(lhs, rhs):
    override def pipeline = 1

    override def implement(implicit cp: Sig[?] => Component) = ir.rtl.Minus(cp(this.lhs), cp(this.rhs))

  private case class FixTimes(override val lhs: Sig[Double], override val rhs: Sig[Double]) extends Times(lhs, rhs):
    override def pipeline = this.rhs match
      case Const(value) if value > 0 && this.rhs.hw.bitsOf(value).bitCount == 1 => 0
      case _ => 3

    override def implement(implicit cp: Sig[?] => Component): Component =
      this.rhs match
        case Const(value) if value > 0 && this.rhs.hw.bitsOf(value).bitCount == 1 =>
          val shift = this.rhs.hw.bitsOf(value).lowestSetBit - this.rhs.hw.asInstanceOf[FixedPoint].fractional
          if shift > 0 then
            ir.rtl.Concat(Seq(ir.rtl.Tap(cp(this.lhs), 0 until (this.lhs.hw.size - shift)),ir.rtl.Const(shift,0)))
          else if shift == 0 then
            cp(this.lhs)
          else
            // Arithmetic right shift: sign-extend with copies of the MSB
            val signBit = ir.rtl.Tap(cp(this.lhs), this.lhs.hw.size - 1 until this.lhs.hw.size)
            ir.rtl.Concat(Seq.fill(-shift)(signBit) :+ ir.rtl.Tap(cp(this.lhs), (-shift) until this.lhs.hw.size))
        case _ =>
          val shift = this.rhs.hw.asInstanceOf[FixedPoint].fractional
          val product = ir.rtl.Times(cp(this.lhs), cp(this.rhs))
          val resultSize = this.lhs.hw.size
          if rounding == 1 && shift > 0 then
            // Half-LSB rounding: add 2^(shift-1) before truncation
            val productSize = resultSize + this.rhs.hw.size
            val roundConst = ir.rtl.Const(productSize, BigInt(1) << (shift - 1))
            ir.rtl.Tap(ir.rtl.Plus(Seq(product, roundConst)), shift until (shift + resultSize))
          else if rounding == 2 && shift > 0 then
            // Jamming / sticky-bit: OR discarded bits into result LSB (no adder/DSP needed)
            val truncated = ir.rtl.Tap(product, shift until (shift + resultSize))
            val discarded = ir.rtl.Tap(product, 0 until shift)
            val sticky = ir.rtl.Not(ir.rtl.Equals(discarded, ir.rtl.Const(shift, 0)))
            val resultLsb = ir.rtl.Tap(truncated, 0 until 1)
            val newLsb = ir.rtl.Or(Seq(resultLsb, sticky))
            val resultMsbs = ir.rtl.Tap(truncated, 1 until resultSize)
            ir.rtl.Concat(Seq(resultMsbs, newLsb))
          else
            ir.rtl.Tap(product, shift until (shift + resultSize))

  override def MID_VALUE: Double = valueOf(BigInt(1) << ((size - 1)/2))

  override def MAX_VALUE: Double = valueOf((BigInt(1) << (size - 1)) - 1)

  override def values: Iterator[Double] = BigIterator(0, BigInt(1)<<size).map(valueOf)

  override def toString: String = (magnitude, fractional) match
    case (8, 0) => "char"
    case (16, 0) => "short"
    case (32, 0) => "int"
    case (64, 0) => "long"
    case _ => s"FixedPoint($magnitude, $fractional)"
