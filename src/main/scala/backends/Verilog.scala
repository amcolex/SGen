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

package backends
import ir.rtl.*
import ir.rtl.hardwaretype.*
import transforms.Transform

import scala.collection.immutable.HashMap

// import scala.collection.{immutable, mutable}
import scala.annotation.tailrec

/**
 * Adds a Verilog backend to modules.
 */
object Verilog {
  extension (mod:Module)
    /**
     * @return a string containing the verilog code of the module
     */
    def toVerilog: String =
      val delayRamThreshold = 64

      def addrBitsFor(depth: Int): Int =
        math.ceil(math.log(depth.toDouble) / math.log(2.0)).toInt.max(1)

      // Detect large all-constant Mux nodes (twiddle ROMs) that can use BRAM
      val romThreshold = 64
      def isConstRom(m: Mux): Boolean =
        m.inputs.size > romThreshold && m.inputs.forall(_.isInstanceOf[Const])

      val constRomSet: Set[Component] = mod.components.collect {
        case m: Mux if m.address.size > 1 && isConstRom(m) => m: Component
      }.toSet

      // Merge deep registers by cycles value into wide memory arrays
      val mergedGroupData: Map[Int, Seq[(Component, Component, Int, Int)]] = {
        val deepRegs = mod.components.collect {
          case cur@Register(input, cycles) if cycles > delayRamThreshold => (cycles, cur, input)
        }
        deepRegs.groupBy(_._1).map { case (cycles, entries) =>
          var offset = 0
          val withOffsets = entries.map { case (_, comp, input) =>
            val o = offset
            offset += comp.size
            (comp, input, comp.size, o)
          }
          cycles -> withOffsets
        }
      }

      val mergedLookup: Map[Component, (Int, Int)] = mergedGroupData.flatMap {
        case (cycles, entries) => entries.map { case (comp, _, _, offset) => comp -> (cycles, offset) }
      }

      val mergedTotalWidth: Map[Int, Int] = mergedGroupData.map {
        case (cycles, entries) => cycles -> entries.map(_._3).sum
      }

      // Get IDs for each regular RTL nodes. An RTL node may use several ids.
      val indexes = HashMap.from(mod.components.zip(mod.components.map {
        case _: Input | _: Output | _: Wire | _: Const => 0
        case Register(_, cycles) if cycles > delayRamThreshold => 1
        case Register(_, cycles) if cycles > 1 => 2
        case RAM(_, _, _) => 2
        case _ => 1
      }.scanLeft(1)(_ + _)))

      // Returns a verilog identifier for each of the nodes
      @tailrec
      def getName(comp: Component, internal: Int = 0): String = comp match
        case Input(_,name) => name
        case Output(_,name) => name
        case Wire(input) => getName(input)
        case Const(size, value) => s"$size'd$value"
        case _ => s"s${indexes(comp) + internal}"

      // Merged memory declarations: depth-1 BRAM + pipeline register for timing
      val mergedDecls = mergedGroupData.toSeq.sortBy(_._1).map { case (cycles, _) =>
        val tw = mergedTotalWidth(cycles)
        val ramDepth = cycles - 1
        val ab = addrBitsFor(ramDepth)
        val twStr = if (tw > 1) s"[${tw - 1}:0] " else ""
        val abStr = if (ab > 1) s"[${ab - 1}:0] " else ""
        s"  reg ${twStr}dly_mem_$cycles [${ramDepth - 1}:0]; // synthesis attribute ram_style of dly_mem_$cycles is block\n" +
        s"  reg ${twStr}dly_rdata_$cycles;\n" +
        s"  reg ${twStr}dly_rdata_${cycles}_q;\n" +
        s"  reg ${abStr}dly_wr_$cycles;\n" +
        s"  wire ${abStr}dly_rd_$cycles = (dly_wr_$cycles == $ab'd${ramDepth - 1}) ? $ab'd0 : dly_wr_$cycles + $ab'd1;\n"
      }.mkString("")

      // Const-ROM Mux nodes that are consumed by a Register(mux, 1)
      val constRomWithReg: Map[Component, Component] = mod.components.collect {
        case cur@Register(m: Mux, cycles) if cycles == 1 && constRomSet.contains(m) => (m: Component) -> (cur: Component)
      }.toMap

      // Const-ROM declarations: BRAM arrays with initial values
      val constRomDecls = mod.components.collect {
        case m@Mux(address, inputs) if constRomWithReg.contains(m) =>
          val name = getName(m)
          val depth = inputs.size
          val w = m.size
          val wStr = if (w > 1) s"[${w - 1}:0] " else ""
          val initLines = inputs.zipWithIndex.map { case (Const(sz, v), i) => s"    ${name}_rom[$i] = $sz'd$v;" case _ => "" }.mkString("\n")
          s"  reg ${wStr}${name}_rom [${depth - 1}:0] /* synthesis syn_romstyle = \"block_ram\" */;\n" +
          s"  initial begin\n$initLines\n  end\n"
      }.mkString("")

      // Registers that read directly from a const-ROM (BRAM read replaces both)
      val constRomReaders: Set[Component] = mod.components.collect {
        case cur@Register(m: Mux, cycles) if cycles == 1 && constRomSet.contains(m) => cur: Component
      }.toSet

      val declarations = (mod.components.flatMap {
        case _: Output | _: Input | _: Const | _: Wire => Seq()
        case cur: Mux if constRomWithReg.contains(cur) => Seq()
        case cur@Mux(address, inputs) if address.size > 1 => Seq(s"reg ${if (cur.size != 1) s"[${cur.size - 1}:0] " else ""}${getName(cur)};")
        case cur@Register(_, cycles) if cycles == 1 => Seq(s"reg ${if (cur.size != 1) s"[${cur.size - 1}:0] " else ""}${getName(cur)};")
        case cur@Register(_, cycles) if cycles == 2 => Seq(
          s"reg ${if (cur.size != 1) s"[${cur.size - 1}:0] " else ""}${getName(cur, 1)};",
          s"reg ${if (cur.size != 1) s"[${cur.size - 1}:0] " else ""}${getName(cur)};")
        case cur@Register(_, cycles) if cycles > delayRamThreshold =>
          Seq(s"wire ${if (cur.size != 1) s"[${cur.size - 1}:0] " else ""}${getName(cur)};")
        case cur@Register(_, cycles) => Seq(
          s"reg ${if (cur.size != 1) s"[${cur.size - 1}:0] " else ""}${getName(cur, 1)} [${cycles - 1}:0];",
          s"wire ${if (cur.size != 1) s"[${cur.size - 1}:0] " else ""}${getName(cur)};")
        case cur@RAM(data, wr, rd) => Seq(
          s"reg ${if (cur.size != 1) s"[${cur.size - 1}:0] " else ""}${getName(cur, 1)} [${(1 << wr.size) - 1}:0]; // synthesis attribute ram_style of ${getName(cur, 1)} is block",
          s"reg ${if (cur.size != 1) s"[${cur.size - 1}:0] " else ""}${getName(cur)};")
        case cur => Seq(s"wire ${if (cur.size != 1) s"[${cur.size - 1}:0] " else ""}${getName(cur)};")
      }:+"integer i;").map(s => s"  $s\n").mkString("")

      val assignments = mod.components.flatMap(cur => (cur match
        case Output(input, _) => Some(getName(input))
        case Plus(terms) => Some(terms.map(getName(_)).mkString(" + "))
        case Minus(lhs, rhs) => Some(s"${getName(lhs)} - ${getName(rhs)}")
        case Times(lhs, rhs) => Some(s"$$signed(${getName(lhs)}) * $$signed(${getName(rhs)})")
        case And(terms) => Some(terms.map(getName(_)).mkString(" & "))
        case Xor(inputs) => Some(inputs.map(getName(_)).mkString(" ^ "))
        case Or(inputs) => Some(inputs.map(getName(_)).mkString(" | "))
        case Equals(lhs, rhs) => Some(s"${getName(lhs)} == ${getName(rhs)}")
        case Not(input) => Some(s"~${getName(input)}")
        case Concat(inputs) => Some(inputs.map(getName(_)).mkString("{",", ","}"))
        case Tap(input, range) => Some(s"${getName(input)}[${if (range.size > 1) s"${range.last}:" else ""}${range.start}]")
        case Register(input, cycles) if cycles > 2 && cycles <= delayRamThreshold => Some(s"${getName(cur,1)} [${cycles - 1}]")
        case Register(_, cycles) if cycles > delayRamThreshold =>
          val (c, offset) = mergedLookup(cur)
          val tw = mergedTotalWidth(c)
          if (cur.size == tw) Some(s"dly_rdata_${c}_q")
          else if (cur.size > 1) Some(s"dly_rdata_${c}_q[${offset + cur.size - 1}:$offset]")
          else Some(s"dly_rdata_${c}_q[$offset]")
        case Mux(address, inputs) if address.size == 1 => Some(s"${getName(address)} ? ${getName(inputs.last)} : ${getName(inputs.head)}")
        case _ => None
      ).map((cur, _))).map((cur, rhs) => s"  assign ${getName(cur)} = $rhs;\n").mkString("")

      // Merged memory read/write with pipeline register (depth-1 BRAM + 1 pipeline = original delay)
      val mergedSeq = mergedGroupData.toSeq.sortBy(_._1).map { case (cycles, entries) =>
        val ramDepth = cycles - 1
        val ab = addrBitsFor(ramDepth)
        val writeDataParts = entries.reverse.map { case (_, input, _, _) => getName(input) }
        val writeData = if (writeDataParts.size == 1) writeDataParts.head else writeDataParts.mkString("{", ", ", "}")
        s"      dly_rdata_$cycles <= dly_mem_$cycles[dly_rd_$cycles];\n" +
        s"      dly_rdata_${cycles}_q <= dly_rdata_$cycles;\n" +
        s"      dly_mem_$cycles[dly_wr_$cycles] <= $writeData;\n" +
        s"      dly_wr_$cycles <= (dly_wr_$cycles == $ab'd${ramDepth - 1}) ? $ab'd0 : dly_wr_$cycles + $ab'd1;\n"
      }.mkString("")

      val sequential = mod.components.flatMap {
        case cur@Register(m: Mux, cycles) if cycles == 1 && constRomWithReg.contains(m) =>
          Seq(s"${getName(cur)} <= ${getName(m)}_rom[${getName(m.address)}];")
        case cur@Register(input, cycles) if cycles == 1 => Seq(s"${getName(cur)} <= ${getName(input)};")
        case cur@Register(input, cycles) if cycles == 2 => Seq(
          s"${getName(cur, 1)} <= ${getName(input)};",
          s"${getName(cur)} <= ${getName(cur, 1)};")
        case cur@Register(input, cycles) if cycles > delayRamThreshold => Seq()
        case cur@Register(input, cycles) => Seq(
          s"${getName(cur, 1)} [0] <= ${getName(input)};",
          s"for (i = 1; i < $cycles; i = i + 1)",
          s"  ${getName(cur, 1)} [i] <= ${getName(cur, 1)} [i - 1];")
        case cur@RAM(data, wr, rd) => Seq(
          s"${getName(cur, 1)} [${getName(wr)}] <= ${getName(data)};",
          s"${getName(cur)} <= ${getName(cur, 1)} [${getName(rd)}];")
        case _ => Seq()
      }.map(s => s"      $s\n").mkString("") + mergedSeq

      val combinatorial = mod.components.flatMap {
        case cur: Mux if constRomWithReg.contains(cur) => Seq()
        case cur@Mux(address, inputs) if address.size > 1 =>
          "always @(*)" +:
            s"  case(${getName(address)})" +:
            inputs.zipWithIndex.map((in, i) => s"    ${if (i == inputs.size - 1 && ((1 << address.size) != inputs.size)) "default" else i}: ${getName(cur)} = ${getName(in)};") :+
            "  endcase"
        case cur: Extern =>
          mod.dependencies.add(cur.filename)
          Seq(s"${cur.module} ext_${getName(cur)}(${cur.inputs.map { case (name, comp) => s".$name(${getName(comp)}), " }.mkString}.${cur.outputName}(${getName(cur)}));")
        case _ => Seq()
      }.map(s => s"  $s\n").mkString("")

      val result = new StringBuilder
      result ++= s"module main(input clk,\n"
      result ++= mod.inputs.map(s => s"  input ${if (s.size != 1) s"[${s.size - 1}:0] " else ""}${getName(s)},\n").mkString("")
      result ++= mod.outputs.map(s => s"  output ${if (s.size != 1) s"[${s.size - 1}:0] " else ""}${getName(s)}").mkString(",\n")
      result ++= ");\n\n"
      result ++= declarations
      result ++= mergedDecls
      result ++= constRomDecls
      result ++= assignments
      result ++= combinatorial
      if sequential.nonEmpty then
        result ++= "  always @(posedge clk)\n"
        result ++= "    begin\n"
        result ++= sequential
        result ++= "    end\n"
      result ++= "endmodule\n"
      result.toString()


  extension [U](sm:StreamingModule[U]) {
    def getTestBench(transform: Transform[U]): String =
      val (testInputs, epsilon) = transform.testParams.applyOrElse(sm.hw,_ => throw Exception(s"Testbench is unavailable for ${sm.hw}."))
      getTestBench(testInputs, s"${transform} streaming with k=${sm.k} using ${sm.hw}", epsilon)

    /**
     * @param repeat Number of datasets that will be tested
     * @param addedGap Number of cycles to add between datasets, in addition to the gap required by the design
     * @returns A verilog testbench of the design
     */
    def getTestBench(inputs: Seq[U], description: String = "", epsilon: Double = 0, addedGap: Int = 0): String = {
      require(inputs.size % sm.N == 0)
      //val input = if(inputs.isEmpty) sm.testBenchInput(2) else inputs.map(sm.hw.bitsOf)
      val repeat = inputs.size / sm.N
      //val input = Vector.tabulate(repeat)(set => Vector.tabulate[Int](N)(i => i * 100 + set * 1000))
      //val input = Vector.tabulate(repeat)(set => Vector.tabulate[BigInt](N)(i => 0))


      val res = new StringBuilder
      res ++= "module test;\n"
      res ++= "    reg clk,rst,next;\n"
      sm.dataInputs.foreach(res ++= "    reg [" ++= (sm.hw.size - 1).toString ++= ":0] " ++= _.name ++= ";\n")
      res ++= "    wire next_out;\n"
      sm.dataOutputs.foreach(res ++= "    wire [" ++= (sm.hw.size - 1).toString ++= ":0] " ++= _.name ++= ";\n")
      res ++= "\n"
      res ++= " //Clock\n"
      res ++= "    always\n"
      res ++= "      begin\n"
      res ++= "        clk <= 0;#50;\n"
      res ++= "        clk <= 1;#50;\n"
      res ++= "      end\n"
      res ++= "\n"
      res ++= "//inputs\n"
      res ++= "    initial\n"
      res ++= "      begin\n"
      res ++= "        @(posedge clk);\n"
      res ++= "        next <= 0;\n"
      (0 to (sm.latency - sm.nextAt + sm.T)).foreach(_ => res ++= "        @(posedge clk);\n")
      res ++= "        rst <= 1;\n"
      res ++= "        @(posedge clk);\n"
      res ++= "        @(posedge clk);\n"
      res ++= "        rst <= 0;\n"
      (Math.min(sm.nextAt, 0) until Math.max((sm.T + sm.minGap + addedGap) * repeat, (sm.T + sm.minGap + addedGap) * (repeat - 1) + sm.nextAt + 4)).foreach(cycle => {
        res ++= "        @(posedge clk); //cycle " ++= cycle.toString ++= "\n"
        if ((cycle - sm.nextAt) >= 0 && (cycle - sm.nextAt) % (sm.T + sm.minGap + addedGap) == 0 && (cycle - sm.nextAt) / (sm.T + sm.minGap + addedGap) < repeat)
          res ++= "        next <= 1;\n"
        if ((cycle - sm.nextAt + 1) >= 0 && (cycle - sm.nextAt) % (sm.T + sm.minGap + addedGap) == 1 && (cycle - sm.nextAt) / (sm.T + sm.minGap + addedGap) < repeat)
          res ++= "        next <= 0;\n"
        val set = cycle / (sm.T + sm.minGap + addedGap)
        val c = cycle % (sm.T + sm.minGap + addedGap)
        if (set < repeat && cycle >= 0 && c < sm.T) {
          if (c == 0)
            res ++= "        //dataset " + set + " enters.\n"
          sm.dataInputs.zipWithIndex.foreach(i => res ++= "        " ++= i._1.name ++= " <= " ++= sm.hw.size.toString ++= "'d" ++= sm.hw.bitsOf(inputs(set * sm.N + c * sm.K + i._2)).toString ++= "; //" ++= inputs(set * sm.N + c * sm.K + i._2).toString ++= "\n")
        }
      })

      res ++= "      end\n"
      res ++= s"    reg [${sm.hw.size/2-1}:0] tmp; // used to display results.\n"
      res ++= "    real tmpr; // used to display results.\n"
      res ++= "    real epsilon; // used to display results.\n"
      res ++= "    initial\n"
      res ++= "      begin\n"
      if description!="" then
        res ++= s"       $$display(\"Testing $description...\");\n"
      res ++= s"        $$display(\"Epsilon: $epsilon\");\n"
      res ++= "        @(posedge next_out);//#100;\n"
      res ++= "        #50;\n"
      val outputs = inputs.grouped(sm.N).toSeq.zipWithIndex.flatMap { case (input, set) => sm.spl.eval(input, set) }
      for r <- 0 until repeat do
        for c <- 0 until sm.T do
          for i <- 0 until sm.K do
            res ++= s"        $$write(\"output${r * sm.T * sm.K + c * sm.K + i}: %0d (\",${sm.dataOutputs(i).name});\n"
            res ++= s"        ${displayInVerilog(sm.hw, sm.dataOutputs(i).name)}\n"
            res ++= s"        $$display(\") expected: ${sm.hw.bitsOf(outputs(r * sm.N + c * sm.K + i)).toString} (${outputs(r * sm.N + c * sm.K + i)})\");\n"
            res ++= s"        ${callFinishIfDifferent(sm.hw, sm.dataOutputs(i).name, outputs(r * sm.N + c * sm.K + i), epsilon)}\n"
          res ++= s"        #100;\n"
        res ++= s"        #${100 * (sm.minGap + addedGap)}; //gap\n"
      res ++= "        $display(\"Success.\");\n"
      res ++= "        $finish();\n"
      res ++= "      end\n"
      res ++= "      main uut(clk,rst,next," ++= (0 until sm.K).map(i => sm.dataInputs(i).name).mkString(",") ++= ",next_out," ++= (0 until sm.K).map(i => sm.dataOutputs(i).name).mkString(",") ++= ");\n"
      res ++= "endmodule\n"
      res.toString
    }
  }

  private def displayInVerilog(hw: HW[?], varName: String): String = hw match
    case ComplexHW(hw) if !hw.isInstanceOf[ComplexHW[?]] => s"tmp=$varName[${hw.size - 1}:0]; ${displayInVerilog(hw, "tmp")} $$write(\" + \"); tmp=$varName[${2 * hw.size - 1}:${hw.size}]; ${displayInVerilog(hw, "tmp")} $$write(\"i\");"
    case FixedPoint(_, fractional, _) => s"$$write(\"%0f\",$$itor($$signed($varName))/${1 << fractional});"
    case Flopoco(wE, wF) if wE <= 11 && wF <= 52 => s"if($varName[${wE+wF}]) $$write(\"-\"); if($varName[${wE+wF+2}:${wE+wF+1}] == 0) $$write(\"0\"); else if($varName[${wE+wF+2}:${wE+wF+1}] == 2) $$write(\"infinity\"); else if($varName[${wE+wF+2}:${wE+wF+1}] == 3) $$write(\"NaN\"); else $$write(\"%0f\", $$bitstoreal({1'd0,11'd${(1<<10)-(1<<(wE-1))}+$varName[${wE+wF-1}:$wF],$varName[${wF-1}:0]${if wF<52 then s",${52-wF}'d0" else ""}}));"
    case IEEE754(wE, wF) if wE <= 11 && wF <= 52 => s"$$write(\"%0f\", $$bitstoreal({$varName[${wE+wF}],$varName[${wE+wF-1}:$wF] == 0 ? 11'd0 : $varName[${wE+wF-1}:$wF] == ${(1<<wE) - 1} ? 11'd2047 : 11'd${(1<<10)-(1<<(wE-1))} + $varName[${wE+wF-1}:$wF], $varName[${wF-1}:0]${if wF<52 then s", ${52-wF}'d0" else ""}}));"
    case _ => s"$$write(\"%0d\", $varName);"

  private def callFinishIfDifferent[T](hw:HW[T], varName: String, value: T, epsilon: Double): String = hw match
    case Flopoco(wE, wF) if wE <= 11 && wF <= 52 => s"tmpr = $varName[${wE+wF+2}:${wE+wF+1}] == 0?0:$$bitstoreal({$varName[${wE+wF}],11'd${(1<<10)-(1<<(wE-1))}+$varName[${wE+wF-1}:$wF],$varName[${wF-1}:0]${if wF<52 then s",${52-wF}'d0" else ""}}) - $$bitstoreal(64'h${java.lang.Long.toHexString(java.lang.Double.doubleToLongBits(value))}); epsilon = $$bitstoreal(64'h${java.lang.Long.toHexString(java.lang.Double.doubleToLongBits(epsilon))}); $$display(\"tmpr: %0f epsilon: %0f\",tmpr, epsilon); if(tmpr > epsilon || -tmpr > epsilon) $$finish;"
    case FixedPoint(magnitude, fractional, _) =>  s"if(($$signed($varName) > ${hw.size}'sd${hw.bitsOf(value)} ? $$signed($varName) - ${hw.size}'sd${hw.bitsOf(value)} : ${hw.size}'sd${hw.bitsOf(value)} - $$signed($varName)) > ${hw.size}'sd${hw.bitsOf(epsilon)}) $$finish;"
    case ComplexHW(hw) => s"tmp = $varName[${hw.size - 1}:0]; ${callFinishIfDifferent(hw,"tmp", value.re, epsilon)} tmp = $varName[${2 * hw.size - 1}:${hw.size}]; ${callFinishIfDifferent(hw,"tmp", value.im, epsilon)}"
    case Unsigned(size) => s"if(($varName > $size'd${hw.bitsOf(value)} ? $varName - $size'd${hw.bitsOf(value)} : $size'd${hw.bitsOf(value)} - $varName) > $size'd${hw.bitsOf(epsilon.toInt)}) $$finish;"
    case IEEE754(wE, wF) if wE <= 11 && wF <= 52 => s"tmpr = $$bitstoreal({$varName[${wE + wF}],$varName[${wE + wF - 1}:$wF] == 0 ? 11'd0 : $varName[${wE + wF - 1}:$wF] == ${(1 << wE) - 1} ? 11'd2047 : 11'd${(1 << 10) - (1 << (wE - 1))} + $varName[${wE + wF - 1}:$wF], $varName[${wF - 1}:0]${if wF<52 then s", ${52 - wF}'d0" else ""}}) - $$bitstoreal(64'h${java.lang.Long.toHexString(java.lang.Double.doubleToLongBits(value))}); epsilon = $$bitstoreal(64'h${java.lang.Long.toHexString(java.lang.Double.doubleToLongBits(epsilon))}); $$display(\"tmpr: %0f epsilon: %0f\",tmpr, epsilon); if(tmpr > epsilon || -tmpr > epsilon) $$finish;"
    case _ => throw Exception(s"Unsupported type $hw for testbench.")
}
