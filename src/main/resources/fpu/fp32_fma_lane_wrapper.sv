// FP32-only fast arithmetic lane built directly from CVFPU's FMA primitive.
// It deliberately bypasses fpnew_top and its operation-group arbitration.
module fp32_fma_lane_wrapper #(
  parameter int unsigned TAG_WIDTH = 16,
  parameter int unsigned PIPE_REGS = 3,
  parameter bit          DUAL_PATH_ABS = 1'b1,
  parameter bit          NORM_PIPE = 1'b1
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
  logic extension_bit_unused, mask_unused, aux_unused;
  assign operands[0] = operand_a_i;
  assign operands[1] = operand_b_i;
  assign operands[2] = operand_c_i;

  fpnew_fma #(
    .FpFormat    (fpnew_pkg::FP32),
    .NumPipeRegs (PIPE_REGS),
    .PipeConfig  (fpnew_pkg::DISTRIBUTED),
    .DualPathAbs (DUAL_PATH_ABS),
    .NormPipe    (NORM_PIPE),
    .TagType     (logic [TAG_WIDTH-1:0]),
    .AuxType     (logic)
  ) i_fma (
    .clk_i,
    .rst_ni,
    .operands_i      (operands),
    .is_boxed_i      (3'b111),
    .rnd_mode_i      (fpnew_pkg::roundmode_e'(rnd_mode_i)),
    .op_i            (fpnew_pkg::operation_e'(op_i)),
    .op_mod_i,
    .tag_i,
    .mask_i          (1'b1),
    .aux_i           (1'b0),
    .in_valid_i,
    .in_ready_o,
    .flush_i,
    .result_o,
    .status_o,
    .extension_bit_o (extension_bit_unused),
    .tag_o,
    .mask_o          (mask_unused),
    .aux_o           (aux_unused),
    .out_valid_o,
    .out_ready_i,
    .busy_o,
    .reg_ena_i       ('0)
  );
endmodule
