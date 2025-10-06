package npu

import chisel3._
import chisel3.util._

import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._

import freechips.rocketchip.devices.tilelink._

class DMARdReq(addrBits: Int, spAddrBits: Int) extends Bundle {
  val vaddr  = UInt(addrBits.W)
  val spaddr = UInt(spAddrBits.W)
  val bytes  = UInt(16.W)
  val cmdId  = UInt(8.W)
}
class DMARdResp extends Bundle {
  val bytesRead = UInt(16.W)
  val cmdId     = UInt(8.W)
}

class MiniScratchpadBank(depth: Int, wBits: Int) extends Module {
  val io = IO(new Bundle {
    val wrEn      = Input(Bool())
    val wrAddr    = Input(UInt(log2Ceil(depth).W))
    val wrData    = Input(UInt(wBits.W))

    val dbgRdEn   = Input(Bool())
    val dbgRdAddr = Input(UInt(log2Ceil(depth).W))
    val dbgRdData = Output(UInt(wBits.W))
  })
  val mem = SyncReadMem(depth, UInt(wBits.W))
  when (io.wrEn) { mem.write(io.wrAddr, io.wrData) }
  io.dbgRdData := mem.read(io.dbgRdAddr, io.dbgRdEn)
}

class SimpleDmaToSram(sramDepth: Int, beatBytes: Int)(implicit p: Parameters) extends LazyModule {
  val node = TLClientNode(Seq(TLClientPortParameters(Seq(
    TLClientParameters(name = "simple-dma2sram", sourceId = IdRange(0, 1))
  ))))

  class Impl(outer: SimpleDmaToSram) extends LazyModuleImp(outer) {
    val io = IO(new Bundle {
      val dma = new Bundle {
        val read = Flipped(Decoupled(new DMARdReq(64, log2Ceil(sramDepth))))
        val resp = Valid(new DMARdResp)
      }
      val spad = new Bundle {
        val dbgRdEn   = Input(Bool())
        val dbgRdAddr = Input(UInt(log2Ceil(sramDepth).W))
        val dbgRdData = Output(UInt((8*beatBytes).W))
      }
      val busy = Output(Bool())
    })

    val (out, edge) = outer.node.out(0)

    // Scratchpad
    val spad = Module(new MiniScratchpadBank(sramDepth, 8*beatBytes))
    spad.io.dbgRdEn   := io.spad.dbgRdEn
    spad.io.dbgRdAddr := io.spad.dbgRdAddr
    io.spad.dbgRdData := spad.io.dbgRdData

    val reqReg   = Reg(new DMARdReq(64, log2Ceil(sramDepth)))
    val spAddr   = Reg(UInt(log2Ceil(sramDepth).W))
    val addrReg  = Reg(UInt(64.W)) 
    val data     = Reg(UInt((8*beatBytes).W))
    val lgBeat   = log2Ceil(beatBytes)
    val lgBeatU  = lgBeat.U

    val beatsLeft = Reg(UInt(16.W))

    val sIdle :: sSendA :: sWaitD :: sWrite :: sDone :: Nil = Enum(5)
    val state = RegInit(sIdle)

    out.a.valid        := (state === sSendA)
    out.a.bits.opcode  := TLMessages.Get
    out.a.bits.param   := 0.U
    out.a.bits.size    := lgBeatU
    out.a.bits.source  := 0.U
    out.a.bits.address := addrReg
    out.a.bits.mask    := edge.mask(addrReg, lgBeatU)
    out.a.bits.data    := DontCare
    out.a.bits.corrupt := false.B

    out.d.ready        := (state === sWaitD)

    spad.io.wrEn   := (state === sWrite)
    spad.io.wrAddr := spAddr
    spad.io.wrData := data

    io.dma.read.ready        := (state === sIdle)
    io.dma.resp.valid        := (state === sDone)
    io.dma.resp.bits.cmdId   := reqReg.cmdId
    io.dma.resp.bits.bytesRead := reqReg.bytes

    switch (state) {
      is (sIdle) {
        when (io.dma.read.fire) {
          when (beatBytes.U === 1.U) {
            assert(io.dma.read.bits.bytes =/= 0.U, "bytes must be > 0")
          } .otherwise {
            assert(io.dma.read.bits.bytes =/= 0.U, "bytes must be > 0")
            assert(io.dma.read.bits.vaddr(lgBeat-1, 0) === 0.U, "vaddr must be beat-aligned")
            assert(io.dma.read.bits.bytes(lgBeat-1, 0) === 0.U, "bytes must be a multiple of beatBytes")
          }

          reqReg    := io.dma.read.bits
          spAddr    := io.dma.read.bits.spaddr
          addrReg   := io.dma.read.bits.vaddr
          beatsLeft := (io.dma.read.bits.bytes >> lgBeat).asUInt
          state     := sSendA
        }
      }

      is (sSendA) {
        when (out.a.fire) {
          state := sWaitD
        }
      }

      is (sWaitD) {
        when (out.d.fire) {
          data  := out.d.bits.data
          state := sWrite
        }
      }

      is (sWrite) {
        printf(p"sWrite: spAddr = 0x${Hexadecimal(spad.io.wrAddr)}  data = 0x${Hexadecimal(spad.io.wrData)}\n")

        when (beatsLeft === 1.U) {
          state := sDone
        } .otherwise {
          val nextAddr = addrReg + beatBytes.U
          addrReg   := nextAddr
          spAddr    := spAddr + 1.U
          beatsLeft := beatsLeft - 1.U
          state     := sSendA
        }
      }

      is (sDone) {
        state := sIdle
      }
    }

    io.busy := (state =/= sIdle)
  }

  override lazy val module: Impl = new Impl(this)
}




class SimpleDmaTop(
  sramDepth: Int,
  beatBytes: Int,
  useROM:    Boolean,
  romBase:   BigInt,
  romBytes:  Seq[Byte]
)(implicit p: Parameters) extends LazyModule {

  val dma2sram = LazyModule(new SimpleDmaToSram(sramDepth, beatBytes))

  if (useROM) {
    val rom = LazyModule(new TLROM(
      base = romBase,
      size = romBytes.length,
      romBytes,
      beatBytes = beatBytes
    ))
    rom.node := TLBuffer() := dma2sram.node
  } else {
    val ramSizePow2 = 1 << log2Ceil(romBytes.length max 1)
    val ramMask     = (ramSizePow2 - 1).toLong
    val ram = LazyModule(new TLRAM(
      address   = AddressSet(romBase, ramMask),
      beatBytes = beatBytes
    ))
    ram.node := TLBuffer() := dma2sram.node
  }

  class Impl(outer: SimpleDmaTop) extends LazyModuleImp(outer) {
    val io = IO(new Bundle {
      val dma = new Bundle {
        val read = Flipped(Decoupled(new DMARdReq(64, log2Ceil(sramDepth))))
        val resp = Valid(new DMARdResp)
      }
      val spad = new Bundle {
        val dbgRdEn   = Input(Bool())
        val dbgRdAddr = Input(UInt(log2Ceil(sramDepth).W))
        val dbgRdData = Output(UInt((8*beatBytes).W))
      }
      val busy = Output(Bool())
    })

    val childIO = outer.dma2sram.module.io

    io.dma.read <> childIO.dma.read
    io.dma.resp := childIO.dma.resp

    childIO.spad.dbgRdEn   := io.spad.dbgRdEn
    childIO.spad.dbgRdAddr := io.spad.dbgRdAddr
    io.spad.dbgRdData      := childIO.spad.dbgRdData

    io.busy := childIO.busy
  }

  override lazy val module: Impl = new Impl(this)
}