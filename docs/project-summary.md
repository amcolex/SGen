# SGen Project Summary

This document captures the current working baseline for the local `SGen` repo, the generated/reference FFT cores we are comparing, and the reproducible synthesis flows for `PolarFire SoC`, `Zynq UltraScale+`, and `Agilex 3`.

## Current Layout

- `designs/generated/sgen/dftcompact_2048_4x18.v`
  - Current SGen-generated compact DFT baseline.
- `designs/reference/spiral/spiral_dft_it_4in_2048_16bit_scaled.v`
  - Current Spiral reference core used for side-by-side synthesis comparison.
- `scripts/generate/sgen_dftcompact_2048_4x18.sh`
  - Recreates the current SGen baseline core.
- `scripts/synth/libero/*.tcl`
  - PolarFire SoC synthesis flows.
- `scripts/synth/vivado/*.tcl`
  - Vivado out-of-context synthesis flows.
- `scripts/synth/quartus/*.tcl`
  - Quartus Pro compile flows for Agilex 3.
- `reports/libero/`
  - Copied Libero/Synplify reports from the previous runs.
- `reports/vivado/`
  - Vivado utilization and timing reports.
- `reports/quartus/`
  - Quartus copied summaries and fitter reports.

## Current Comparison Baseline

### SGen core

- Generated with:

```bash
./sgen.bat -n 11 -k 2 -dualramcontrol -hw complex signed 18 -o designs/generated/sgen/dftcompact_2048_4x18.v dftcompact
```

- Characteristics:
  - `2048`-point DFT
  - `4` complex samples per cycle
  - compact/folded architecture
  - `18`-bit signed real and imaginary components
  - latency `6130`
  - delay between datasets `5632`
  - new transform every `6144` cycles

### Spiral reference

- File: `designs/reference/spiral/spiral_dft_it_4in_2048_16bit_scaled.v`
- Characteristics from header:
  - `2048`-point DFT
  - `4` complex samples per cycle
  - `16`-bit scaled implementation
  - latency `6149`
  - throughput one transform every `5655` cycles

## Resource Results

These are the current baseline numbers collected so far.

### PolarFire SoC

Target: `MPFS095TL-FCSG325E` via Libero/Synplify in `ubuntu22`

| Design | LUTs | DFF | DSP/Math | BRAM/LSRAM | User I/O | Notes |
|---|---:|---:|---:|---:|---:|---|
| `dftcompact_2048_4x18.v` | 4592 | 16085 | 8 | 4 | 292 | `COMPILE` fails on package I/O limit |
| `spiral_dft_it_4in_2048_16bit_scaled.v` | 4871 | 3368 | 8 | 16 | 260 | `COMPILE` fails on package I/O limit |

Notes:
- The compile-netlist reports use package-limited implementation data, but both designs exceed the `FCSG325` I/O count.
- The detailed copied reports are under `reports/libero/`.

### Zynq UltraScale+

Target: `xczu2cg-sbva484-2-i` via Vivado in `ubuntu22`

| Design | LUTs | FFs | DSPs | BRAM |
|---|---:|---:|---:|---:|
| `dftcompact_2048_4x18.v` | 6982 | 2540 | 8 | 5 BRAM tiles (`10` RAMB18E2) |
| `spiral_dft_it_4in_2048_16bit_scaled.v` | 1343 | 1261 | 8 | 10 BRAM tiles (`10` RAMB36E2) |

Notes:
- Vivado was run as out-of-context synthesis.
- The detailed copied reports are under `reports/vivado/`.

### Agilex 3

Target: `A3CW135BM16AE6S` via Quartus Pro in `ubuntu24`

| Design | ALMs | Registers | DSP Blocks | RAM Blocks | Block Memory Bits |
|---|---:|---:|---:|---:|---:|
| `dftcompact_2048_4x18.v` | 972 | 3857 | 4 | 11 | 155648 |
| `spiral_dft_it_4in_2048_16bit_scaled.v` | 908 | 1951 | 4 | 19 | 308224 |

Notes:
- Quartus uses virtual pins in the scripted flow so fit can complete despite high top-level I/O count.
- No `.sdc` is provided in these batch flows, so Quartus derives a clock and timing numbers are not meaningful yet.
- The detailed copied reports are under `reports/quartus/`.

## Key Comparison Caveats

- This is not yet an apples-to-apples architecture comparison.
- The SGen core is `18-bit`.
- The Spiral reference is `16-bit scaled`.
- Vivado uses out-of-context synthesis.
- Quartus uses virtual pins.
- Libero reaches synthesis/compile-netlist reporting, but package I/O count blocks full compile on the selected package.

For a fairer comparison, a next natural step is to generate a `16-bit` SGen compact DFT with the same `2048`/`4-lane` configuration and rerun the same three flows.

## How To Regenerate The Current SGen Core

From the repo root on the host:

```bash
bash scripts/generate/sgen_dftcompact_2048_4x18.sh
```

This script writes:

- `designs/generated/sgen/dftcompact_2048_4x18.v`

The command works with the packaged `sgen.bat`; a separate standalone Scala install is not required just to run the generator.

## How To Re-run Vivado

Vivado lives in `ubuntu22`.

From the host:

```bash
distrobox enter ubuntu22
source "$HOME/xilinx/Vivado/2023.2/settings64.sh"
REPO=/run/host/var/home/alex/git/SGen
vivado -mode batch -source "$REPO/scripts/synth/vivado/sgen_dftcompact_2048_4x18_xczu2cg.tcl"
vivado -mode batch -source "$REPO/scripts/synth/vivado/spiral_dft_2048_4in_16_xczu2cg.tcl"
```

Outputs are written to:

- `reports/vivado/vivado_sgen_dftcompact_2048_4x18_util.rpt`
- `reports/vivado/vivado_sgen_dftcompact_2048_4x18_timing.rpt`
- `reports/vivado/vivado_spiral_dft_2048_4in_16_util.rpt`
- `reports/vivado/vivado_spiral_dft_2048_4in_16_timing.rpt`

## How To Re-run Libero

Libero lives in `ubuntu22`, and the local launcher already sets the license variables and tool paths.

From the host:

```bash
distrobox enter ubuntu22
REPO=/run/host/var/home/alex/git/SGen
~/launch_libero.sh "script:$REPO/scripts/synth/libero/sgen_dftcompact_2048_4x18_pf.tcl"
~/launch_libero.sh "script:$REPO/scripts/synth/libero/spiral_dft_2048_4in_16_pf.tcl"
```

Outputs are written to:

- `reports/libero/sgen_dftcompact_2048_4x18_compile_netlist_resources.xml`
- `reports/libero/sgen_dftcompact_2048_4x18_compile_netlist.log`
- `reports/libero/sgen_dftcompact_2048_4x18_fpga_mapper_resourceusage.rpt`
- `reports/libero/spiral_dft_it_4in_2048_16bit_scaled_compile_netlist_resources.xml`
- `reports/libero/spiral_dft_it_4in_2048_16bit_scaled_compile_netlist.log`
- `reports/libero/spiral_dft_it_4in_2048_16bit_scaled_fpga_mapper_resourceusage.rpt`

Important:
- These Libero scripts intentionally attempt `COMPILE` after synthesis so the compile-netlist resource files are produced.
- On the current `MPFS095TL-FCSG325E` target, `COMPILE` is expected to complain about top-level I/O count.

## How To Re-run Quartus

Quartus Pro lives in `ubuntu24`.

From the host:

```bash
distrobox enter ubuntu24 -- bash -lc '
  REPO=/run/host/var/home/alex/git/SGen
  "$HOME/altera_pro/25.3.1/quartus/bin/quartus_sh" -t "$REPO/scripts/synth/quartus/sgen_dftcompact_2048_4x18_ac135.tcl"
  "$HOME/altera_pro/25.3.1/quartus/bin/quartus_sh" -t "$REPO/scripts/synth/quartus/spiral_dft_2048_4in_16_ac135.tcl"
'
```

Outputs are written to:

- `build/quartus/.../output_files/`
- and copied back into `reports/quartus/`

Important:
- The Quartus scripts assign all top-level ports as virtual pins so the fitter can complete on `A3CW135BM16AE6S`.
- Timing summaries will reflect a tool-derived clock because no user `.sdc` is present in this flow yet.

## Existing Validation / Test Hooks

Useful regression commands before and after SGen changes:

- `sbt RegularTest/test`
  - Scala algorithm/correctness tests.
- `sbt SimTest/test`
  - Generates Verilog testbenches and simulates them with Xilinx `xvhdl`, `xvlog`, `xelab`, and `xsim`.
- `sbt SynthTest/test`
  - Existing Vivado-oriented synthesis regression flow under `test/synth/`.

## Current State Summary

- The repo now contains the two comparison designs in explicit folders.
- The three synthesis targets each have reusable Tcl scripts checked into the repo.
- The key external reports gathered so far have been copied into `reports/`.
- The baseline is ready for SGen architecture changes, new reference designs, or a fairer `16-bit` SGen re-run across all three FPGA families.
