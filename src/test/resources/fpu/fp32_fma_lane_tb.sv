module fp32_fma_lane_tb;
  logic clk_i = 0;
  logic rst_ni = 0;
  logic [31:0] operand_a_i, operand_b_i, operand_c_i;
  logic [2:0] rnd_mode_i;
  logic [3:0] op_i;
  logic op_mod_i;
  logic [15:0] tag_i;
  logic in_valid_i, in_ready_o, flush_i;
  logic [31:0] result_o;
  logic [4:0] status_o;
  logic [15:0] tag_o;
  logic out_valid_o, out_ready_i, busy_o;

  always #1 clk_i = ~clk_i;

  fp32_fma_lane_wrapper dut (.*);

  task automatic execute(
    input logic [3:0] op,
    input logic modifier,
    input logic [31:0] a,
    input logic [31:0] b,
    input logic [31:0] c,
    input logic [31:0] expected,
    input logic [15:0] tag
  );
    op_i = op;
    op_mod_i = modifier;
    operand_a_i = a;
    operand_b_i = b;
    operand_c_i = c;
    tag_i = tag;
    in_valid_i = 1;
    do @(posedge clk_i); while (!in_ready_o);
    @(negedge clk_i);
    in_valid_i = 0;
    do @(posedge clk_i); while (!out_valid_o);
    if (result_o !== expected || status_o !== 0 || tag_o !== tag)
      $fatal(1, "op=%0d result=%h expected=%h status=%h tag=%h", op, result_o,
             expected, status_o, tag_o);

    // A completed result and its metadata must remain stable under pressure.
    repeat (2) begin
      @(posedge clk_i);
      if (!out_valid_o || result_o !== expected || tag_o !== tag)
        $fatal(1, "result changed while output was blocked");
    end
    out_ready_i = 1;
    @(posedge clk_i);
    @(negedge clk_i);
    out_ready_i = 0;
  endtask

  initial begin
    operand_a_i = 0;
    operand_b_i = 0;
    operand_c_i = 0;
    rnd_mode_i = 3'b000;
    op_i = 0;
    op_mod_i = 0;
    tag_i = 0;
    in_valid_i = 0;
    flush_i = 0;
    out_ready_i = 0;
    repeat (3) @(posedge clk_i);
    rst_ni = 1;

    // 1.5 * 2.0 + 0.5 = 3.5
    execute(4'd0, 1'b0, 32'h3fc00000, 32'h40000000, 32'h3f000000,
            32'h40600000, 16'h1234);
    // 1.5 * 2.0 - 0.5 = 2.5
    execute(4'd0, 1'b1, 32'h3fc00000, 32'h40000000, 32'h3f000000,
            32'h40200000, 16'h2345);
    // ADD uses operand B + operand C in fpnew_fma.
    execute(4'd2, 1'b0, 32'h00000000, 32'h3fc00000, 32'h40000000,
            32'h40600000, 16'h3456);
    // 1.5 * 2.0 = 3.0
    execute(4'd3, 1'b0, 32'h3fc00000, 32'h40000000, 32'h00000000,
            32'h40400000, 16'h4567);
    $display("fp32_fma_lane_tb PASS");
    $finish;
  end
endmodule
