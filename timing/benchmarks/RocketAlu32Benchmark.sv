module RocketAlu32Benchmark (
  input  logic        clock,
  input  logic [3:0]  fn,
  input  logic [31:0] in1,
  input  logic [31:0] in2,
  output logic [31:0] out
);
  logic [3:0]  fn_q;
  logic [31:0] in1_q;
  logic [31:0] in2_q;
  logic [31:0] out_q;

  logic        is_sub;
  logic [31:0] in2_inv;
  logic [31:0] in1_xor_in2;
  logic [31:0] adder_out;
  logic        slt;
  logic        cmp_out;
  logic [4:0]  shamt;
  logic [31:0] shin;
  logic signed [32:0] signed_shin;
  logic [31:0] shout_r;
  logic [31:0] shout_l;
  logic [31:0] shout;
  logic [31:0] logic_result;
  logic [31:0] shift_logic;
  logic [31:0] alu_out;

  function automatic logic [31:0] reverse32(input logic [31:0] value);
    integer i;
    begin
      for (i = 0; i < 32; i = i + 1)
        reverse32[i] = value[31-i];
    end
  endfunction

  always_comb begin
    is_sub = fn_q[3];
    in2_inv = is_sub ? ~in2_q : in2_q;
    in1_xor_in2 = in1_q ^ in2_inv;
    adder_out = in1_q + in2_inv + is_sub;

    slt = in1_q[31] == in2_q[31]
      ? adder_out[31]
      : (fn_q[1] ? in2_q[31] : in1_q[31]);
    cmp_out = fn_q[0] ^
      ((!fn_q[3]) ? (in1_xor_in2 == 32'b0) : slt);

    shamt = in2_q[4:0];
    shin = (fn_q == 4'd5 || fn_q == 4'd11)
      ? in1_q
      : reverse32(in1_q);
    signed_shin = $signed({is_sub & shin[31], shin});
    shout_r = signed_shin >>> shamt;
    shout_l = reverse32(shout_r);
    shout = ((fn_q == 4'd5 || fn_q == 4'd11) ? shout_r : 32'b0) |
      ((fn_q == 4'd1) ? shout_l : 32'b0);

    logic_result =
      ((fn_q == 4'd4 || fn_q == 4'd6) ? in1_xor_in2 : 32'b0) |
      ((fn_q == 4'd6 || fn_q == 4'd7) ? (in1_q & in2_q) : 32'b0);
    shift_logic = ((fn_q >= 4'd12 && slt) ? 32'd1 : 32'b0) |
      logic_result | shout;
    alu_out = (fn_q == 4'd0 || fn_q == 4'd10)
      ? adder_out
      : shift_logic;
  end

  always_ff @(posedge clock) begin
    fn_q <= fn;
    in1_q <= in1;
    in2_q <= in2;
    out_q <= alu_out;
  end

  assign out = out_q;
endmodule
