module GpuIntegerAlu32Benchmark (
  input  logic        clock,
  input  logic [3:0]  operation,
  input  logic [31:0] lhs,
  input  logic [31:0] rhs,
  output logic [31:0] result
);
  logic [3:0]  operation_q;
  logic [31:0] lhs_q;
  logic [31:0] rhs_q;
  logic [31:0] alu_result;
  logic [31:0] result_q;

  IntegerAlu alu (
    .io_lhs(lhs_q),
    .io_rhs(rhs_q),
    .io_operation(operation_q),
    .io_result(alu_result)
  );

  always_ff @(posedge clock) begin
    operation_q <= operation;
    lhs_q <= lhs;
    rhs_q <= rhs;
    result_q <= alu_result;
  end

  assign result = result_q;
endmodule
