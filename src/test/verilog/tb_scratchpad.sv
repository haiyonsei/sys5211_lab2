

`timescale 1ns/1ps

module tb_SimpleDmaTop;

  localparam int BEAT_BYTES  = 8;
  localparam int NBEATS      = 8;
  localparam int TOTAL_BYTES = BEAT_BYTES * NBEATS;
  localparam int SRAM_DEPTH  = 32;

  reg  clock;
  reg  reset;

  wire        io_dma_read_ready;
  reg         io_dma_read_valid;
  reg  [63:0] io_dma_read_bits_vaddr;
  reg  [4:0]  io_dma_read_bits_spaddr;
  reg  [15:0] io_dma_read_bits_bytes;
  reg  [7:0]  io_dma_read_bits_cmdId;

  wire        io_dma_resp_valid;
  wire [15:0] io_dma_resp_bits_bytesRead;
  wire [7:0]  io_dma_resp_bits_cmdId;

  reg         io_spad_dbgRdEn;
  reg  [4:0]  io_spad_dbgRdAddr;
  wire [63:0] io_spad_dbgRdData;
  reg [63:0] got;

  wire        io_busy;

  SimpleDmaTop dut (
    .clock                      (clock),
    .reset                      (reset),

    .io_dma_read_ready          (io_dma_read_ready),
    .io_dma_read_valid          (io_dma_read_valid),
    .io_dma_read_bits_vaddr     (io_dma_read_bits_vaddr),
    .io_dma_read_bits_spaddr    (io_dma_read_bits_spaddr),
    .io_dma_read_bits_bytes     (io_dma_read_bits_bytes),
    .io_dma_read_bits_cmdId     (io_dma_read_bits_cmdId),

    .io_dma_resp_valid          (io_dma_resp_valid),
    .io_dma_resp_bits_bytesRead (io_dma_resp_bits_bytesRead),
    .io_dma_resp_bits_cmdId     (io_dma_resp_bits_cmdId),

    .io_spad_dbgRdEn            (io_spad_dbgRdEn),
    .io_spad_dbgRdAddr          (io_spad_dbgRdAddr),
    .io_spad_dbgRdData          (io_spad_dbgRdData),

    .io_busy                    (io_busy)
  );

  initial clock = 0;
  always #5 clock = ~clock; 

  reg [63:0] expected [0:NBEATS-1];
  initial begin
    expected[0] = 64'hDEADBEEFCAFEBABE;
    expected[1] = 64'h1122334455667788;
    expected[2] = 64'h0123456789ABCDEF;
    expected[3] = 64'h0F1E2D3C4B5A6978;
    expected[4] = 64'hDEADBEEFCAFEBABE;
    expected[5] = 64'h1122334455667788;
    expected[6] = 64'h0123456789ABCDEF;
    expected[7] = 64'h0F1E2D3C4B5A6978;
  end

  task automatic step(input int n = 1);
    repeat (n) @(posedge clock);
  endtask

  task automatic wait_ready();
    int guard = 1000;
    while (!io_dma_read_ready && guard > 0) begin
      step(1);
      guard -= 1;
    end
    if (guard == 0) begin
      $fatal(1, "[TB] Timeout waiting for io_dma_read_ready");
    end
  endtask

  task automatic wait_resp();
    int guard = 1000;
    while (!io_dma_resp_valid && guard > 0) begin
      step(1);
      guard -= 1;
    end
    if (guard == 0) begin
      $fatal(1, "[TB] Timeout waiting for io_dma_resp_valid");
    end
  endtask

  task automatic spad_read(input [4:0] addr, output [63:0] dout);
    begin
      io_spad_dbgRdEn   = 1'b1;
      io_spad_dbgRdAddr = addr;
      step(1);
      dout = io_spad_dbgRdData;
    end
  endtask

  initial begin
    `ifdef WAVES
      $dumpfile("tb_SimpleDmaTop.vcd");
      $dumpvars(0, tb_SimpleDmaTop);
    `endif

    reset                      = 1'b1;
    io_dma_read_valid          = 1'b0;
    io_dma_read_bits_vaddr     = 64'd0;
    io_dma_read_bits_spaddr    = 5'd0;
    io_dma_read_bits_bytes     = 16'd0;
    io_dma_read_bits_cmdId     = 8'd0;
    io_spad_dbgRdEn            = 1'b0;
    io_spad_dbgRdAddr          = 5'd0;

    step(5);
    reset = 1'b0;
    step(2);

    io_dma_read_bits_vaddr = 64'd0;           
    io_dma_read_bits_spaddr = 5'd0;
    io_dma_read_bits_bytes  = TOTAL_BYTES[15:0]; 
    io_dma_read_bits_cmdId  = 8'd7;

    io_dma_read_valid = 1'b1;
    wait_ready();     
    step(1);          
    io_dma_read_valid = 1'b0;

    wait_resp();
    if (io_dma_resp_bits_cmdId !== 8'd7) begin
      $fatal(1, "[TB] cmdId mismatch: got %0d", io_dma_resp_bits_cmdId);
    end
    if (io_dma_resp_bits_bytesRead !== TOTAL_BYTES[15:0]) begin
      $fatal(1, "[TB] bytesRead mismatch: got %0d", io_dma_resp_bits_bytesRead);
    end

    for (int i = 0; i < NBEATS; i++) begin
      spad_read(i[4:0], got);
      $display("[TB] spad[%0d] = 0x%016h (expect 0x%016h)", i, got, expected[i]);
      if (got !== expected[i]) begin
        $fatal(1, "[TB] MISMATCH at spad[%0d]: got 0x%016h expect 0x%016h", i, got, expected[i]);
      end
    end

    $display("[TB] PASS: %0d beats transferred and verified.", NBEATS);
    step(5);
    $finish;
  end

endmodule

