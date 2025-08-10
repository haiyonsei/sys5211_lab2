
package npu

import chisel3.stage.ChiselStage
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy.LazyModule

object EmitVerilog extends App {
  implicit val p: Parameters = Parameters.empty

  val beatBytes = 8
  val sramDepth = 32
  val romBase   = BigInt(0)
  val useROM    = true

  // 테스트와 동일한 8개 64b 워드
  val words: Seq[BigInt] = Seq(
    BigInt("DEADBEEFCAFEBABE", 16),
    BigInt("1122334455667788", 16),
    BigInt("0123456789ABCDEF", 16),
    BigInt("0F1E2D3C4B5A6978", 16),
    BigInt("DEADBEEFCAFEBABE", 16),
    BigInt("1122334455667788", 16),
    BigInt("0123456789ABCDEF", 16),
    BigInt("0F1E2D3C4B5A6978", 16)
  )

  // 리틀엔디안으로 전개
  val bytesLe: Seq[Byte] =
    words.flatMap { w => (0 until beatBytes).map(i => ((w >> (8*i)) & 0xFF).toInt.toByte) }

  // 256B로 패딩
  val romSize  = 256
  require(bytesLe.length <= romSize)
  val romBytes: Seq[Byte] = bytesLe ++ Seq.fill(romSize - bytesLe.length)(0.toByte)

  val top = LazyModule(new SimpleDmaTop(
    sramDepth = sramDepth,
    beatBytes = beatBytes,
    useROM    = useROM,
    romBase   = romBase,
    romBytes  = romBytes
  ))

  new ChiselStage().emitVerilog(
    top.module,
    Array("--target-dir", "build")
  )
}
/*
package npu

import chisel3.stage.ChiselGeneratorAnnotation
import circt.stage.ChiselStage

object EmitVerilog extends App {
  (new ChiselStage).execute(
    Array(
      "--target", "systemverilog",       // CIRCT 백엔드로 SV 생성
      "--target-dir", "generated",
      "--split-verilog",
      "--disable-all-randomization"      // 재현성 원하면 유지, 아니면 제거
      // 필요시 추가: "--preserve-aggregate=1"
    ),
    Seq(
      ChiselGeneratorAnnotation(() =>
        new NpuExeTop(
          blockSize = 4, spBanks = 4, spEntries = 256, dataW = 16, addrW = 16
        )
      )
    )
  )
}

*/
