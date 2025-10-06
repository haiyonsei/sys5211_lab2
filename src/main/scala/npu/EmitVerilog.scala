
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

  val bytesLe: Seq[Byte] =
    words.flatMap { w => (0 until beatBytes).map(i => ((w >> (8*i)) & 0xFF).toInt.toByte) }

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

