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
    val beatBytes = 8   // 64-bit beat
    val sramDepth = 32
    val romBase   = BigInt(0L)

    // 4개의 64b 워드를 리틀엔디안으로 ROM에 적재
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
      // 초기화
      c.io.spad.dbgRdEn.poke(false.B)
      c.io.dma.read.valid.poke(false.B)
      c.clock.step()

      // 멀티-beat DMA 요청 (총 32B = 4 * 8B)
      val nBeats      = words.length
      val totalBytes  = nBeats * beatBytes
      val baseSpAddr  = 0

      c.io.dma.read.valid.poke(true.B)
      c.io.dma.read.bits.vaddr.poke(romBase.U)
      c.io.dma.read.bits.spaddr.poke(baseSpAddr.U)
      c.io.dma.read.bits.bytes.poke(totalBytes.U) // 4 beats
      c.io.dma.read.bits.cmdId.poke(7.U)

      // 요청 핸드셰이크
      while (!c.io.dma.read.ready.peek().litToBoolean) { c.clock.step() }
      c.clock.step()
      c.io.dma.read.valid.poke(false.B)

      // 완료 대기
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

      // 스크래치패드에 4개의 워드가 순서대로 써졌는지 확인
      for (i <- 0 until nBeats) {
        c.io.spad.dbgRdEn.poke(true.B)
        c.io.spad.dbgRdAddr.poke((baseSpAddr + i).U)
        c.clock.step() // SyncReadMem 1-cycle latency
        val got = c.io.spad.dbgRdData.peek().litValue
        val exp = words(i)
        println(f"spad[$i] = 0x$got%X  expect = 0x$exp%X")
        got shouldBe exp
      }
    }
  }
}
