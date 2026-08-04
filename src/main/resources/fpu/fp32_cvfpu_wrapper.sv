// FP32-only integration boundary for OpenHW CVFPU/FPnew.
// CVFPU revision: 79e453139072df42c9ec8f697132ba485d74e23d
module fp32_cvfpu_wrapper #(
  parameter int unsigned TAG_WIDTH = 16
) (
  input  logic                   clk_i,
  input  logic                   rst_ni,
  input  logic [31:0]            operand_a_i,
  input  logic [31:0]            operand_b_i,
  input  logic [31:0]            operand_c_i,
  input  logic [2:0]             rnd_mode_i,
  input  logic [3:0]             op_i,
  input  logic                   op_mod_i,
  input  logic [TAG_WIDTH-1:0]   tag_i,
  input  logic                   in_valid_i,
  output logic                   in_ready_o,
  input  logic                   flush_i,
  output logic [31:0]            result_o,
  output logic [4:0]             status_o,
  output logic [TAG_WIDTH-1:0]   tag_o,
  output logic                   out_valid_o,
  input  logic                   out_ready_i,
  output logic                   busy_o
);
  logic [2:0][31:0] operands;
  assign operands[0] = operand_a_i;
  assign operands[1] = operand_b_i;
  assign operands[2] = operand_c_i;

  // DEFAULT_SNITCH inserts one register in each enabled operation group and
  // disables DIV/SQRT.  Those instructions remain illegal until a separate,
  // low-throughput unit is integrated and verified.
  fpnew_top #(
    .Features       (fpnew_pkg::RV32F),
    .Implementation (fpnew_pkg::DEFAULT_SNITCH),
    .TagType        (logic [TAG_WIDTH-1:0])
  ) i_fpnew (
    .clk_i,
    .rst_ni,
    .operands_i     (operands),
    .rnd_mode_i     (fpnew_pkg::roundmode_e'(rnd_mode_i)),
    .op_i           (fpnew_pkg::operation_e'(op_i)),
    .op_mod_i,
    .src_fmt_i      (fpnew_pkg::FP32),
    .dst_fmt_i      (fpnew_pkg::FP32),
    .int_fmt_i      (fpnew_pkg::INT32),
    .vectorial_op_i (1'b0),
    .tag_i,
    .simd_mask_i    ('1),
    .in_valid_i,
    .in_ready_o,
    .flush_i,
    .result_o,
    .status_o,
    .tag_o,
    .out_valid_o,
    .out_ready_i,
    .busy_o
  );
endmodule
