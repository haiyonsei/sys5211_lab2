
# Lab 2: Implementing `Scratchpad` in Verilog

## Overview
This lab focuses on understanding and implementing the `Scratchpad` module by translating its behavior from the provided Scala/Chisel source into Verilog. The project is pre-configured with all necessary build scripts, source files, and testbenches.

## File Structure
The lab consists of the following files:

- **Build & Configuration**
  - `build.sbt` – SBT build configuration
  - `build/Makefile` – Makefile to compile, simulate, and debug
- **Main Sources**
  - `src/main/scala/npu/EmitVerilog.scala` – Entry point for Verilog generation from Chisel
  - `src/main/scala/npu/Scratchpad.scala` – Scala implementation of the Scratchpad logic
  - `src/main/verilog/SimpleDmaTop.v` – Verilog version of the top-level scratchpad 
  - `src/main/verilog/SimpleDmaTopToSram.v` – **(To be implemented by you)** Verilog version of the mini scratchpade
- **Test Sources**
  - `src/test/scala/npu/ScratchpadSpec.scala` – Scala test specification
  - `src/test/verilog/tb_scratchpad.sv` – Verilog testbench for simulation

## Lab Goal
Your objective is to:
1. Analyze `Scratchpad.scala` to understand its functionality.
2. Implement **`SimpleDmaTopToSram.v`** in Verilog to match the behavior of the Scala/Chisel design.
3. Verify your implementation against the reference design.

## Build & Simulation Flow

### 1. Generating Reference Verilog
To generate the reference Verilog from the Chisel implementation:
```bash
make ref
```
This:

* Runs Scratchpad.scala through the Chisel generator (EmitVerilog.scala).
* Produces SimpleDmaTop.v in the build directory.
* Compiles the design with Synopsys VCS into an executable simv.

To run the reference simulation:
```bash 
make run
```

### 2. GUI Debugging with Verdi
You can view the simulation waveform using Verdi:
```bash
make refgui
make run
```
This opens Verdi, a GUI-based Synopsys debugging tool, allowing you to inspect signals and verify the Scratchpad's behavior.


### 3. Implementing Your Verilog
Edit:
```
src/main/verilog/SimpleDmaToSram.v
```

Then build and simulate your design:
```bash
make       # or make gui for waveform debugging
make run
```
Your goal is to match the simulation results from make ref exactly.

**Testing**

* Testbench: tb_scratchpad.sv is provided to drive and validate your design.
* Ensure that the outputs from your Verilog implementation match those from the reference Chisel-generated design.
* When you see the message "[TB] PASS: 8 beats transferred and verified.", it indicates that the code has been successfully verified.

**Summary**

By completing this lab, you will:
* Gain experience translating high-level hardware descriptions into Verilog.
* Learn how to verify hardware modules using industry-standard tools (VCS and Verdi).
* Deepen your understanding of scratchpad logic within an NPU context.





