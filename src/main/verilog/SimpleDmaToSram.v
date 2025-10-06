module SimpleDmaToSram(
  input         clock,
  input         reset,
  input         auto_out_a_ready,
  output        auto_out_a_valid,
  output [7:0]  auto_out_a_bits_address,
  output        auto_out_d_ready,
  input         auto_out_d_valid,
  input  [63:0] auto_out_d_bits_data,
  output        io_dma_read_ready,
  input         io_dma_read_valid,
  input  [63:0] io_dma_read_bits_vaddr,
  input  [4:0]  io_dma_read_bits_spaddr,
  input  [15:0] io_dma_read_bits_bytes,
  input  [7:0]  io_dma_read_bits_cmdId,
  output        io_dma_resp_valid,
  output [15:0] io_dma_resp_bits_bytesRead,
  output [7:0]  io_dma_resp_bits_cmdId,
  input         io_spad_dbgRdEn,
  input  [4:0]  io_spad_dbgRdAddr,
  output [63:0] io_spad_dbgRdData,
  output        io_busy
);

// Write your code here

endmodule

