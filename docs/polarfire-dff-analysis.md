# PolarFire DFF Blowup Analysis

Why the SGen compact DFT uses 16K DFFs on PolarFire while only 2.5K FFs on Zynq
UltraScale+ and 3.9K registers on Agilex 3 — and what to do about it.

---

## 1. The Problem At A Glance

| Metric | Zynq US+ (Vivado) | PolarFire SoC (Libero) | Agilex 3 (Quartus) |
|--------|---:|---:|---:|
| **Flip-Flops / DFFs** | **2,540** | **16,085** | **3,857** |
| LUTs / 4LUTs / ALMs | 6,982 | 4,592 | 972 |
| DSP | 8 | 8 | 4 |
| Block RAM | 5 tiles (10× RAMB18) | 4 LSRAM | 11 M20K |
| uSRAM / MLAB | n/a | **0** of 876 | 3 |

The SGen design uses **6.3× more DFFs** on PolarFire than on Zynq UltraScale+.
The Spiral reference design, by contrast, shows only 2,504 SLEs on PolarFire vs
1,261 FFs on Zynq — a modest 2× ratio explained by LSRAM interface overhead.

The root cause is a single architectural feature: **Xilinx SRL (Shift Register LUT)
primitives**, and the absence of any equivalent on PolarFire.

---

## 2. Where The DFFs Come From: SGen's Shift Register Chains

### 2.1 The Generated Verilog Pattern

SGen's Verilog backend (`backends/Verilog.scala`) has exactly two storage
primitives:

1. **`Register(input, cycles)`** — emitted as a DFF chain with a for-loop shift:

```verilog
reg [8:0] s8 [489:0];           // 9-bit × 490-deep array
wire [8:0] s7;
assign s7 = s8[489];            // output tap at chain end

always @(posedge clk) begin
  s8[0] <= s555;                // input at stage 0
  for (i = 1; i < 490; i = i + 1)
    s8[i] <= s8[i - 1];         // shift
end
```

2. **`RAM(data, wr, rd)`** — emitted as addressed dual-port memory with a
   `// synthesis attribute ram_style of ... is block` hint.

There is **no depth threshold** in the Scala code that switches long delays from
shift registers to RAM. The decision is structural: temporal streaming permutations
use `RAM`; everything else (scheduling gaps, token alignment, pipeline
balancing) uses `Register`.

### 2.2 Shift Register Inventory in `dftcompact_2048_4x18.v`

| Category | Count | Width | Depth | DFF-equivalent per instance | Total DFFs |
|----------|------:|------:|------:|----------------------------:|-----------:|
| Deep data delay | 36 | 9-bit | 490 | 4,410 | 158,760 |
| Deep 1-bit control | 11 | 1-bit | 490 | 490 | 5,390 |
| Deep 1-bit control | 1 | 1-bit | 491 | 491 | 491 |
| Deep 1-bit control | 2 | 1-bit | 495 | 495 | 990 |
| Deep 1-bit control | 1 | 1-bit | 494 | 494 | 494 |
| Deep 3-bit control | 1 | 3-bit | 494 | 1,482 | 1,482 |
| Pipeline (data) | 8 | 18-bit | 4 | 72 | 576 |
| Pipeline (data) | 6 | 18-bit | 3 | 54 | 324 |
| Medium 1-bit | 12 | 1-bit | 14 | 14 | 168 |
| Short 1-bit | 11 | 1-bit | 6 | 6 | 66 |
| Short 1-bit | 1 | 1-bit | 8 | 8 | 8 |
| **Explicit RAMs** | **4** | **36-bit** | **512** | **LSRAM** | **0** (RAM) |
| **Subtotal shift register stages** | | | | | **~168,749** |

In addition there are ~250 simple single/double-stage pipeline registers
(the "real" flip-flops).

### 2.3 The Key Insight: 168K Stages ≠ 168K DFFs On Every Target

The naive DFF count of the shift register declarations is ~168K. But synthesis
tools drastically reduce this — the question is *how*:

**Xilinx Vivado** maps shift chains into **SRL primitives** inside CLB LUTs:

| Primitive | Count | Capacity | Stages absorbed |
|-----------|------:|:---------|:----------------|
| SRLC32E | 5,472 | 32-deep × 1-bit per LUT | ~175K max |
| SRL16E | 168 | 16-deep × 1-bit per LUT | ~2.7K max |
| **Total SRL LUTs** | **5,640** | | **~168K stages** |
| FDRE/FDSE | 2,540 | 1 FF each | Pipeline/control |
| RAMB18E2 | 10 | Block RAM | Explicit RAMs |

On Xilinx, **every LUT** can function as a 16- or 32-deep shift register at
zero additional cost. A 490-deep × 9-bit shift register packs into
9 × ⌈490/32⌉ = 144 SRL-LUTs. The 5,640 SRL LUTs absorb virtually all shift
register stages, leaving only 2,540 real flip-flops.

**Intel Quartus** on Agilex 3 auto-converts large shift register arrays into
**M20K block RAMs**:

| Resource | Count | Notes |
|----------|------:|-------|
| M20K blocks | 11 | Includes 4 explicit RAMs + auto-converted SR chains |
| MLAB | 3 | Shallow shift register absorption (s117, s523, s538) |
| Registers | 3,857 | Pipeline/control + residual short chains |

Quartus's "Shift Register to RAM Conversion" feature is **on by default**. It
detected the deep chains and packed them into M20K alongside the explicit RAMs.
The 3 MLAB instances absorb a few shorter chains (14-deep × 1-bit). The
remaining 3,857 registers are genuine pipeline/control flip-flops plus some
short chains that didn't merit conversion.

**Synplify Pro on PolarFire** recognizes the shift chains but **does not convert
them**:

```
@N: CL135 | Found sequential shift s41 with address depth of 490 words and data bit width of 9.
@N: CL135 | Found sequential shift s43 with address depth of 490 words and data bit width of 9.
... (dozens more)
```

Synplify finds the patterns during the compiler phase (CL135 messages), but with
`-seqshift_to_uram` **disabled** (the default), it leaves them as DFF chains.
The mapper then applies standard optimizations (dead-code removal, register
merging) that bring the raw ~168K down to the final **15,653 SLEs** — still a
catastrophic 6× blowup over the Xilinx baseline.

---

## 3. PolarFire Memory Architecture: What's Available

PolarFire has two types of embedded memory that could absorb shift registers:

### 3.1 uSRAM (RAM64x12)

| Parameter | Value |
|-----------|-------|
| Primitive | `RAM64x12` |
| Capacity | 64 words × 12 bits = 768 bits |
| Ports | 1 write + 1 read (synchronous write, async or sync read) |
| Location | One per math/logic cluster — physically close to fabric |
| Availability (MPFS095T) | **876 blocks** |
| Currently used | **0** |

Aspect ratio modes: 64×12 (native), 128×6, 256×3, 512×1.

For a 490-deep shift register: ⌈490/64⌉ = 8 uSRAM blocks per bit slice.
A 490×9 shift register → 8 × 1 = 8 uSRAMs (since 9 < 12, one uSRAM per
depth-slice covers all bits).

**Cost of mapping all 36 × 490×9 chains to uSRAM:**
36 × 8 = 288 uSRAMs (33% of 876). This is feasible but expensive in uSRAM.

### 3.2 LSRAM (RAM1K20)

| Parameter | Value |
|-----------|-------|
| Primitive | `RAM1K20` |
| Capacity | 20,480 bits |
| Ports | True dual-port |
| Location | Dedicated RAM columns |
| Availability (MPFS095T) | **308 blocks** |
| Currently used | **4** (explicit RAMs only) |

Supported configurations: 512×20, 1K×10, 2K×5, 4K×2(+parity), 8K×1.

For a 490-deep × 9-bit shift register: one RAM1K20 in 512×10 mode holds
the entire delay line with capacity to spare.

**Cost of mapping all deep shift register chains to LSRAM:**

| Chain type | Count | LSRAM per chain | Total LSRAM |
|------------|------:|---------:|------:|
| 490×9 data | 36 | 1 | 36 |
| ~490×1 control | 15 | 1 per ~10 chains (pack at 512×1 × 10 bits) | 2 |
| 494×3 control | 1 | 1 | 1 |
| **Total** | | | **~39** |

Total LSRAM: 4 (existing) + 39 (converted) = **43 of 308** (14%).
This is entirely feasible and the most efficient option.

### 3.3 Comparison with Xilinx SRL and Intel MLAB

| Feature | Xilinx SRL | Intel MLAB | PolarFire uSRAM |
|---------|:-----------|:-----------|:----------------|
| Basic unit | 32-deep × 1-bit (inside LUT) | 640 bits (in LAB) | 64-deep × 12-bit (dedicated block) |
| Native shift register mode | **Yes** — LUT IS the shift register | Yes (altshift_taps) | **No** — must use as addressed RAM |
| Dynamic length | Yes (addressable tap) | Limited | No (counter logic needed) |
| Cascade for depth | Dedicated cascade output | Manual | Manual (external address logic) |
| Inference | Automatic — always on | Automatic | **Off by default** (`-seqshift_to_uram`) |
| Cost for 490×9 SR | 144 LUTs (essentially free) | ~7 MLABs or 1 M20K | 8 uSRAMs or **1 LSRAM** |

**The fundamental problem:** PolarFire LUTs are pure combinational 4-input
lookup tables paired with a single DFF. They **cannot** function as shift
registers. Unlike Xilinx where every LUT has an internal flip-flop chain that
can be repurposed as a 16/32-deep SRL at zero cost, PolarFire requires explicit
memory blocks (uSRAM or LSRAM) with address-counter logic.

---

## 4. Why The Spiral Design Doesn't Have This Problem

The Spiral reference core (`spiral_dft_it_4in_2048_16bit_scaled.v`) uses a
fundamentally different architecture for its delay lines:

| Metric | SGen | Spiral |
|--------|-----:|-------:|
| PolarFire SLEs (DFF) | 15,653 | 2,504 |
| PolarFire LSRAM | 4 | 16 |
| Vivado FFs | 2,540 | 1,261 |
| Vivado SRL LUTs | 5,640 | 129 |
| Vivado BRAM tiles | 5 | 10 |

Spiral uses **addressed RAM patterns** (write-pointer / read-pointer circular
buffers) for its delay lines, which every synthesis tool naturally maps to block
RAM. SGen uses **shift register chains** which only Xilinx absorbs for free.

The Spiral approach trades **more BRAM** (10 RAMB36E2 on Vivado, 16 LSRAM on
PolarFire) for **far fewer DFFs**. On PolarFire where BRAM is relatively
abundant and SRL doesn't exist, Spiral's approach is vastly superior.

---

## 5. The SGen Scala Code Path

The decision tree in SGen's codebase:

```
DFT.compact → ItPeaseFused → ItProduct → LinearPerm.stream
                                           ↓
                                    Spatial × Temporal × Spatial
                                              ↓
                                    ┌─────────┴──────────┐
                                    │  Small form?        │
                                    │  → SmallTemporal    │
                                    │    (DoubleShiftReg) │
                                    │                     │
                                    │  Otherwise          │
                                    │  → DualControlRAM   │
                                    │    (→ ir.rtl.RAM)   │
                                    └─────────────────────┘

Component.delay(cycles)  →  Register(this, cycles)  →  ALWAYS a shift chain
StreamingModule token alignment  →  Register(prev, diff)  →  shift chain
AcyclicStreamingModule scheduling  →  delay(time)  →  shift chain
```

Key observations:
- **Temporal permutations** → `RAM` (explicit dual-port addressed memory)
- **All other delays** → `Register` (shift register chain)
- There is **no configurable threshold** to switch long `Register` delays to RAM
- The `Component.delay(cycles)` method unconditionally creates `Register(this, cycles)` regardless of depth
- The Verilog backend (`Verilog.scala` lines 68-70, 99-102) unconditionally
  emits the `for`-loop shift pattern for `cycles > 2`

---

## 6. Synplify Shift Register Handling: What Happened

From the synthesis logs (`synthesis/synlog/main_fpga_mapper.srr`):

1. **Compiler phase** recognized all shift register chains (CL135 messages)
2. **Mapper** mapped 4 explicit RAMs to RAM1K20 (FX107 warnings about
   read/write conflict)
3. **Mapper** applied dead-code removal (BN362: "Removing sequential instance
   ... because it does not drive other instances") — removed some unused stages
4. **Mapper** merged equivalent registers (BN132: "Removing sequential instance
   ... because it is equivalent to ...")
5. **Result**: 15,653 SLEs, 4 LSRAM, **0 uSRAM**

The mapper's optimizations (dead-code removal, register merging) significantly
reduce the raw ~168K shift register DFF count down to ~13K DFFs attributed to
shift chains plus ~2.5K real flip-flops. But even with these optimizations, the
result is unacceptable.

---

## 7. Optimization Strategies

### 7.1 Quick Win: Synplify Synthesis Option (No HDL Changes)

Add `-seqshift_to_uram` to the Libero Tcl script:

```tcl
configure_tool -name {SYNTHESIZE} \
  -params {SYNPLIFY_OPTIONS:set_option -seqshift_to_uram 1}
run_tool -name SYNTHESIZE
```

This instructs Synplify to automatically convert recognized sequential shift
registers into uSRAM-based circular buffers. The tool generates the address
counter and read/write logic internally.

**Limitations:**
- uSRAM is only 64 deep — a 490-deep chain needs 8 cascaded uSRAMs
- The tool may decline to convert very deep chains if the cascade cost is too high
- Only works for pure shift registers with a single end-of-chain output tap
- Does **not** target LSRAM (only uSRAM)

**Expected outcome:** Partial DFF reduction. The 490-deep chains may or may not
be converted, depending on the tool's cost model for 8-deep cascades.

### 7.2 Better Quick Win: `syn_srlstyle` Attribute (Minimal HDL Change)

Modify the Verilog emitter to add synthesis attributes on shift register declarations:

```verilog
(* syn_srlstyle = "select_srl" *)
reg [8:0] s8 [489:0];
```

Or for targeting LSRAM (better for 490-deep chains):

```verilog
(* syn_srlstyle = "block_srl" *)
reg [8:0] s8 [489:0];
```

This can be added in `Verilog.scala` at lines 68-70 with a one-line change to
the declaration string. Values:

| Value | Target | Best for |
|-------|--------|----------|
| `"registers"` | DFF chain | Short pipelines (< 4 stages) |
| `"select_srl"` | uSRAM (distributed) | Depths ≤ 64 |
| `"block_srl"` | LSRAM (block RAM) | Depths > 64 |

**Recommended:** Use `"block_srl"` for `cycles > 64`, `"select_srl"` for
`16 < cycles ≤ 64`, and leave as DFFs for `cycles ≤ 16`.

### 7.3 Best Long-Term Fix: Emit RAM-Based Delay Lines in SGen

Modify the Verilog backend to emit a **circular buffer** pattern instead of a
shift chain for deep delays. This is synthesis-tool-agnostic and works on every
FPGA target:

```verilog
// Instead of:
reg [8:0] s8 [489:0];
always @(posedge clk) begin
  s8[0] <= input;
  for (i = 1; i < 490; i = i + 1)
    s8[i] <= s8[i - 1];
end
assign output = s8[489];

// Emit:
reg [8:0] s8_mem [511:0];  // power-of-2 for simple modular addressing
reg [8:0] s8_rd;
reg [8:0] s8_waddr;
always @(posedge clk) begin
  s8_mem[s8_waddr] <= input;
  s8_rd <= s8_mem[s8_waddr];  // reads data written 490 cycles ago
  s8_waddr <= (s8_waddr == 9'd489) ? 9'd0 : s8_waddr + 9'd1;
end
assign output = s8_rd;
```

Every synthesis tool (Synplify, Vivado, Quartus) will automatically infer this
as a block RAM — no attributes needed.

**Implementation in Scala:** Add a depth threshold (e.g., `cycles > 64`) to
`Verilog.scala` and emit the circular-buffer pattern for deep delays. The
threshold can be made configurable via a command-line flag.

**Impact on correctness:** The circular-buffer pattern is functionally identical
to a shift chain — it produces the same output delayed by `cycles` clock edges.
Existing testbenches (`sbt SimTest/test`) would validate the change.

### 7.4 Most Aggressive: Modify SGen IR to Use RAM for Deep Delays

Change `Component.delay(cycles)` to emit `RAM` nodes instead of `Register` nodes
when `cycles` exceeds a threshold. This would be the cleanest solution but
requires changes deeper in the IR:

```scala
final def delay(cycles: Int) =
  require(cycles >= 0)
  if cycles == 0 then this
  else if cycles <= DELAY_RAM_THRESHOLD then Register(this, cycles)
  else DelayRAM(this, cycles)  // new node type
```

This is the most work but produces the most portable Verilog.

---

## 8. Projected Resource Usage After Optimization

### 8.1 Strategy: Map 490-deep chains to LSRAM (`block_srl` or circular buffer)

| Resource | Current | Projected | Change |
|----------|--------:|----------:|-------:|
| DFF / SLE | 16,085 | ~3,000–3,500 | **-75% to -80%** |
| LSRAM | 4 | ~43 | +39 (14% of 308) |
| uSRAM | 0 | 0 | — |
| LUTs | 4,592 | ~4,200 | Slight reduction (fewer SR LUTs, some counter logic) |
| DSP | 8 | 8 | — |

The projected DFF count of ~3,000–3,500 would be comparable to the Agilex 3
result (3,857 registers) and within 1.5× of Vivado (2,540 FFs).

The LSRAM increase from 4 to ~43 uses only 14% of the MPFS095T's 308 LSRAM
blocks — well within budget.

### 8.2 Strategy: Map 490-deep chains to uSRAM (`select_srl` or `-seqshift_to_uram`)

| Resource | Current | Projected | Change |
|----------|--------:|----------:|-------:|
| DFF / SLE | 16,085 | ~3,000–3,500 | **-75% to -80%** |
| LSRAM | 4 | 4 | — |
| uSRAM | 0 | ~288 | +288 (33% of 876) |
| LUTs | 4,592 | ~4,600 | Counter logic adds some LUTs |

This uses 33% of uSRAM — more resource-hungry than LSRAM but preserves LSRAM
for other uses.

### 8.3 Comparison with Spiral Reference (Target)

| Resource | SGen current | SGen projected (LSRAM) | Spiral |
|----------|-------------:|-----------------------:|-------:|
| DFF / SLE | 16,085 | ~3,200 | 3,368 |
| LSRAM | 4 | ~43 | 16 |
| uSRAM | 0 | 0 | 0 |
| LUTs | 4,592 | ~4,200 | 4,871 |
| DSP | 8 | 8 | 8 |

After optimization, the SGen design would have **comparable DFF usage** to
Spiral, with moderately higher LSRAM usage (43 vs 16) but **fewer LUTs**
(~4,200 vs 4,871). The SGen architecture uses only 4 LSRAM for the explicit
temporal permutation RAMs — the additional ~39 LSRAM would be for converted
shift register delay lines.

The higher LSRAM count (43 vs 16) reflects SGen's finer-grained delay line
structure: many narrow (9-bit) delay lines vs Spiral's fewer, wider RAM blocks.
Packing multiple narrow delay lines into shared LSRAM blocks (using wider port
modes) could reduce this further.

---

## 9. Recommended Action Plan

### Phase 1: Quick Validation (minutes)

1. Re-run Libero synthesis with `-seqshift_to_uram 1` to see how much Synplify
   can auto-convert
2. Check the resulting uSRAM usage and DFF count
3. This requires only a Tcl change — no HDL or Scala modifications

### Phase 2: Verilog Backend Enhancement (hours)

1. Modify `Verilog.scala` to emit `syn_srlstyle` attributes on shift register
   declarations based on depth thresholds
2. For depths > 64: add `(* syn_srlstyle = "block_srl" *)`
3. For depths 16–64: add `(* syn_srlstyle = "select_srl" *)`
4. Run `sbt SimTest/test` to validate functional correctness
5. Re-synthesize on all three targets and verify resource improvements

### Phase 3: Circular Buffer Emission (days)

1. Add a new emission mode in `Verilog.scala` for `Register` nodes with
   `cycles` above a configurable threshold
2. Emit the circular-buffer RAM pattern instead of the for-loop shift chain
3. This produces the most portable, vendor-agnostic Verilog
4. Full regression with `sbt RegularTest/test` and `sbt SimTest/test`
5. Re-synthesize and update resource comparison tables

### Phase 4: SGen IR Enhancement (optional, longer term)

1. Add a `DelayRAM` IR node type for RAM-based delay lines
2. Modify `Component.delay()` to use it above a threshold
3. This is the cleanest architectural solution but requires the most code changes

---

## 10. Why Agilex 3 Handles It Well

Quartus Pro's **Shift Register to RAM Conversion** feature is enabled by
default and aggressive. From the fitter report, Quartus:

1. Detected the shift register chains
2. Auto-converted deep chains to **M20K block RAMs** (the `s8_rtl_0` etc.
   entries in the RAM summary at 512×20)
3. Converted a few medium-depth chains to **MLAB** (the `s117[0]`, `s523[0]`,
   `s538[0]` entries at 16×11/14)
4. Left only pipeline registers and short chains as fabric registers

This is why Agilex 3 achieves only 3,857 registers and 11 M20K blocks: Quartus
automatically does what we need Synplify to do on PolarFire. The difference is
that **Quartus defaults this optimization to ON**, while **Synplify defaults it
to OFF** on PolarFire.

---

## 11. Key Files Reference

| File | Role |
|------|------|
| `src/main/scala/backends/Verilog.scala` | Verilog emission — lines 68-70 (SR declaration), 99-102 (SR sequential logic) |
| `src/main/scala/ir/rtl/Component.scala` | `delay()` method — line 42-47 — unconditionally creates `Register` |
| `src/main/scala/ir/rtl/signals/RAM.scala` | `DualControlRAM` / `SingleControlRAM` — the only path to `ir.rtl.RAM` |
| `src/main/scala/transforms/perm/Temporal.scala` | Temporal permutation — decides RAM vs SmallTemporal |
| `designs/generated/sgen/dftcompact_2048_4x18.v` | Generated Verilog with the shift register instances |
| `reports/vivado/vivado_sgen_dftcompact_2048_4x18_util.rpt` | Vivado primitives showing SRL absorption |
| `reports/libero/sgen_dftcompact_2048_4x18_fpga_mapper_resourceusage.rpt` | Synplify mapper showing 15,653 SLEs |
| `reports/quartus/quartus_sgen_dftcompact_2048_4x18_ac135.fit.rpt` | Quartus shift-register-to-RAM conversion details |

---

## 12. Summary

The SGen compact DFT generates an architecture optimized for Xilinx, where shift
register delay lines are essentially free (absorbed into SRL LUTs). On PolarFire,
which lacks SRL primitives, these same delay lines become enormous DFF chains.
The fix is straightforward: either tell Synplify to map them to memory
(`-seqshift_to_uram` or `syn_srlstyle`), or modify SGen to emit
synthesis-tool-agnostic RAM-based delay lines. The projected resource usage after
optimization would bring PolarFire DFF count in line with Agilex 3 and Zynq
UltraScale+, at a cost of ~39 additional LSRAM blocks (14% of available).
