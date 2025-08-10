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
  val bytes  = UInt(16.W) // 총 요청 바이트 수 (멀티-beat 허용)
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
  io.dbgRdData := mem.read(io.dbgRdAddr, io.dbgRdEn) // 1-cycle
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

    // -----------------------
    // 상태/레지스터
    // -----------------------
    val reqReg   = Reg(new DMARdReq(64, log2Ceil(sramDepth)))
    val spAddr   = Reg(UInt(log2Ceil(sramDepth).W))
    val addrReg  = Reg(UInt(64.W))           // NEW: 현재 beat의 vaddr
    val data     = Reg(UInt((8*beatBytes).W))
    val lgBeat   = log2Ceil(beatBytes)       // Int
    val lgBeatU  = lgBeat.U

    // NEW: 남은 beat 수
    val beatsLeft = Reg(UInt(16.W))

    val sIdle :: sSendA :: sWaitD :: sWrite :: sDone :: Nil = Enum(5)
    val state = RegInit(sIdle)

    // -----------------------
    // A 채널(Get): 1 beat 크기로 반복 발행
    // -----------------------
    out.a.valid        := (state === sSendA)
    out.a.bits.opcode  := TLMessages.Get
    out.a.bits.param   := 0.U
    out.a.bits.size    := lgBeatU               // 항상 1 beat 단위 요청
    out.a.bits.source  := 0.U
    out.a.bits.address := addrReg               // 현재 beat의 주소
    out.a.bits.mask    := edge.mask(addrReg, lgBeatU)
    out.a.bits.data    := DontCare
    out.a.bits.corrupt := false.B

    // D 채널
    out.d.ready        := (state === sWaitD)

    // Scratchpad write
    spad.io.wrEn   := (state === sWrite)
    spad.io.wrAddr := spAddr
    spad.io.wrData := data

    // DMA IF
    io.dma.read.ready        := (state === sIdle)
    io.dma.resp.valid        := (state === sDone)
    io.dma.resp.bits.cmdId   := reqReg.cmdId
    io.dma.resp.bits.bytesRead := reqReg.bytes  // NEW: 전체 바이트 수로 응답

    // -----------------------
    // FSM
    // -----------------------
    switch (state) {
      is (sIdle) {
        when (io.dma.read.fire) {
          // 제약: bytes > 0, vaddr beat 정렬, bytes는 beatBytes 배수
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
          beatsLeft := (io.dma.read.bits.bytes >> lgBeat).asUInt // N = bytes/beatBytes
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
        // 디버그
        printf(p"sWrite: spAddr = 0x${Hexadecimal(spad.io.wrAddr)}  data = 0x${Hexadecimal(spad.io.wrData)}\n")

        // 현재 beat 기록 후 다음 진행
        when (beatsLeft === 1.U) {
          // 마지막 beat 완료
          state := sDone
        } .otherwise {
          // 다음 beat 준비
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



// -----------------------------
// 상단 래퍼: TLROM 또는 TLRAM에 연결
// -----------------------------
class SimpleDmaTop(
  sramDepth: Int,
  beatBytes: Int,
  useROM:    Boolean,         // true면 TLROM, false면 TLRAM
  romBase:   BigInt,
  romBytes:  Seq[Byte]
)(implicit p: Parameters) extends LazyModule {

  val dma2sram = LazyModule(new SimpleDmaToSram(sramDepth, beatBytes))

  // Client(우) -> Manager(좌) 연결: 분기 내에서 바로 연결해 타입 문제 제거
  if (useROM) {
    // TLROM: 4번째 인자(executable)는 디폴트 사용, beatBytes는 named로 전달
    val rom = LazyModule(new TLROM(
      base = romBase,
      size = romBytes.length,
      romBytes,                   // contents (positional; 버전별 contents/contentsDelayed 차이 회피)
      beatBytes = beatBytes
    ))
    rom.node := TLBuffer() := dma2sram.node
  } else {
    // TLRAM: 주소 마스크는 보통 (sizePow2 - 1)
    val ramSizePow2 = 1 << log2Ceil(romBytes.length max 1)
    val ramMask     = (ramSizePow2 - 1).toLong
    val ram = LazyModule(new TLRAM(
      address   = AddressSet(romBase, ramMask),
      beatBytes = beatBytes
    ))
    ram.node := TLBuffer() := dma2sram.node
  }

  // Top Imp
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

    // path‑dependent 타입 누출 방지: IO만 뽑아 사용
    val childIO = outer.dma2sram.module.io

    io.dma.read <> childIO.dma.read
    io.dma.resp := childIO.dma.resp

    //io.spad.dbgRdEn   := childIO.spad.dbgRdEn
    //io.spad.dbgRdAddr := childIO.spad.dbgRdAddr
    //io.spad.dbgRdData := childIO.spad.dbgRdData

    childIO.spad.dbgRdEn   := io.spad.dbgRdEn
    childIO.spad.dbgRdAddr := io.spad.dbgRdAddr
    io.spad.dbgRdData      := childIO.spad.dbgRdData

    io.busy := childIO.busy
  }

  override lazy val module: Impl = new Impl(this)
}