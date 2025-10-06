package npu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy.LazyModule

class SimpleDmaSpec extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  implicit val p: Parameters = Parameters.empty

  behavior of "SimpleDmaToSram (multi-beat TL Get -> SRAM writes)"

  it should "read multiple beats from TLROM and store into SRAM sequentially" in {
    val beatBytes = 8
    val sramDepth = 32
    val romBase   = BigInt(0L)

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
    val bytesLe: Seq[Byte] = words.flatMap { w =>
      (0 until beatBytes).map { i => ((w >> (8 * i)) & 0xFF).toInt.toByte }
    }

    val romSize  = 256
    val padBytes = Seq.fill(romSize - bytesLe.length)(0.toByte)
    val romBytes: Seq[Byte] = bytesLe ++ padBytes

    val useROM = true
    val top = LazyModule(new SimpleDmaTop(sramDepth, beatBytes, useROM, romBase, romBytes))

    test(top.module) { c =>
      c.io.spad.dbgRdEn.poke(false.B)
      c.io.dma.read.valid.poke(false.B)
      c.clock.step()

      val nBeats      = words.length
      val totalBytes  = nBeats * beatBytes
      val baseSpAddr  = 0

      c.io.dma.read.valid.poke(true.B)
      c.io.dma.read.bits.vaddr.poke(romBase.U)
      c.io.dma.read.bits.spaddr.poke(baseSpAddr.U)
      c.io.dma.read.bits.bytes.poke(totalBytes.U) 
      c.io.dma.read.bits.cmdId.poke(7.U)

      while (!c.io.dma.read.ready.peek().litToBoolean) { c.clock.step() }
      c.clock.step()
      c.io.dma.read.valid.poke(false.B)

      var gotResp = false
      var safety  = 200
      while (!gotResp && safety > 0) {
        if (c.io.dma.resp.valid.peek().litToBoolean) {
          gotResp = true
          c.io.dma.resp.bits.cmdId.expect(7.U)
          c.io.dma.resp.bits.bytesRead.expect(totalBytes.U)
        }
        c.clock.step()
        safety -= 1
      }
      assert(gotResp, "DMA read did not complete")

      for (i <- 0 until nBeats) {
        c.io.spad.dbgRdEn.poke(true.B)
        c.io.spad.dbgRdAddr.poke((baseSpAddr + i).U)
        c.clock.step()
        val got = c.io.spad.dbgRdData.peek().litValue
        val exp = words(i)
        println(f"spad[$i] = 0x$got%X  expect = 0x$exp%X")
        got shouldBe exp
      }
    }
  }
}
