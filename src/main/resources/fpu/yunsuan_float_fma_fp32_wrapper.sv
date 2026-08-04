// FP32-only integration wrapper around YunSuan's generated FloatFMA.
// Constant fp_format allows synthesis to remove the FP16/FP64 datapaths.
module yunsuan_float_fma_fp32_wrapper (
  input  logic        clk_i,
  input  logic        rst_ni,
  input  logic [31:0] operand_a_i,
  input  logic [31:0] operand_b_i,
  input  logic [31:0] operand_c_i,
  input  logic [2:0]  rnd_mode_i,
  input  logic [3:0]  op_i,
  input  logic        in_valid_i,
  output logic [31:0] result_o,
  output logic [4:0]  status_o
);
  logic [63:0] result_full;

  FloatFMA i_fma (
    .clock                   (clk_i),
    .reset                   (~rst_ni),
    .io_fire                 (in_valid_i),
    .io_fp_a                 ({32'b0, operand_a_i}),
    .io_fp_b                 ({32'b0, operand_b_i}),
    .io_fp_c                 ({32'b0, operand_c_i}),
    .io_round_mode           (rnd_mode_i),
    .io_fp_format            (2'b10),
    .io_op_code              (op_i),
    .io_fp_result            (result_full),
    .io_fflags               (status_o),
    .io_fp_aIsFpCanonicalNAN (operand_a_i[30:0] == 31'h7fc00000),
    .io_fp_bIsFpCanonicalNAN (operand_b_i[30:0] == 31'h7fc00000),
    .io_fp_cIsFpCanonicalNAN (operand_c_i[30:0] == 31'h7fc00000)
  );

  assign result_o = result_full[31:0];
endmodule
