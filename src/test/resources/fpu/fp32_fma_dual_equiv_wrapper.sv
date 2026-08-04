module fp32_fma_dual_equiv_wrapper (
  input  logic        clk_i,
  input  logic        rst_ni,
  input  logic [31:0] operand_a_i,
  input  logic [31:0] operand_b_i,
  input  logic [31:0] operand_c_i,
  input  logic [2:0]  rnd_mode_i,
  input  logic [3:0]  op_i,
  input  logic         op_mod_i,
  input  logic [15:0] tag_i,
  input  logic         in_valid_i,
  output logic         both_ready_o,
  output logic         mismatch_o,
  output logic         compare_valid_o
);
  logic serial_ready, dual_ready;
  logic [31:0] serial_result, dual_result;
  logic [4:0] serial_status, dual_status;
  logic [15:0] serial_tag, dual_tag;
  logic serial_valid, dual_valid;
  logic serial_busy, dual_busy;
  logic [31:0] serial_result_q;
  logic [4:0] serial_status_q;
  logic [15:0] serial_tag_q;
  logic serial_valid_q;

  assign both_ready_o = serial_ready & dual_ready;
  assign compare_valid_o = serial_valid_q & dual_valid;
  assign mismatch_o = (serial_valid_q != dual_valid)
                    | (compare_valid_o && ((serial_result_q != dual_result)
                                           | (serial_status_q != dual_status)
                                           | (serial_tag_q != dual_tag)));

  always_ff @(posedge clk_i or negedge rst_ni) begin
    if (!rst_ni) begin
      serial_result_q <= '0;
      serial_status_q <= '0;
      serial_tag_q    <= '0;
      serial_valid_q  <= 1'b0;
    end else begin
      serial_result_q <= serial_result;
      serial_status_q <= serial_status;
      serial_tag_q    <= serial_tag;
      serial_valid_q  <= serial_valid;
    end
  end

  fp32_fma_lane_wrapper #(.DUAL_PATH_ABS(1'b0), .NORM_PIPE(1'b0)) i_serial (
    .clk_i, .rst_ni, .operand_a_i, .operand_b_i, .operand_c_i, .rnd_mode_i,
    .op_i, .op_mod_i, .tag_i, .in_valid_i, .in_ready_o(serial_ready),
    .flush_i(1'b0), .result_o(serial_result), .status_o(serial_status),
    .tag_o(serial_tag), .out_valid_o(serial_valid), .out_ready_i(1'b1),
    .busy_o(serial_busy)
  );
  fp32_fma_lane_wrapper #(.DUAL_PATH_ABS(1'b1), .NORM_PIPE(1'b1)) i_dual (
    .clk_i, .rst_ni, .operand_a_i, .operand_b_i, .operand_c_i, .rnd_mode_i,
    .op_i, .op_mod_i, .tag_i, .in_valid_i, .in_ready_o(dual_ready),
    .flush_i(1'b0), .result_o(dual_result), .status_o(dual_status),
    .tag_o(dual_tag), .out_valid_o(dual_valid), .out_ready_i(1'b1),
    .busy_o(dual_busy)
  );
endmodule
