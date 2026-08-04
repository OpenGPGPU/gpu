module BoothEncoderF64F32F16 (
	io_in_a,
	io_in_b,
	io_is_fp64,
	io_is_fp32,
	io_out_pp_0,
	io_out_pp_1,
	io_out_pp_2,
	io_out_pp_3,
	io_out_pp_4,
	io_out_pp_5,
	io_out_pp_6,
	io_out_pp_7,
	io_out_pp_8,
	io_out_pp_9,
	io_out_pp_10,
	io_out_pp_11,
	io_out_pp_12,
	io_out_pp_13,
	io_out_pp_14,
	io_out_pp_15,
	io_out_pp_16,
	io_out_pp_17,
	io_out_pp_18,
	io_out_pp_19,
	io_out_pp_20,
	io_out_pp_21,
	io_out_pp_22,
	io_out_pp_23,
	io_out_pp_24,
	io_out_pp_25,
	io_out_pp_26
);
	input [52:0] io_in_a;
	input [52:0] io_in_b;
	input io_is_fp64;
	input io_is_fp32;
	output wire [106:0] io_out_pp_0;
	output wire [106:0] io_out_pp_1;
	output wire [106:0] io_out_pp_2;
	output wire [106:0] io_out_pp_3;
	output wire [106:0] io_out_pp_4;
	output wire [106:0] io_out_pp_5;
	output wire [106:0] io_out_pp_6;
	output wire [106:0] io_out_pp_7;
	output wire [106:0] io_out_pp_8;
	output wire [106:0] io_out_pp_9;
	output wire [106:0] io_out_pp_10;
	output wire [106:0] io_out_pp_11;
	output wire [106:0] io_out_pp_12;
	output wire [106:0] io_out_pp_13;
	output wire [106:0] io_out_pp_14;
	output wire [106:0] io_out_pp_15;
	output wire [106:0] io_out_pp_16;
	output wire [106:0] io_out_pp_17;
	output wire [106:0] io_out_pp_18;
	output wire [106:0] io_out_pp_19;
	output wire [106:0] io_out_pp_20;
	output wire [106:0] io_out_pp_21;
	output wire [106:0] io_out_pp_22;
	output wire [106:0] io_out_pp_23;
	output wire [106:0] io_out_pp_24;
	output wire [106:0] io_out_pp_25;
	output wire [106:0] io_out_pp_26;
	wire [52:0] _GEN = (io_is_fp64 ? io_in_b : (io_is_fp32 ? {29'h00000000, io_in_b[23:0]} : {42'h00000000000, io_in_b[10:0]}));
	wire [3:0] booth_4bit_onehot_0 = (_GEN[1:0] == 2'h1 ? 4'h8 : (_GEN[1:0] == 2'h2 ? 4'h1 : {2'h0, &_GEN[1:0], 1'h0}));
	wire [3:0] booth_4bit_onehot_1 = ((_GEN[3:1] == 3'h1) | (_GEN[3:1] == 3'h2) ? 4'h8 : (_GEN[3:1] == 3'h3 ? 4'h4 : (_GEN[3:1] == 3'h4 ? 4'h1 : {2'h0, (_GEN[3:1] == 3'h5) | (_GEN[3:1] == 3'h6), 1'h0})));
	wire [3:0] booth_4bit_onehot_2 = ((_GEN[5:3] == 3'h1) | (_GEN[5:3] == 3'h2) ? 4'h8 : (_GEN[5:3] == 3'h3 ? 4'h4 : (_GEN[5:3] == 3'h4 ? 4'h1 : {2'h0, (_GEN[5:3] == 3'h5) | (_GEN[5:3] == 3'h6), 1'h0})));
	wire [3:0] booth_4bit_onehot_3 = ((_GEN[7:5] == 3'h1) | (_GEN[7:5] == 3'h2) ? 4'h8 : (_GEN[7:5] == 3'h3 ? 4'h4 : (_GEN[7:5] == 3'h4 ? 4'h1 : {2'h0, (_GEN[7:5] == 3'h5) | (_GEN[7:5] == 3'h6), 1'h0})));
	wire [3:0] booth_4bit_onehot_4 = ((_GEN[9:7] == 3'h1) | (_GEN[9:7] == 3'h2) ? 4'h8 : (_GEN[9:7] == 3'h3 ? 4'h4 : (_GEN[9:7] == 3'h4 ? 4'h1 : {2'h0, (_GEN[9:7] == 3'h5) | (_GEN[9:7] == 3'h6), 1'h0})));
	wire [3:0] booth_4bit_onehot_5 = ((_GEN[11:9] == 3'h1) | (_GEN[11:9] == 3'h2) ? 4'h8 : (_GEN[11:9] == 3'h3 ? 4'h4 : (_GEN[11:9] == 3'h4 ? 4'h1 : {2'h0, (_GEN[11:9] == 3'h5) | (_GEN[11:9] == 3'h6), 1'h0})));
	wire [3:0] booth_4bit_onehot_6 = ((_GEN[13:11] == 3'h1) | (_GEN[13:11] == 3'h2) ? 4'h8 : (_GEN[13:11] == 3'h3 ? 4'h4 : (_GEN[13:11] == 3'h4 ? 4'h1 : {2'h0, (_GEN[13:11] == 3'h5) | (_GEN[13:11] == 3'h6), 1'h0})));
	wire [3:0] booth_4bit_onehot_7 = ((_GEN[15:13] == 3'h1) | (_GEN[15:13] == 3'h2) ? 4'h8 : (_GEN[15:13] == 3'h3 ? 4'h4 : (_GEN[15:13] == 3'h4 ? 4'h1 : {2'h0, (_GEN[15:13] == 3'h5) | (_GEN[15:13] == 3'h6), 1'h0})));
	wire [3:0] booth_4bit_onehot_8 = ((_GEN[17:15] == 3'h1) | (_GEN[17:15] == 3'h2) ? 4'h8 : (_GEN[17:15] == 3'h3 ? 4'h4 : (_GEN[17:15] == 3'h4 ? 4'h1 : {2'h0, (_GEN[17:15] == 3'h5) | (_GEN[17:15] == 3'h6), 1'h0})));
	wire [3:0] booth_4bit_onehot_9 = ((_GEN[19:17] == 3'h1) | (_GEN[19:17] == 3'h2) ? 4'h8 : (_GEN[19:17] == 3'h3 ? 4'h4 : (_GEN[19:17] == 3'h4 ? 4'h1 : {2'h0, (_GEN[19:17] == 3'h5) | (_GEN[19:17] == 3'h6), 1'h0})));
	wire [3:0] booth_4bit_onehot_10 = ((_GEN[21:19] == 3'h1) | (_GEN[21:19] == 3'h2) ? 4'h8 : (_GEN[21:19] == 3'h3 ? 4'h4 : (_GEN[21:19] == 3'h4 ? 4'h1 : {2'h0, (_GEN[21:19] == 3'h5) | (_GEN[21:19] == 3'h6), 1'h0})));
	wire [3:0] booth_4bit_onehot_11 = ((_GEN[23:21] == 3'h1) | (_GEN[23:21] == 3'h2) ? 4'h8 : (_GEN[23:21] == 3'h3 ? 4'h4 : (_GEN[23:21] == 3'h4 ? 4'h1 : {2'h0, (_GEN[23:21] == 3'h5) | (_GEN[23:21] == 3'h6), 1'h0})));
	wire [3:0] booth_4bit_onehot_12 = ((_GEN[25:23] == 3'h1) | (_GEN[25:23] == 3'h2) ? 4'h8 : (_GEN[25:23] == 3'h3 ? 4'h4 : (_GEN[25:23] == 3'h4 ? 4'h1 : {2'h0, (_GEN[25:23] == 3'h5) | (_GEN[25:23] == 3'h6), 1'h0})));
	wire [3:0] booth_4bit_onehot_13 = ((_GEN[27:25] == 3'h1) | (_GEN[27:25] == 3'h2) ? 4'h8 : (_GEN[27:25] == 3'h3 ? 4'h4 : (_GEN[27:25] == 3'h4 ? 4'h1 : {2'h0, (_GEN[27:25] == 3'h5) | (_GEN[27:25] == 3'h6), 1'h0})));
	wire [3:0] booth_4bit_onehot_14 = ((_GEN[29:27] == 3'h1) | (_GEN[29:27] == 3'h2) ? 4'h8 : (_GEN[29:27] == 3'h3 ? 4'h4 : (_GEN[29:27] == 3'h4 ? 4'h1 : {2'h0, (_GEN[29:27] == 3'h5) | (_GEN[29:27] == 3'h6), 1'h0})));
	wire [3:0] booth_4bit_onehot_15 = ((_GEN[31:29] == 3'h1) | (_GEN[31:29] == 3'h2) ? 4'h8 : (_GEN[31:29] == 3'h3 ? 4'h4 : (_GEN[31:29] == 3'h4 ? 4'h1 : {2'h0, (_GEN[31:29] == 3'h5) | (_GEN[31:29] == 3'h6), 1'h0})));
	wire [3:0] booth_4bit_onehot_16 = ((_GEN[33:31] == 3'h1) | (_GEN[33:31] == 3'h2) ? 4'h8 : (_GEN[33:31] == 3'h3 ? 4'h4 : (_GEN[33:31] == 3'h4 ? 4'h1 : {2'h0, (_GEN[33:31] == 3'h5) | (_GEN[33:31] == 3'h6), 1'h0})));
	wire [3:0] booth_4bit_onehot_17 = ((_GEN[35:33] == 3'h1) | (_GEN[35:33] == 3'h2) ? 4'h8 : (_GEN[35:33] == 3'h3 ? 4'h4 : (_GEN[35:33] == 3'h4 ? 4'h1 : {2'h0, (_GEN[35:33] == 3'h5) | (_GEN[35:33] == 3'h6), 1'h0})));
	wire [3:0] booth_4bit_onehot_18 = ((_GEN[37:35] == 3'h1) | (_GEN[37:35] == 3'h2) ? 4'h8 : (_GEN[37:35] == 3'h3 ? 4'h4 : (_GEN[37:35] == 3'h4 ? 4'h1 : {2'h0, (_GEN[37:35] == 3'h5) | (_GEN[37:35] == 3'h6), 1'h0})));
	wire [3:0] booth_4bit_onehot_19 = ((_GEN[39:37] == 3'h1) | (_GEN[39:37] == 3'h2) ? 4'h8 : (_GEN[39:37] == 3'h3 ? 4'h4 : (_GEN[39:37] == 3'h4 ? 4'h1 : {2'h0, (_GEN[39:37] == 3'h5) | (_GEN[39:37] == 3'h6), 1'h0})));
	wire [3:0] booth_4bit_onehot_20 = ((_GEN[41:39] == 3'h1) | (_GEN[41:39] == 3'h2) ? 4'h8 : (_GEN[41:39] == 3'h3 ? 4'h4 : (_GEN[41:39] == 3'h4 ? 4'h1 : {2'h0, (_GEN[41:39] == 3'h5) | (_GEN[41:39] == 3'h6), 1'h0})));
	wire [3:0] booth_4bit_onehot_21 = ((_GEN[43:41] == 3'h1) | (_GEN[43:41] == 3'h2) ? 4'h8 : (_GEN[43:41] == 3'h3 ? 4'h4 : (_GEN[43:41] == 3'h4 ? 4'h1 : {2'h0, (_GEN[43:41] == 3'h5) | (_GEN[43:41] == 3'h6), 1'h0})));
	wire [3:0] booth_4bit_onehot_22 = ((_GEN[45:43] == 3'h1) | (_GEN[45:43] == 3'h2) ? 4'h8 : (_GEN[45:43] == 3'h3 ? 4'h4 : (_GEN[45:43] == 3'h4 ? 4'h1 : {2'h0, (_GEN[45:43] == 3'h5) | (_GEN[45:43] == 3'h6), 1'h0})));
	wire [3:0] booth_4bit_onehot_23 = ((_GEN[47:45] == 3'h1) | (_GEN[47:45] == 3'h2) ? 4'h8 : (_GEN[47:45] == 3'h3 ? 4'h4 : (_GEN[47:45] == 3'h4 ? 4'h1 : {2'h0, (_GEN[47:45] == 3'h5) | (_GEN[47:45] == 3'h6), 1'h0})));
	wire [3:0] booth_4bit_onehot_24 = ((_GEN[49:47] == 3'h1) | (_GEN[49:47] == 3'h2) ? 4'h8 : (_GEN[49:47] == 3'h3 ? 4'h4 : (_GEN[49:47] == 3'h4 ? 4'h1 : {2'h0, (_GEN[49:47] == 3'h5) | (_GEN[49:47] == 3'h6), 1'h0})));
	wire [3:0] booth_4bit_onehot_25 = ((_GEN[51:49] == 3'h1) | (_GEN[51:49] == 3'h2) ? 4'h8 : (_GEN[51:49] == 3'h3 ? 4'h4 : (_GEN[51:49] == 3'h4 ? 4'h1 : {2'h0, (_GEN[51:49] == 3'h5) | (_GEN[51:49] == 3'h6), 1'h0})));
	wire [3:0] booth_4bit_onehot_26 = ((_GEN[52:51] == 2'h1) | (_GEN[52:51] == 2'h2) ? 4'h8 : {1'h0, &_GEN[52:51], 2'h0});
	wire sign_seq_0 = booth_4bit_onehot_0[1] | booth_4bit_onehot_0[0];
	wire [53:0] _pp_seq_f64_26_T_2 = {1'h0, io_in_a};
	wire [53:0] _pp_seq_f64_26_T_6 = {io_in_a, 1'h0};
	wire sign_seq_1 = booth_4bit_onehot_1[1] | booth_4bit_onehot_1[0];
	wire sign_seq_2 = booth_4bit_onehot_2[1] | booth_4bit_onehot_2[0];
	wire sign_seq_3 = booth_4bit_onehot_3[1] | booth_4bit_onehot_3[0];
	wire sign_seq_4 = booth_4bit_onehot_4[1] | booth_4bit_onehot_4[0];
	wire sign_seq_5 = booth_4bit_onehot_5[1] | booth_4bit_onehot_5[0];
	wire sign_seq_6 = booth_4bit_onehot_6[1] | booth_4bit_onehot_6[0];
	wire sign_seq_7 = booth_4bit_onehot_7[1] | booth_4bit_onehot_7[0];
	wire sign_seq_8 = booth_4bit_onehot_8[1] | booth_4bit_onehot_8[0];
	wire sign_seq_9 = booth_4bit_onehot_9[1] | booth_4bit_onehot_9[0];
	wire sign_seq_10 = booth_4bit_onehot_10[1] | booth_4bit_onehot_10[0];
	wire sign_seq_11 = booth_4bit_onehot_11[1] | booth_4bit_onehot_11[0];
	wire sign_seq_12 = booth_4bit_onehot_12[1] | booth_4bit_onehot_12[0];
	wire sign_seq_13 = booth_4bit_onehot_13[1] | booth_4bit_onehot_13[0];
	wire sign_seq_14 = booth_4bit_onehot_14[1] | booth_4bit_onehot_14[0];
	wire sign_seq_15 = booth_4bit_onehot_15[1] | booth_4bit_onehot_15[0];
	wire sign_seq_16 = booth_4bit_onehot_16[1] | booth_4bit_onehot_16[0];
	wire sign_seq_17 = booth_4bit_onehot_17[1] | booth_4bit_onehot_17[0];
	wire sign_seq_18 = booth_4bit_onehot_18[1] | booth_4bit_onehot_18[0];
	wire sign_seq_19 = booth_4bit_onehot_19[1] | booth_4bit_onehot_19[0];
	wire sign_seq_20 = booth_4bit_onehot_20[1] | booth_4bit_onehot_20[0];
	wire sign_seq_21 = booth_4bit_onehot_21[1] | booth_4bit_onehot_21[0];
	wire sign_seq_22 = booth_4bit_onehot_22[1] | booth_4bit_onehot_22[0];
	wire sign_seq_23 = booth_4bit_onehot_23[1] | booth_4bit_onehot_23[0];
	wire sign_seq_24 = booth_4bit_onehot_24[1] | booth_4bit_onehot_24[0];
	wire sign_seq_25 = booth_4bit_onehot_25[1] | booth_4bit_onehot_25[0];
	wire [1:0] _GEN_0 = {2 {sign_seq_0}};
	assign io_out_pp_0 = (io_is_fp64 ? {50'h0000000000000, ~sign_seq_0, _GEN_0, (((booth_4bit_onehot_0[3] ? _pp_seq_f64_26_T_2 : 54'h00000000000000) | (booth_4bit_onehot_0[2] ? _pp_seq_f64_26_T_6 : 54'h00000000000000)) | (booth_4bit_onehot_0[1] ? {1'h1, ~io_in_a} : 54'h00000000000000)) | (booth_4bit_onehot_0[0] ? {~io_in_a, 1'h1} : 54'h00000000000000)} : (io_is_fp32 ? {79'h00000000000000000000, ~sign_seq_0, _GEN_0, (((booth_4bit_onehot_0[3] ? {1'h0, io_in_a[23:0]} : 25'h0000000) | (booth_4bit_onehot_0[2] ? {io_in_a[23:0], 1'h0} : 25'h0000000)) | (booth_4bit_onehot_0[1] ? {1'h1, ~io_in_a[23:0]} : 25'h0000000)) | (booth_4bit_onehot_0[0] ? {~io_in_a[23:0], 1'h1} : 25'h0000000)} : {92'h00000000000000000000000, ~sign_seq_0, _GEN_0, (((booth_4bit_onehot_0[3] ? {1'h0, io_in_a[10:0]} : 12'h000) | (booth_4bit_onehot_0[2] ? {io_in_a[10:0], 1'h0} : 12'h000)) | (booth_4bit_onehot_0[1] ? {1'h1, ~io_in_a[10:0]} : 12'h000)) | (booth_4bit_onehot_0[0] ? {~io_in_a[10:0], 1'h1} : 12'h000)}));
	assign io_out_pp_1 = {(io_is_fp64 ? {50'h0000000000001, ~sign_seq_1, (((booth_4bit_onehot_1[3] ? _pp_seq_f64_26_T_2 : 54'h00000000000000) | (booth_4bit_onehot_1[2] ? _pp_seq_f64_26_T_6 : 54'h00000000000000)) | (booth_4bit_onehot_1[1] ? {1'h1, ~io_in_a} : 54'h00000000000000)) | (booth_4bit_onehot_1[0] ? {~io_in_a, 1'h1} : 54'h00000000000000)} : (io_is_fp32 ? {79'h00000000000000000001, ~sign_seq_1, (((booth_4bit_onehot_1[3] ? {1'h0, io_in_a[23:0]} : 25'h0000000) | (booth_4bit_onehot_1[2] ? {io_in_a[23:0], 1'h0} : 25'h0000000)) | (booth_4bit_onehot_1[1] ? {1'h1, ~io_in_a[23:0]} : 25'h0000000)) | (booth_4bit_onehot_1[0] ? {~io_in_a[23:0], 1'h1} : 25'h0000000)} : {92'h00000000000000000000001, ~sign_seq_1, (((booth_4bit_onehot_1[3] ? {1'h0, io_in_a[10:0]} : 12'h000) | (booth_4bit_onehot_1[2] ? {io_in_a[10:0], 1'h0} : 12'h000)) | (booth_4bit_onehot_1[1] ? {1'h1, ~io_in_a[10:0]} : 12'h000)) | (booth_4bit_onehot_1[0] ? {~io_in_a[10:0], 1'h1} : 12'h000)})), 1'h0, sign_seq_0};
	assign io_out_pp_2 = {(io_is_fp64 ? {48'h000000000001, ~sign_seq_2, (((booth_4bit_onehot_2[3] ? _pp_seq_f64_26_T_2 : 54'h00000000000000) | (booth_4bit_onehot_2[2] ? _pp_seq_f64_26_T_6 : 54'h00000000000000)) | (booth_4bit_onehot_2[1] ? {1'h1, ~io_in_a} : 54'h00000000000000)) | (booth_4bit_onehot_2[0] ? {~io_in_a, 1'h1} : 54'h00000000000000)} : (io_is_fp32 ? {77'h00000000000000000001, ~sign_seq_2, (((booth_4bit_onehot_2[3] ? {1'h0, io_in_a[23:0]} : 25'h0000000) | (booth_4bit_onehot_2[2] ? {io_in_a[23:0], 1'h0} : 25'h0000000)) | (booth_4bit_onehot_2[1] ? {1'h1, ~io_in_a[23:0]} : 25'h0000000)) | (booth_4bit_onehot_2[0] ? {~io_in_a[23:0], 1'h1} : 25'h0000000)} : {90'h00000000000000000000001, ~sign_seq_2, (((booth_4bit_onehot_2[3] ? {1'h0, io_in_a[10:0]} : 12'h000) | (booth_4bit_onehot_2[2] ? {io_in_a[10:0], 1'h0} : 12'h000)) | (booth_4bit_onehot_2[1] ? {1'h1, ~io_in_a[10:0]} : 12'h000)) | (booth_4bit_onehot_2[0] ? {~io_in_a[10:0], 1'h1} : 12'h000)})), 1'h0, sign_seq_1, 2'h0};
	assign io_out_pp_3 = {(io_is_fp64 ? {46'h000000000001, ~sign_seq_3, (((booth_4bit_onehot_3[3] ? _pp_seq_f64_26_T_2 : 54'h00000000000000) | (booth_4bit_onehot_3[2] ? _pp_seq_f64_26_T_6 : 54'h00000000000000)) | (booth_4bit_onehot_3[1] ? {1'h1, ~io_in_a} : 54'h00000000000000)) | (booth_4bit_onehot_3[0] ? {~io_in_a, 1'h1} : 54'h00000000000000)} : (io_is_fp32 ? {75'h0000000000000000001, ~sign_seq_3, (((booth_4bit_onehot_3[3] ? {1'h0, io_in_a[23:0]} : 25'h0000000) | (booth_4bit_onehot_3[2] ? {io_in_a[23:0], 1'h0} : 25'h0000000)) | (booth_4bit_onehot_3[1] ? {1'h1, ~io_in_a[23:0]} : 25'h0000000)) | (booth_4bit_onehot_3[0] ? {~io_in_a[23:0], 1'h1} : 25'h0000000)} : {88'h0000000000000000000001, ~sign_seq_3, (((booth_4bit_onehot_3[3] ? {1'h0, io_in_a[10:0]} : 12'h000) | (booth_4bit_onehot_3[2] ? {io_in_a[10:0], 1'h0} : 12'h000)) | (booth_4bit_onehot_3[1] ? {1'h1, ~io_in_a[10:0]} : 12'h000)) | (booth_4bit_onehot_3[0] ? {~io_in_a[10:0], 1'h1} : 12'h000)})), 1'h0, sign_seq_2, 4'h0};
	assign io_out_pp_4 = {(io_is_fp64 ? {44'h00000000001, ~sign_seq_4, (((booth_4bit_onehot_4[3] ? _pp_seq_f64_26_T_2 : 54'h00000000000000) | (booth_4bit_onehot_4[2] ? _pp_seq_f64_26_T_6 : 54'h00000000000000)) | (booth_4bit_onehot_4[1] ? {1'h1, ~io_in_a} : 54'h00000000000000)) | (booth_4bit_onehot_4[0] ? {~io_in_a, 1'h1} : 54'h00000000000000)} : (io_is_fp32 ? {73'h0000000000000000001, ~sign_seq_4, (((booth_4bit_onehot_4[3] ? {1'h0, io_in_a[23:0]} : 25'h0000000) | (booth_4bit_onehot_4[2] ? {io_in_a[23:0], 1'h0} : 25'h0000000)) | (booth_4bit_onehot_4[1] ? {1'h1, ~io_in_a[23:0]} : 25'h0000000)) | (booth_4bit_onehot_4[0] ? {~io_in_a[23:0], 1'h1} : 25'h0000000)} : {86'h0000000000000000000001, ~sign_seq_4, (((booth_4bit_onehot_4[3] ? {1'h0, io_in_a[10:0]} : 12'h000) | (booth_4bit_onehot_4[2] ? {io_in_a[10:0], 1'h0} : 12'h000)) | (booth_4bit_onehot_4[1] ? {1'h1, ~io_in_a[10:0]} : 12'h000)) | (booth_4bit_onehot_4[0] ? {~io_in_a[10:0], 1'h1} : 12'h000)})), 1'h0, sign_seq_3, 6'h00};
	assign io_out_pp_5 = {(io_is_fp64 ? {42'h00000000001, ~sign_seq_5, (((booth_4bit_onehot_5[3] ? _pp_seq_f64_26_T_2 : 54'h00000000000000) | (booth_4bit_onehot_5[2] ? _pp_seq_f64_26_T_6 : 54'h00000000000000)) | (booth_4bit_onehot_5[1] ? {1'h1, ~io_in_a} : 54'h00000000000000)) | (booth_4bit_onehot_5[0] ? {~io_in_a, 1'h1} : 54'h00000000000000)} : (io_is_fp32 ? {71'h000000000000000001, ~sign_seq_5, (((booth_4bit_onehot_5[3] ? {1'h0, io_in_a[23:0]} : 25'h0000000) | (booth_4bit_onehot_5[2] ? {io_in_a[23:0], 1'h0} : 25'h0000000)) | (booth_4bit_onehot_5[1] ? {1'h1, ~io_in_a[23:0]} : 25'h0000000)) | (booth_4bit_onehot_5[0] ? {~io_in_a[23:0], 1'h1} : 25'h0000000)} : {85'h0000000000000000000001, (((booth_4bit_onehot_5[3] ? {1'h0, io_in_a[10:0]} : 12'h000) | (booth_4bit_onehot_5[2] ? {io_in_a[10:0], 1'h0} : 12'h000)) | (booth_4bit_onehot_5[1] ? {1'h1, ~io_in_a[10:0]} : 12'h000)) | (booth_4bit_onehot_5[0] ? {~io_in_a[10:0], 1'h1} : 12'h000)})), 1'h0, sign_seq_4, 8'h00};
	assign io_out_pp_6 = (io_is_fp64 ? {40'h0000000001, ~sign_seq_6, (((booth_4bit_onehot_6[3] ? _pp_seq_f64_26_T_2 : 54'h00000000000000) | (booth_4bit_onehot_6[2] ? _pp_seq_f64_26_T_6 : 54'h00000000000000)) | (booth_4bit_onehot_6[1] ? {1'h1, ~io_in_a} : 54'h00000000000000)) | (booth_4bit_onehot_6[0] ? {~io_in_a, 1'h1} : 54'h00000000000000), 1'h0, sign_seq_5, 10'h000} : (io_is_fp32 ? {69'h000000000000000001, ~sign_seq_6, (((booth_4bit_onehot_6[3] ? {1'h0, io_in_a[23:0]} : 25'h0000000) | (booth_4bit_onehot_6[2] ? {io_in_a[23:0], 1'h0} : 25'h0000000)) | (booth_4bit_onehot_6[1] ? {1'h1, ~io_in_a[23:0]} : 25'h0000000)) | (booth_4bit_onehot_6[0] ? {~io_in_a[23:0], 1'h1} : 25'h0000000), 1'h0, sign_seq_5, 10'h000} : 107'h000000000000000000000000000));
	assign io_out_pp_7 = (io_is_fp64 ? {38'h0000000001, ~sign_seq_7, (((booth_4bit_onehot_7[3] ? _pp_seq_f64_26_T_2 : 54'h00000000000000) | (booth_4bit_onehot_7[2] ? _pp_seq_f64_26_T_6 : 54'h00000000000000)) | (booth_4bit_onehot_7[1] ? {1'h1, ~io_in_a} : 54'h00000000000000)) | (booth_4bit_onehot_7[0] ? {~io_in_a, 1'h1} : 54'h00000000000000), 1'h0, sign_seq_6, 12'h000} : (io_is_fp32 ? {67'h00000000000000001, ~sign_seq_7, (((booth_4bit_onehot_7[3] ? {1'h0, io_in_a[23:0]} : 25'h0000000) | (booth_4bit_onehot_7[2] ? {io_in_a[23:0], 1'h0} : 25'h0000000)) | (booth_4bit_onehot_7[1] ? {1'h1, ~io_in_a[23:0]} : 25'h0000000)) | (booth_4bit_onehot_7[0] ? {~io_in_a[23:0], 1'h1} : 25'h0000000), 1'h0, sign_seq_6, 12'h000} : 107'h000000000000000000000000000));
	assign io_out_pp_8 = (io_is_fp64 ? {36'h000000001, ~sign_seq_8, (((booth_4bit_onehot_8[3] ? _pp_seq_f64_26_T_2 : 54'h00000000000000) | (booth_4bit_onehot_8[2] ? _pp_seq_f64_26_T_6 : 54'h00000000000000)) | (booth_4bit_onehot_8[1] ? {1'h1, ~io_in_a} : 54'h00000000000000)) | (booth_4bit_onehot_8[0] ? {~io_in_a, 1'h1} : 54'h00000000000000), 1'h0, sign_seq_7, 14'h0000} : (io_is_fp32 ? {65'h00000000000000001, ~sign_seq_8, (((booth_4bit_onehot_8[3] ? {1'h0, io_in_a[23:0]} : 25'h0000000) | (booth_4bit_onehot_8[2] ? {io_in_a[23:0], 1'h0} : 25'h0000000)) | (booth_4bit_onehot_8[1] ? {1'h1, ~io_in_a[23:0]} : 25'h0000000)) | (booth_4bit_onehot_8[0] ? {~io_in_a[23:0], 1'h1} : 25'h0000000), 1'h0, sign_seq_7, 14'h0000} : 107'h000000000000000000000000000));
	assign io_out_pp_9 = (io_is_fp64 ? {34'h000000001, ~sign_seq_9, (((booth_4bit_onehot_9[3] ? _pp_seq_f64_26_T_2 : 54'h00000000000000) | (booth_4bit_onehot_9[2] ? _pp_seq_f64_26_T_6 : 54'h00000000000000)) | (booth_4bit_onehot_9[1] ? {1'h1, ~io_in_a} : 54'h00000000000000)) | (booth_4bit_onehot_9[0] ? {~io_in_a, 1'h1} : 54'h00000000000000), 1'h0, sign_seq_8, 16'h0000} : (io_is_fp32 ? {63'h0000000000000001, ~sign_seq_9, (((booth_4bit_onehot_9[3] ? {1'h0, io_in_a[23:0]} : 25'h0000000) | (booth_4bit_onehot_9[2] ? {io_in_a[23:0], 1'h0} : 25'h0000000)) | (booth_4bit_onehot_9[1] ? {1'h1, ~io_in_a[23:0]} : 25'h0000000)) | (booth_4bit_onehot_9[0] ? {~io_in_a[23:0], 1'h1} : 25'h0000000), 1'h0, sign_seq_8, 16'h0000} : 107'h000000000000000000000000000));
	assign io_out_pp_10 = (io_is_fp64 ? {32'h00000001, ~sign_seq_10, (((booth_4bit_onehot_10[3] ? _pp_seq_f64_26_T_2 : 54'h00000000000000) | (booth_4bit_onehot_10[2] ? _pp_seq_f64_26_T_6 : 54'h00000000000000)) | (booth_4bit_onehot_10[1] ? {1'h1, ~io_in_a} : 54'h00000000000000)) | (booth_4bit_onehot_10[0] ? {~io_in_a, 1'h1} : 54'h00000000000000), 1'h0, sign_seq_9, 18'h00000} : (io_is_fp32 ? {61'h0000000000000001, ~sign_seq_10, (((booth_4bit_onehot_10[3] ? {1'h0, io_in_a[23:0]} : 25'h0000000) | (booth_4bit_onehot_10[2] ? {io_in_a[23:0], 1'h0} : 25'h0000000)) | (booth_4bit_onehot_10[1] ? {1'h1, ~io_in_a[23:0]} : 25'h0000000)) | (booth_4bit_onehot_10[0] ? {~io_in_a[23:0], 1'h1} : 25'h0000000), 1'h0, sign_seq_9, 18'h00000} : 107'h000000000000000000000000000));
	assign io_out_pp_11 = (io_is_fp64 ? {30'h00000001, ~sign_seq_11, (((booth_4bit_onehot_11[3] ? _pp_seq_f64_26_T_2 : 54'h00000000000000) | (booth_4bit_onehot_11[2] ? _pp_seq_f64_26_T_6 : 54'h00000000000000)) | (booth_4bit_onehot_11[1] ? {1'h1, ~io_in_a} : 54'h00000000000000)) | (booth_4bit_onehot_11[0] ? {~io_in_a, 1'h1} : 54'h00000000000000), 1'h0, sign_seq_10, 20'h00000} : (io_is_fp32 ? {59'h000000000000001, ~sign_seq_11, (((booth_4bit_onehot_11[3] ? {1'h0, io_in_a[23:0]} : 25'h0000000) | (booth_4bit_onehot_11[2] ? {io_in_a[23:0], 1'h0} : 25'h0000000)) | (booth_4bit_onehot_11[1] ? {1'h1, ~io_in_a[23:0]} : 25'h0000000)) | (booth_4bit_onehot_11[0] ? {~io_in_a[23:0], 1'h1} : 25'h0000000), 1'h0, sign_seq_10, 20'h00000} : 107'h000000000000000000000000000));
	assign io_out_pp_12 = (io_is_fp64 ? {28'h0000001, ~sign_seq_12, (((booth_4bit_onehot_12[3] ? _pp_seq_f64_26_T_2 : 54'h00000000000000) | (booth_4bit_onehot_12[2] ? _pp_seq_f64_26_T_6 : 54'h00000000000000)) | (booth_4bit_onehot_12[1] ? {1'h1, ~io_in_a} : 54'h00000000000000)) | (booth_4bit_onehot_12[0] ? {~io_in_a, 1'h1} : 54'h00000000000000), 1'h0, sign_seq_11, 22'h000000} : (io_is_fp32 ? {59'h000000000000000, (((booth_4bit_onehot_12[3] ? io_in_a[23:0] : 24'h000000) | (booth_4bit_onehot_12[2] ? {io_in_a[22:0], 1'h0} : 24'h000000)) | (booth_4bit_onehot_12[1] ? ~io_in_a[23:0] : 24'h000000)) | (booth_4bit_onehot_12[0] ? {~io_in_a[22:0], 1'h1} : 24'h000000), 1'h0, sign_seq_11, 22'h000000} : 107'h000000000000000000000000000));
	assign io_out_pp_13 = (io_is_fp64 ? {26'h0000001, ~sign_seq_13, (((booth_4bit_onehot_13[3] ? _pp_seq_f64_26_T_2 : 54'h00000000000000) | (booth_4bit_onehot_13[2] ? _pp_seq_f64_26_T_6 : 54'h00000000000000)) | (booth_4bit_onehot_13[1] ? {1'h1, ~io_in_a} : 54'h00000000000000)) | (booth_4bit_onehot_13[0] ? {~io_in_a, 1'h1} : 54'h00000000000000), 1'h0, sign_seq_12, 24'h000000} : 107'h000000000000000000000000000);
	assign io_out_pp_14 = (io_is_fp64 ? {24'h000001, ~sign_seq_14, (((booth_4bit_onehot_14[3] ? _pp_seq_f64_26_T_2 : 54'h00000000000000) | (booth_4bit_onehot_14[2] ? _pp_seq_f64_26_T_6 : 54'h00000000000000)) | (booth_4bit_onehot_14[1] ? {1'h1, ~io_in_a} : 54'h00000000000000)) | (booth_4bit_onehot_14[0] ? {~io_in_a, 1'h1} : 54'h00000000000000), 1'h0, sign_seq_13, 26'h0000000} : 107'h000000000000000000000000000);
	assign io_out_pp_15 = (io_is_fp64 ? {22'h000001, ~sign_seq_15, (((booth_4bit_onehot_15[3] ? _pp_seq_f64_26_T_2 : 54'h00000000000000) | (booth_4bit_onehot_15[2] ? _pp_seq_f64_26_T_6 : 54'h00000000000000)) | (booth_4bit_onehot_15[1] ? {1'h1, ~io_in_a} : 54'h00000000000000)) | (booth_4bit_onehot_15[0] ? {~io_in_a, 1'h1} : 54'h00000000000000), 1'h0, sign_seq_14, 28'h0000000} : 107'h000000000000000000000000000);
	assign io_out_pp_16 = (io_is_fp64 ? {20'h00001, ~sign_seq_16, (((booth_4bit_onehot_16[3] ? _pp_seq_f64_26_T_2 : 54'h00000000000000) | (booth_4bit_onehot_16[2] ? _pp_seq_f64_26_T_6 : 54'h00000000000000)) | (booth_4bit_onehot_16[1] ? {1'h1, ~io_in_a} : 54'h00000000000000)) | (booth_4bit_onehot_16[0] ? {~io_in_a, 1'h1} : 54'h00000000000000), 1'h0, sign_seq_15, 30'h00000000} : 107'h000000000000000000000000000);
	assign io_out_pp_17 = (io_is_fp64 ? {18'h00001, ~sign_seq_17, (((booth_4bit_onehot_17[3] ? _pp_seq_f64_26_T_2 : 54'h00000000000000) | (booth_4bit_onehot_17[2] ? _pp_seq_f64_26_T_6 : 54'h00000000000000)) | (booth_4bit_onehot_17[1] ? {1'h1, ~io_in_a} : 54'h00000000000000)) | (booth_4bit_onehot_17[0] ? {~io_in_a, 1'h1} : 54'h00000000000000), 1'h0, sign_seq_16, 32'h00000000} : 107'h000000000000000000000000000);
	assign io_out_pp_18 = (io_is_fp64 ? {16'h0001, ~sign_seq_18, (((booth_4bit_onehot_18[3] ? _pp_seq_f64_26_T_2 : 54'h00000000000000) | (booth_4bit_onehot_18[2] ? _pp_seq_f64_26_T_6 : 54'h00000000000000)) | (booth_4bit_onehot_18[1] ? {1'h1, ~io_in_a} : 54'h00000000000000)) | (booth_4bit_onehot_18[0] ? {~io_in_a, 1'h1} : 54'h00000000000000), 1'h0, sign_seq_17, 34'h000000000} : 107'h000000000000000000000000000);
	assign io_out_pp_19 = (io_is_fp64 ? {14'h0001, ~sign_seq_19, (((booth_4bit_onehot_19[3] ? _pp_seq_f64_26_T_2 : 54'h00000000000000) | (booth_4bit_onehot_19[2] ? _pp_seq_f64_26_T_6 : 54'h00000000000000)) | (booth_4bit_onehot_19[1] ? {1'h1, ~io_in_a} : 54'h00000000000000)) | (booth_4bit_onehot_19[0] ? {~io_in_a, 1'h1} : 54'h00000000000000), 1'h0, sign_seq_18, 36'h000000000} : 107'h000000000000000000000000000);
	assign io_out_pp_20 = (io_is_fp64 ? {12'h001, ~sign_seq_20, (((booth_4bit_onehot_20[3] ? _pp_seq_f64_26_T_2 : 54'h00000000000000) | (booth_4bit_onehot_20[2] ? _pp_seq_f64_26_T_6 : 54'h00000000000000)) | (booth_4bit_onehot_20[1] ? {1'h1, ~io_in_a} : 54'h00000000000000)) | (booth_4bit_onehot_20[0] ? {~io_in_a, 1'h1} : 54'h00000000000000), 1'h0, sign_seq_19, 38'h0000000000} : 107'h000000000000000000000000000);
	assign io_out_pp_21 = (io_is_fp64 ? {10'h001, ~sign_seq_21, (((booth_4bit_onehot_21[3] ? _pp_seq_f64_26_T_2 : 54'h00000000000000) | (booth_4bit_onehot_21[2] ? _pp_seq_f64_26_T_6 : 54'h00000000000000)) | (booth_4bit_onehot_21[1] ? {1'h1, ~io_in_a} : 54'h00000000000000)) | (booth_4bit_onehot_21[0] ? {~io_in_a, 1'h1} : 54'h00000000000000), 1'h0, sign_seq_20, 40'h0000000000} : 107'h000000000000000000000000000);
	assign io_out_pp_22 = (io_is_fp64 ? {8'h01, ~sign_seq_22, (((booth_4bit_onehot_22[3] ? _pp_seq_f64_26_T_2 : 54'h00000000000000) | (booth_4bit_onehot_22[2] ? _pp_seq_f64_26_T_6 : 54'h00000000000000)) | (booth_4bit_onehot_22[1] ? {1'h1, ~io_in_a} : 54'h00000000000000)) | (booth_4bit_onehot_22[0] ? {~io_in_a, 1'h1} : 54'h00000000000000), 1'h0, sign_seq_21, 42'h00000000000} : 107'h000000000000000000000000000);
	assign io_out_pp_23 = (io_is_fp64 ? {6'h01, ~sign_seq_23, (((booth_4bit_onehot_23[3] ? _pp_seq_f64_26_T_2 : 54'h00000000000000) | (booth_4bit_onehot_23[2] ? _pp_seq_f64_26_T_6 : 54'h00000000000000)) | (booth_4bit_onehot_23[1] ? {1'h1, ~io_in_a} : 54'h00000000000000)) | (booth_4bit_onehot_23[0] ? {~io_in_a, 1'h1} : 54'h00000000000000), 1'h0, sign_seq_22, 44'h00000000000} : 107'h000000000000000000000000000);
	assign io_out_pp_24 = (io_is_fp64 ? {4'h1, ~sign_seq_24, (((booth_4bit_onehot_24[3] ? _pp_seq_f64_26_T_2 : 54'h00000000000000) | (booth_4bit_onehot_24[2] ? _pp_seq_f64_26_T_6 : 54'h00000000000000)) | (booth_4bit_onehot_24[1] ? {1'h1, ~io_in_a} : 54'h00000000000000)) | (booth_4bit_onehot_24[0] ? {~io_in_a, 1'h1} : 54'h00000000000000), 1'h0, sign_seq_23, 46'h000000000000} : 107'h000000000000000000000000000);
	assign io_out_pp_25 = (io_is_fp64 ? {2'h1, ~sign_seq_25, (((booth_4bit_onehot_25[3] ? _pp_seq_f64_26_T_2 : 54'h00000000000000) | (booth_4bit_onehot_25[2] ? _pp_seq_f64_26_T_6 : 54'h00000000000000)) | (booth_4bit_onehot_25[1] ? {1'h1, ~io_in_a} : 54'h00000000000000)) | (booth_4bit_onehot_25[0] ? {~io_in_a, 1'h1} : 54'h00000000000000), 1'h0, sign_seq_24, 48'h000000000000} : 107'h000000000000000000000000000);
	assign io_out_pp_26 = (io_is_fp64 ? {1'h1, (((booth_4bit_onehot_26[3] ? _pp_seq_f64_26_T_2 : 54'h00000000000000) | (booth_4bit_onehot_26[2] ? _pp_seq_f64_26_T_6 : 54'h00000000000000)) | (booth_4bit_onehot_26[1] ? {1'h1, ~io_in_a} : 54'h00000000000000)) | (booth_4bit_onehot_26[0] ? {~io_in_a, 1'h1} : 54'h00000000000000), 1'h0, sign_seq_25, 50'h0000000000000} : 107'h000000000000000000000000000);
endmodule
module CSA3to2 (
	io_in_a,
	io_in_b,
	io_in_c,
	io_out_sum,
	io_out_car
);
	input [106:0] io_in_a;
	input [106:0] io_in_b;
	input [106:0] io_in_c;
	output wire [106:0] io_out_sum;
	output wire [106:0] io_out_car;
	assign io_out_sum = (io_in_a ^ io_in_b) ^ io_in_c;
	assign io_out_car = {((io_in_a[105:0] & io_in_b[105:0]) | (io_in_a[105:0] & io_in_c[105:0])) | (io_in_b[105:0] & io_in_c[105:0]), 1'h0};
endmodule
module CSA4to2 (
	io_in_a,
	io_in_b,
	io_in_c,
	io_in_d,
	io_out_sum,
	io_out_car
);
	input [106:0] io_in_a;
	input [106:0] io_in_b;
	input [106:0] io_in_c;
	input [106:0] io_in_d;
	output wire [106:0] io_out_sum;
	output wire [106:0] io_out_car;
	wire cout_vec_0 = (io_in_a[0] ^ io_in_b[0] ? io_in_c[0] : io_in_a[0]);
	wire cout_vec_1 = (io_in_a[1] ^ io_in_b[1] ? io_in_c[1] : io_in_a[1]);
	wire cout_vec_2 = (io_in_a[2] ^ io_in_b[2] ? io_in_c[2] : io_in_a[2]);
	wire cout_vec_3 = (io_in_a[3] ^ io_in_b[3] ? io_in_c[3] : io_in_a[3]);
	wire cout_vec_4 = (io_in_a[4] ^ io_in_b[4] ? io_in_c[4] : io_in_a[4]);
	wire cout_vec_5 = (io_in_a[5] ^ io_in_b[5] ? io_in_c[5] : io_in_a[5]);
	wire cout_vec_6 = (io_in_a[6] ^ io_in_b[6] ? io_in_c[6] : io_in_a[6]);
	wire cout_vec_7 = (io_in_a[7] ^ io_in_b[7] ? io_in_c[7] : io_in_a[7]);
	wire cout_vec_8 = (io_in_a[8] ^ io_in_b[8] ? io_in_c[8] : io_in_a[8]);
	wire cout_vec_9 = (io_in_a[9] ^ io_in_b[9] ? io_in_c[9] : io_in_a[9]);
	wire cout_vec_10 = (io_in_a[10] ^ io_in_b[10] ? io_in_c[10] : io_in_a[10]);
	wire cout_vec_11 = (io_in_a[11] ^ io_in_b[11] ? io_in_c[11] : io_in_a[11]);
	wire cout_vec_12 = (io_in_a[12] ^ io_in_b[12] ? io_in_c[12] : io_in_a[12]);
	wire cout_vec_13 = (io_in_a[13] ^ io_in_b[13] ? io_in_c[13] : io_in_a[13]);
	wire cout_vec_14 = (io_in_a[14] ^ io_in_b[14] ? io_in_c[14] : io_in_a[14]);
	wire cout_vec_15 = (io_in_a[15] ^ io_in_b[15] ? io_in_c[15] : io_in_a[15]);
	wire cout_vec_16 = (io_in_a[16] ^ io_in_b[16] ? io_in_c[16] : io_in_a[16]);
	wire cout_vec_17 = (io_in_a[17] ^ io_in_b[17] ? io_in_c[17] : io_in_a[17]);
	wire cout_vec_18 = (io_in_a[18] ^ io_in_b[18] ? io_in_c[18] : io_in_a[18]);
	wire cout_vec_19 = (io_in_a[19] ^ io_in_b[19] ? io_in_c[19] : io_in_a[19]);
	wire cout_vec_20 = (io_in_a[20] ^ io_in_b[20] ? io_in_c[20] : io_in_a[20]);
	wire cout_vec_21 = (io_in_a[21] ^ io_in_b[21] ? io_in_c[21] : io_in_a[21]);
	wire cout_vec_22 = (io_in_a[22] ^ io_in_b[22] ? io_in_c[22] : io_in_a[22]);
	wire cout_vec_23 = (io_in_a[23] ^ io_in_b[23] ? io_in_c[23] : io_in_a[23]);
	wire cout_vec_24 = (io_in_a[24] ^ io_in_b[24] ? io_in_c[24] : io_in_a[24]);
	wire cout_vec_25 = (io_in_a[25] ^ io_in_b[25] ? io_in_c[25] : io_in_a[25]);
	wire cout_vec_26 = (io_in_a[26] ^ io_in_b[26] ? io_in_c[26] : io_in_a[26]);
	wire cout_vec_27 = (io_in_a[27] ^ io_in_b[27] ? io_in_c[27] : io_in_a[27]);
	wire cout_vec_28 = (io_in_a[28] ^ io_in_b[28] ? io_in_c[28] : io_in_a[28]);
	wire cout_vec_29 = (io_in_a[29] ^ io_in_b[29] ? io_in_c[29] : io_in_a[29]);
	wire cout_vec_30 = (io_in_a[30] ^ io_in_b[30] ? io_in_c[30] : io_in_a[30]);
	wire cout_vec_31 = (io_in_a[31] ^ io_in_b[31] ? io_in_c[31] : io_in_a[31]);
	wire cout_vec_32 = (io_in_a[32] ^ io_in_b[32] ? io_in_c[32] : io_in_a[32]);
	wire cout_vec_33 = (io_in_a[33] ^ io_in_b[33] ? io_in_c[33] : io_in_a[33]);
	wire cout_vec_34 = (io_in_a[34] ^ io_in_b[34] ? io_in_c[34] : io_in_a[34]);
	wire cout_vec_35 = (io_in_a[35] ^ io_in_b[35] ? io_in_c[35] : io_in_a[35]);
	wire cout_vec_36 = (io_in_a[36] ^ io_in_b[36] ? io_in_c[36] : io_in_a[36]);
	wire cout_vec_37 = (io_in_a[37] ^ io_in_b[37] ? io_in_c[37] : io_in_a[37]);
	wire cout_vec_38 = (io_in_a[38] ^ io_in_b[38] ? io_in_c[38] : io_in_a[38]);
	wire cout_vec_39 = (io_in_a[39] ^ io_in_b[39] ? io_in_c[39] : io_in_a[39]);
	wire cout_vec_40 = (io_in_a[40] ^ io_in_b[40] ? io_in_c[40] : io_in_a[40]);
	wire cout_vec_41 = (io_in_a[41] ^ io_in_b[41] ? io_in_c[41] : io_in_a[41]);
	wire cout_vec_42 = (io_in_a[42] ^ io_in_b[42] ? io_in_c[42] : io_in_a[42]);
	wire cout_vec_43 = (io_in_a[43] ^ io_in_b[43] ? io_in_c[43] : io_in_a[43]);
	wire cout_vec_44 = (io_in_a[44] ^ io_in_b[44] ? io_in_c[44] : io_in_a[44]);
	wire cout_vec_45 = (io_in_a[45] ^ io_in_b[45] ? io_in_c[45] : io_in_a[45]);
	wire cout_vec_46 = (io_in_a[46] ^ io_in_b[46] ? io_in_c[46] : io_in_a[46]);
	wire cout_vec_47 = (io_in_a[47] ^ io_in_b[47] ? io_in_c[47] : io_in_a[47]);
	wire cout_vec_48 = (io_in_a[48] ^ io_in_b[48] ? io_in_c[48] : io_in_a[48]);
	wire cout_vec_49 = (io_in_a[49] ^ io_in_b[49] ? io_in_c[49] : io_in_a[49]);
	wire cout_vec_50 = (io_in_a[50] ^ io_in_b[50] ? io_in_c[50] : io_in_a[50]);
	wire cout_vec_51 = (io_in_a[51] ^ io_in_b[51] ? io_in_c[51] : io_in_a[51]);
	wire cout_vec_52 = (io_in_a[52] ^ io_in_b[52] ? io_in_c[52] : io_in_a[52]);
	wire cout_vec_53 = (io_in_a[53] ^ io_in_b[53] ? io_in_c[53] : io_in_a[53]);
	wire cout_vec_54 = (io_in_a[54] ^ io_in_b[54] ? io_in_c[54] : io_in_a[54]);
	wire cout_vec_55 = (io_in_a[55] ^ io_in_b[55] ? io_in_c[55] : io_in_a[55]);
	wire cout_vec_56 = (io_in_a[56] ^ io_in_b[56] ? io_in_c[56] : io_in_a[56]);
	wire cout_vec_57 = (io_in_a[57] ^ io_in_b[57] ? io_in_c[57] : io_in_a[57]);
	wire cout_vec_58 = (io_in_a[58] ^ io_in_b[58] ? io_in_c[58] : io_in_a[58]);
	wire cout_vec_59 = (io_in_a[59] ^ io_in_b[59] ? io_in_c[59] : io_in_a[59]);
	wire cout_vec_60 = (io_in_a[60] ^ io_in_b[60] ? io_in_c[60] : io_in_a[60]);
	wire cout_vec_61 = (io_in_a[61] ^ io_in_b[61] ? io_in_c[61] : io_in_a[61]);
	wire cout_vec_62 = (io_in_a[62] ^ io_in_b[62] ? io_in_c[62] : io_in_a[62]);
	wire cout_vec_63 = (io_in_a[63] ^ io_in_b[63] ? io_in_c[63] : io_in_a[63]);
	wire cout_vec_64 = (io_in_a[64] ^ io_in_b[64] ? io_in_c[64] : io_in_a[64]);
	wire cout_vec_65 = (io_in_a[65] ^ io_in_b[65] ? io_in_c[65] : io_in_a[65]);
	wire cout_vec_66 = (io_in_a[66] ^ io_in_b[66] ? io_in_c[66] : io_in_a[66]);
	wire cout_vec_67 = (io_in_a[67] ^ io_in_b[67] ? io_in_c[67] : io_in_a[67]);
	wire cout_vec_68 = (io_in_a[68] ^ io_in_b[68] ? io_in_c[68] : io_in_a[68]);
	wire cout_vec_69 = (io_in_a[69] ^ io_in_b[69] ? io_in_c[69] : io_in_a[69]);
	wire cout_vec_70 = (io_in_a[70] ^ io_in_b[70] ? io_in_c[70] : io_in_a[70]);
	wire cout_vec_71 = (io_in_a[71] ^ io_in_b[71] ? io_in_c[71] : io_in_a[71]);
	wire cout_vec_72 = (io_in_a[72] ^ io_in_b[72] ? io_in_c[72] : io_in_a[72]);
	wire cout_vec_73 = (io_in_a[73] ^ io_in_b[73] ? io_in_c[73] : io_in_a[73]);
	wire cout_vec_74 = (io_in_a[74] ^ io_in_b[74] ? io_in_c[74] : io_in_a[74]);
	wire cout_vec_75 = (io_in_a[75] ^ io_in_b[75] ? io_in_c[75] : io_in_a[75]);
	wire cout_vec_76 = (io_in_a[76] ^ io_in_b[76] ? io_in_c[76] : io_in_a[76]);
	wire cout_vec_77 = (io_in_a[77] ^ io_in_b[77] ? io_in_c[77] : io_in_a[77]);
	wire cout_vec_78 = (io_in_a[78] ^ io_in_b[78] ? io_in_c[78] : io_in_a[78]);
	wire cout_vec_79 = (io_in_a[79] ^ io_in_b[79] ? io_in_c[79] : io_in_a[79]);
	wire cout_vec_80 = (io_in_a[80] ^ io_in_b[80] ? io_in_c[80] : io_in_a[80]);
	wire cout_vec_81 = (io_in_a[81] ^ io_in_b[81] ? io_in_c[81] : io_in_a[81]);
	wire cout_vec_82 = (io_in_a[82] ^ io_in_b[82] ? io_in_c[82] : io_in_a[82]);
	wire cout_vec_83 = (io_in_a[83] ^ io_in_b[83] ? io_in_c[83] : io_in_a[83]);
	wire cout_vec_84 = (io_in_a[84] ^ io_in_b[84] ? io_in_c[84] : io_in_a[84]);
	wire cout_vec_85 = (io_in_a[85] ^ io_in_b[85] ? io_in_c[85] : io_in_a[85]);
	wire cout_vec_86 = (io_in_a[86] ^ io_in_b[86] ? io_in_c[86] : io_in_a[86]);
	wire cout_vec_87 = (io_in_a[87] ^ io_in_b[87] ? io_in_c[87] : io_in_a[87]);
	wire cout_vec_88 = (io_in_a[88] ^ io_in_b[88] ? io_in_c[88] : io_in_a[88]);
	wire cout_vec_89 = (io_in_a[89] ^ io_in_b[89] ? io_in_c[89] : io_in_a[89]);
	wire cout_vec_90 = (io_in_a[90] ^ io_in_b[90] ? io_in_c[90] : io_in_a[90]);
	wire cout_vec_91 = (io_in_a[91] ^ io_in_b[91] ? io_in_c[91] : io_in_a[91]);
	wire cout_vec_92 = (io_in_a[92] ^ io_in_b[92] ? io_in_c[92] : io_in_a[92]);
	wire cout_vec_93 = (io_in_a[93] ^ io_in_b[93] ? io_in_c[93] : io_in_a[93]);
	wire cout_vec_94 = (io_in_a[94] ^ io_in_b[94] ? io_in_c[94] : io_in_a[94]);
	wire cout_vec_95 = (io_in_a[95] ^ io_in_b[95] ? io_in_c[95] : io_in_a[95]);
	wire cout_vec_96 = (io_in_a[96] ^ io_in_b[96] ? io_in_c[96] : io_in_a[96]);
	wire cout_vec_97 = (io_in_a[97] ^ io_in_b[97] ? io_in_c[97] : io_in_a[97]);
	wire cout_vec_98 = (io_in_a[98] ^ io_in_b[98] ? io_in_c[98] : io_in_a[98]);
	wire cout_vec_99 = (io_in_a[99] ^ io_in_b[99] ? io_in_c[99] : io_in_a[99]);
	wire cout_vec_100 = (io_in_a[100] ^ io_in_b[100] ? io_in_c[100] : io_in_a[100]);
	wire cout_vec_101 = (io_in_a[101] ^ io_in_b[101] ? io_in_c[101] : io_in_a[101]);
	wire cout_vec_102 = (io_in_a[102] ^ io_in_b[102] ? io_in_c[102] : io_in_a[102]);
	wire cout_vec_103 = (io_in_a[103] ^ io_in_b[103] ? io_in_c[103] : io_in_a[103]);
	wire cout_vec_104 = (io_in_a[104] ^ io_in_b[104] ? io_in_c[104] : io_in_a[104]);
	assign io_out_sum = {(((io_in_a[106] ^ io_in_b[106]) ^ io_in_c[106]) ^ io_in_d[106]) ^ (io_in_a[105] ^ io_in_b[105] ? io_in_c[105] : io_in_a[105]), (((io_in_a[104] ^ io_in_b[104]) ^ io_in_c[104]) ^ io_in_d[104] ? cout_vec_103 : io_in_d[104]), (((io_in_a[104] ^ io_in_b[104]) ^ io_in_c[104]) ^ io_in_d[104]) ^ cout_vec_103, (((io_in_a[102] ^ io_in_b[102]) ^ io_in_c[102]) ^ io_in_d[102] ? cout_vec_101 : io_in_d[102]), (((io_in_a[102] ^ io_in_b[102]) ^ io_in_c[102]) ^ io_in_d[102]) ^ cout_vec_101, (((io_in_a[100] ^ io_in_b[100]) ^ io_in_c[100]) ^ io_in_d[100] ? cout_vec_99 : io_in_d[100]), (((io_in_a[100] ^ io_in_b[100]) ^ io_in_c[100]) ^ io_in_d[100]) ^ cout_vec_99, (((io_in_a[98] ^ io_in_b[98]) ^ io_in_c[98]) ^ io_in_d[98] ? cout_vec_97 : io_in_d[98]), (((io_in_a[98] ^ io_in_b[98]) ^ io_in_c[98]) ^ io_in_d[98]) ^ cout_vec_97, (((io_in_a[96] ^ io_in_b[96]) ^ io_in_c[96]) ^ io_in_d[96] ? cout_vec_95 : io_in_d[96]), (((io_in_a[96] ^ io_in_b[96]) ^ io_in_c[96]) ^ io_in_d[96]) ^ cout_vec_95, (((io_in_a[94] ^ io_in_b[94]) ^ io_in_c[94]) ^ io_in_d[94] ? cout_vec_93 : io_in_d[94]), (((io_in_a[94] ^ io_in_b[94]) ^ io_in_c[94]) ^ io_in_d[94]) ^ cout_vec_93, (((io_in_a[92] ^ io_in_b[92]) ^ io_in_c[92]) ^ io_in_d[92] ? cout_vec_91 : io_in_d[92]), (((io_in_a[92] ^ io_in_b[92]) ^ io_in_c[92]) ^ io_in_d[92]) ^ cout_vec_91, (((io_in_a[90] ^ io_in_b[90]) ^ io_in_c[90]) ^ io_in_d[90] ? cout_vec_89 : io_in_d[90]), (((io_in_a[90] ^ io_in_b[90]) ^ io_in_c[90]) ^ io_in_d[90]) ^ cout_vec_89, (((io_in_a[88] ^ io_in_b[88]) ^ io_in_c[88]) ^ io_in_d[88] ? cout_vec_87 : io_in_d[88]), (((io_in_a[88] ^ io_in_b[88]) ^ io_in_c[88]) ^ io_in_d[88]) ^ cout_vec_87, (((io_in_a[86] ^ io_in_b[86]) ^ io_in_c[86]) ^ io_in_d[86] ? cout_vec_85 : io_in_d[86]), (((io_in_a[86] ^ io_in_b[86]) ^ io_in_c[86]) ^ io_in_d[86]) ^ cout_vec_85, (((io_in_a[84] ^ io_in_b[84]) ^ io_in_c[84]) ^ io_in_d[84] ? cout_vec_83 : io_in_d[84]), (((io_in_a[84] ^ io_in_b[84]) ^ io_in_c[84]) ^ io_in_d[84]) ^ cout_vec_83, (((io_in_a[82] ^ io_in_b[82]) ^ io_in_c[82]) ^ io_in_d[82] ? cout_vec_81 : io_in_d[82]), (((io_in_a[82] ^ io_in_b[82]) ^ io_in_c[82]) ^ io_in_d[82]) ^ cout_vec_81, (((io_in_a[80] ^ io_in_b[80]) ^ io_in_c[80]) ^ io_in_d[80] ? cout_vec_79 : io_in_d[80]), (((io_in_a[80] ^ io_in_b[80]) ^ io_in_c[80]) ^ io_in_d[80]) ^ cout_vec_79, (((io_in_a[78] ^ io_in_b[78]) ^ io_in_c[78]) ^ io_in_d[78] ? cout_vec_77 : io_in_d[78]), (((io_in_a[78] ^ io_in_b[78]) ^ io_in_c[78]) ^ io_in_d[78]) ^ cout_vec_77, (((io_in_a[76] ^ io_in_b[76]) ^ io_in_c[76]) ^ io_in_d[76] ? cout_vec_75 : io_in_d[76]), (((io_in_a[76] ^ io_in_b[76]) ^ io_in_c[76]) ^ io_in_d[76]) ^ cout_vec_75, (((io_in_a[74] ^ io_in_b[74]) ^ io_in_c[74]) ^ io_in_d[74] ? cout_vec_73 : io_in_d[74]), (((io_in_a[74] ^ io_in_b[74]) ^ io_in_c[74]) ^ io_in_d[74]) ^ cout_vec_73, (((io_in_a[72] ^ io_in_b[72]) ^ io_in_c[72]) ^ io_in_d[72] ? cout_vec_71 : io_in_d[72]), (((io_in_a[72] ^ io_in_b[72]) ^ io_in_c[72]) ^ io_in_d[72]) ^ cout_vec_71, (((io_in_a[70] ^ io_in_b[70]) ^ io_in_c[70]) ^ io_in_d[70] ? cout_vec_69 : io_in_d[70]), (((io_in_a[70] ^ io_in_b[70]) ^ io_in_c[70]) ^ io_in_d[70]) ^ cout_vec_69, (((io_in_a[68] ^ io_in_b[68]) ^ io_in_c[68]) ^ io_in_d[68] ? cout_vec_67 : io_in_d[68]), (((io_in_a[68] ^ io_in_b[68]) ^ io_in_c[68]) ^ io_in_d[68]) ^ cout_vec_67, (((io_in_a[66] ^ io_in_b[66]) ^ io_in_c[66]) ^ io_in_d[66] ? cout_vec_65 : io_in_d[66]), (((io_in_a[66] ^ io_in_b[66]) ^ io_in_c[66]) ^ io_in_d[66]) ^ cout_vec_65, (((io_in_a[64] ^ io_in_b[64]) ^ io_in_c[64]) ^ io_in_d[64] ? cout_vec_63 : io_in_d[64]), (((io_in_a[64] ^ io_in_b[64]) ^ io_in_c[64]) ^ io_in_d[64]) ^ cout_vec_63, (((io_in_a[62] ^ io_in_b[62]) ^ io_in_c[62]) ^ io_in_d[62] ? cout_vec_61 : io_in_d[62]), (((io_in_a[62] ^ io_in_b[62]) ^ io_in_c[62]) ^ io_in_d[62]) ^ cout_vec_61, (((io_in_a[60] ^ io_in_b[60]) ^ io_in_c[60]) ^ io_in_d[60] ? cout_vec_59 : io_in_d[60]), (((io_in_a[60] ^ io_in_b[60]) ^ io_in_c[60]) ^ io_in_d[60]) ^ cout_vec_59, (((io_in_a[58] ^ io_in_b[58]) ^ io_in_c[58]) ^ io_in_d[58] ? cout_vec_57 : io_in_d[58]), (((io_in_a[58] ^ io_in_b[58]) ^ io_in_c[58]) ^ io_in_d[58]) ^ cout_vec_57, (((io_in_a[56] ^ io_in_b[56]) ^ io_in_c[56]) ^ io_in_d[56] ? cout_vec_55 : io_in_d[56]), (((io_in_a[56] ^ io_in_b[56]) ^ io_in_c[56]) ^ io_in_d[56]) ^ cout_vec_55, (((io_in_a[54] ^ io_in_b[54]) ^ io_in_c[54]) ^ io_in_d[54] ? cout_vec_53 : io_in_d[54]), (((io_in_a[54] ^ io_in_b[54]) ^ io_in_c[54]) ^ io_in_d[54]) ^ cout_vec_53, (((io_in_a[52] ^ io_in_b[52]) ^ io_in_c[52]) ^ io_in_d[52] ? cout_vec_51 : io_in_d[52]), (((io_in_a[52] ^ io_in_b[52]) ^ io_in_c[52]) ^ io_in_d[52]) ^ cout_vec_51, (((io_in_a[50] ^ io_in_b[50]) ^ io_in_c[50]) ^ io_in_d[50] ? cout_vec_49 : io_in_d[50]), (((io_in_a[50] ^ io_in_b[50]) ^ io_in_c[50]) ^ io_in_d[50]) ^ cout_vec_49, (((io_in_a[48] ^ io_in_b[48]) ^ io_in_c[48]) ^ io_in_d[48] ? cout_vec_47 : io_in_d[48]), (((io_in_a[48] ^ io_in_b[48]) ^ io_in_c[48]) ^ io_in_d[48]) ^ cout_vec_47, (((io_in_a[46] ^ io_in_b[46]) ^ io_in_c[46]) ^ io_in_d[46] ? cout_vec_45 : io_in_d[46]), (((io_in_a[46] ^ io_in_b[46]) ^ io_in_c[46]) ^ io_in_d[46]) ^ cout_vec_45, (((io_in_a[44] ^ io_in_b[44]) ^ io_in_c[44]) ^ io_in_d[44] ? cout_vec_43 : io_in_d[44]), (((io_in_a[44] ^ io_in_b[44]) ^ io_in_c[44]) ^ io_in_d[44]) ^ cout_vec_43, (((io_in_a[42] ^ io_in_b[42]) ^ io_in_c[42]) ^ io_in_d[42] ? cout_vec_41 : io_in_d[42]), (((io_in_a[42] ^ io_in_b[42]) ^ io_in_c[42]) ^ io_in_d[42]) ^ cout_vec_41, (((io_in_a[40] ^ io_in_b[40]) ^ io_in_c[40]) ^ io_in_d[40] ? cout_vec_39 : io_in_d[40]), (((io_in_a[40] ^ io_in_b[40]) ^ io_in_c[40]) ^ io_in_d[40]) ^ cout_vec_39, (((io_in_a[38] ^ io_in_b[38]) ^ io_in_c[38]) ^ io_in_d[38] ? cout_vec_37 : io_in_d[38]), (((io_in_a[38] ^ io_in_b[38]) ^ io_in_c[38]) ^ io_in_d[38]) ^ cout_vec_37, (((io_in_a[36] ^ io_in_b[36]) ^ io_in_c[36]) ^ io_in_d[36] ? cout_vec_35 : io_in_d[36]), (((io_in_a[36] ^ io_in_b[36]) ^ io_in_c[36]) ^ io_in_d[36]) ^ cout_vec_35, (((io_in_a[34] ^ io_in_b[34]) ^ io_in_c[34]) ^ io_in_d[34] ? cout_vec_33 : io_in_d[34]), (((io_in_a[34] ^ io_in_b[34]) ^ io_in_c[34]) ^ io_in_d[34]) ^ cout_vec_33, (((io_in_a[32] ^ io_in_b[32]) ^ io_in_c[32]) ^ io_in_d[32] ? cout_vec_31 : io_in_d[32]), (((io_in_a[32] ^ io_in_b[32]) ^ io_in_c[32]) ^ io_in_d[32]) ^ cout_vec_31, (((io_in_a[30] ^ io_in_b[30]) ^ io_in_c[30]) ^ io_in_d[30] ? cout_vec_29 : io_in_d[30]), (((io_in_a[30] ^ io_in_b[30]) ^ io_in_c[30]) ^ io_in_d[30]) ^ cout_vec_29, (((io_in_a[28] ^ io_in_b[28]) ^ io_in_c[28]) ^ io_in_d[28] ? cout_vec_27 : io_in_d[28]), (((io_in_a[28] ^ io_in_b[28]) ^ io_in_c[28]) ^ io_in_d[28]) ^ cout_vec_27, (((io_in_a[26] ^ io_in_b[26]) ^ io_in_c[26]) ^ io_in_d[26] ? cout_vec_25 : io_in_d[26]), (((io_in_a[26] ^ io_in_b[26]) ^ io_in_c[26]) ^ io_in_d[26]) ^ cout_vec_25, (((io_in_a[24] ^ io_in_b[24]) ^ io_in_c[24]) ^ io_in_d[24] ? cout_vec_23 : io_in_d[24]), (((io_in_a[24] ^ io_in_b[24]) ^ io_in_c[24]) ^ io_in_d[24]) ^ cout_vec_23, (((io_in_a[22] ^ io_in_b[22]) ^ io_in_c[22]) ^ io_in_d[22] ? cout_vec_21 : io_in_d[22]), (((io_in_a[22] ^ io_in_b[22]) ^ io_in_c[22]) ^ io_in_d[22]) ^ cout_vec_21, (((io_in_a[20] ^ io_in_b[20]) ^ io_in_c[20]) ^ io_in_d[20] ? cout_vec_19 : io_in_d[20]), (((io_in_a[20] ^ io_in_b[20]) ^ io_in_c[20]) ^ io_in_d[20]) ^ cout_vec_19, (((io_in_a[18] ^ io_in_b[18]) ^ io_in_c[18]) ^ io_in_d[18] ? cout_vec_17 : io_in_d[18]), (((io_in_a[18] ^ io_in_b[18]) ^ io_in_c[18]) ^ io_in_d[18]) ^ cout_vec_17, (((io_in_a[16] ^ io_in_b[16]) ^ io_in_c[16]) ^ io_in_d[16] ? cout_vec_15 : io_in_d[16]), (((io_in_a[16] ^ io_in_b[16]) ^ io_in_c[16]) ^ io_in_d[16]) ^ cout_vec_15, (((io_in_a[14] ^ io_in_b[14]) ^ io_in_c[14]) ^ io_in_d[14] ? cout_vec_13 : io_in_d[14]), (((io_in_a[14] ^ io_in_b[14]) ^ io_in_c[14]) ^ io_in_d[14]) ^ cout_vec_13, (((io_in_a[12] ^ io_in_b[12]) ^ io_in_c[12]) ^ io_in_d[12] ? cout_vec_11 : io_in_d[12]), (((io_in_a[12] ^ io_in_b[12]) ^ io_in_c[12]) ^ io_in_d[12]) ^ cout_vec_11, (((io_in_a[10] ^ io_in_b[10]) ^ io_in_c[10]) ^ io_in_d[10] ? cout_vec_9 : io_in_d[10]), (((io_in_a[10] ^ io_in_b[10]) ^ io_in_c[10]) ^ io_in_d[10]) ^ cout_vec_9, (((io_in_a[8] ^ io_in_b[8]) ^ io_in_c[8]) ^ io_in_d[8] ? cout_vec_7 : io_in_d[8]), (((io_in_a[8] ^ io_in_b[8]) ^ io_in_c[8]) ^ io_in_d[8]) ^ cout_vec_7, (((io_in_a[6] ^ io_in_b[6]) ^ io_in_c[6]) ^ io_in_d[6] ? cout_vec_5 : io_in_d[6]), (((io_in_a[6] ^ io_in_b[6]) ^ io_in_c[6]) ^ io_in_d[6]) ^ cout_vec_5, (((io_in_a[4] ^ io_in_b[4]) ^ io_in_c[4]) ^ io_in_d[4] ? cout_vec_3 : io_in_d[4]), (((io_in_a[4] ^ io_in_b[4]) ^ io_in_c[4]) ^ io_in_d[4]) ^ cout_vec_3, (((io_in_a[2] ^ io_in_b[2]) ^ io_in_c[2]) ^ io_in_d[2] ? cout_vec_1 : io_in_d[2]), (((io_in_a[2] ^ io_in_b[2]) ^ io_in_c[2]) ^ io_in_d[2]) ^ cout_vec_1, (((io_in_a[0] ^ io_in_b[0]) ^ io_in_c[0]) ^ ~io_in_d[0]) & io_in_d[0], ((io_in_a[0] ^ io_in_b[0]) ^ io_in_c[0]) ^ io_in_d[0]};
	assign io_out_car = {(((io_in_a[105] ^ io_in_b[105]) ^ io_in_c[105]) ^ io_in_d[105] ? cout_vec_104 : io_in_d[105]), (((io_in_a[105] ^ io_in_b[105]) ^ io_in_c[105]) ^ io_in_d[105]) ^ cout_vec_104, (((io_in_a[103] ^ io_in_b[103]) ^ io_in_c[103]) ^ io_in_d[103] ? cout_vec_102 : io_in_d[103]), (((io_in_a[103] ^ io_in_b[103]) ^ io_in_c[103]) ^ io_in_d[103]) ^ cout_vec_102, (((io_in_a[101] ^ io_in_b[101]) ^ io_in_c[101]) ^ io_in_d[101] ? cout_vec_100 : io_in_d[101]), (((io_in_a[101] ^ io_in_b[101]) ^ io_in_c[101]) ^ io_in_d[101]) ^ cout_vec_100, (((io_in_a[99] ^ io_in_b[99]) ^ io_in_c[99]) ^ io_in_d[99] ? cout_vec_98 : io_in_d[99]), (((io_in_a[99] ^ io_in_b[99]) ^ io_in_c[99]) ^ io_in_d[99]) ^ cout_vec_98, (((io_in_a[97] ^ io_in_b[97]) ^ io_in_c[97]) ^ io_in_d[97] ? cout_vec_96 : io_in_d[97]), (((io_in_a[97] ^ io_in_b[97]) ^ io_in_c[97]) ^ io_in_d[97]) ^ cout_vec_96, (((io_in_a[95] ^ io_in_b[95]) ^ io_in_c[95]) ^ io_in_d[95] ? cout_vec_94 : io_in_d[95]), (((io_in_a[95] ^ io_in_b[95]) ^ io_in_c[95]) ^ io_in_d[95]) ^ cout_vec_94, (((io_in_a[93] ^ io_in_b[93]) ^ io_in_c[93]) ^ io_in_d[93] ? cout_vec_92 : io_in_d[93]), (((io_in_a[93] ^ io_in_b[93]) ^ io_in_c[93]) ^ io_in_d[93]) ^ cout_vec_92, (((io_in_a[91] ^ io_in_b[91]) ^ io_in_c[91]) ^ io_in_d[91] ? cout_vec_90 : io_in_d[91]), (((io_in_a[91] ^ io_in_b[91]) ^ io_in_c[91]) ^ io_in_d[91]) ^ cout_vec_90, (((io_in_a[89] ^ io_in_b[89]) ^ io_in_c[89]) ^ io_in_d[89] ? cout_vec_88 : io_in_d[89]), (((io_in_a[89] ^ io_in_b[89]) ^ io_in_c[89]) ^ io_in_d[89]) ^ cout_vec_88, (((io_in_a[87] ^ io_in_b[87]) ^ io_in_c[87]) ^ io_in_d[87] ? cout_vec_86 : io_in_d[87]), (((io_in_a[87] ^ io_in_b[87]) ^ io_in_c[87]) ^ io_in_d[87]) ^ cout_vec_86, (((io_in_a[85] ^ io_in_b[85]) ^ io_in_c[85]) ^ io_in_d[85] ? cout_vec_84 : io_in_d[85]), (((io_in_a[85] ^ io_in_b[85]) ^ io_in_c[85]) ^ io_in_d[85]) ^ cout_vec_84, (((io_in_a[83] ^ io_in_b[83]) ^ io_in_c[83]) ^ io_in_d[83] ? cout_vec_82 : io_in_d[83]), (((io_in_a[83] ^ io_in_b[83]) ^ io_in_c[83]) ^ io_in_d[83]) ^ cout_vec_82, (((io_in_a[81] ^ io_in_b[81]) ^ io_in_c[81]) ^ io_in_d[81] ? cout_vec_80 : io_in_d[81]), (((io_in_a[81] ^ io_in_b[81]) ^ io_in_c[81]) ^ io_in_d[81]) ^ cout_vec_80, (((io_in_a[79] ^ io_in_b[79]) ^ io_in_c[79]) ^ io_in_d[79] ? cout_vec_78 : io_in_d[79]), (((io_in_a[79] ^ io_in_b[79]) ^ io_in_c[79]) ^ io_in_d[79]) ^ cout_vec_78, (((io_in_a[77] ^ io_in_b[77]) ^ io_in_c[77]) ^ io_in_d[77] ? cout_vec_76 : io_in_d[77]), (((io_in_a[77] ^ io_in_b[77]) ^ io_in_c[77]) ^ io_in_d[77]) ^ cout_vec_76, (((io_in_a[75] ^ io_in_b[75]) ^ io_in_c[75]) ^ io_in_d[75] ? cout_vec_74 : io_in_d[75]), (((io_in_a[75] ^ io_in_b[75]) ^ io_in_c[75]) ^ io_in_d[75]) ^ cout_vec_74, (((io_in_a[73] ^ io_in_b[73]) ^ io_in_c[73]) ^ io_in_d[73] ? cout_vec_72 : io_in_d[73]), (((io_in_a[73] ^ io_in_b[73]) ^ io_in_c[73]) ^ io_in_d[73]) ^ cout_vec_72, (((io_in_a[71] ^ io_in_b[71]) ^ io_in_c[71]) ^ io_in_d[71] ? cout_vec_70 : io_in_d[71]), (((io_in_a[71] ^ io_in_b[71]) ^ io_in_c[71]) ^ io_in_d[71]) ^ cout_vec_70, (((io_in_a[69] ^ io_in_b[69]) ^ io_in_c[69]) ^ io_in_d[69] ? cout_vec_68 : io_in_d[69]), (((io_in_a[69] ^ io_in_b[69]) ^ io_in_c[69]) ^ io_in_d[69]) ^ cout_vec_68, (((io_in_a[67] ^ io_in_b[67]) ^ io_in_c[67]) ^ io_in_d[67] ? cout_vec_66 : io_in_d[67]), (((io_in_a[67] ^ io_in_b[67]) ^ io_in_c[67]) ^ io_in_d[67]) ^ cout_vec_66, (((io_in_a[65] ^ io_in_b[65]) ^ io_in_c[65]) ^ io_in_d[65] ? cout_vec_64 : io_in_d[65]), (((io_in_a[65] ^ io_in_b[65]) ^ io_in_c[65]) ^ io_in_d[65]) ^ cout_vec_64, (((io_in_a[63] ^ io_in_b[63]) ^ io_in_c[63]) ^ io_in_d[63] ? cout_vec_62 : io_in_d[63]), (((io_in_a[63] ^ io_in_b[63]) ^ io_in_c[63]) ^ io_in_d[63]) ^ cout_vec_62, (((io_in_a[61] ^ io_in_b[61]) ^ io_in_c[61]) ^ io_in_d[61] ? cout_vec_60 : io_in_d[61]), (((io_in_a[61] ^ io_in_b[61]) ^ io_in_c[61]) ^ io_in_d[61]) ^ cout_vec_60, (((io_in_a[59] ^ io_in_b[59]) ^ io_in_c[59]) ^ io_in_d[59] ? cout_vec_58 : io_in_d[59]), (((io_in_a[59] ^ io_in_b[59]) ^ io_in_c[59]) ^ io_in_d[59]) ^ cout_vec_58, (((io_in_a[57] ^ io_in_b[57]) ^ io_in_c[57]) ^ io_in_d[57] ? cout_vec_56 : io_in_d[57]), (((io_in_a[57] ^ io_in_b[57]) ^ io_in_c[57]) ^ io_in_d[57]) ^ cout_vec_56, (((io_in_a[55] ^ io_in_b[55]) ^ io_in_c[55]) ^ io_in_d[55] ? cout_vec_54 : io_in_d[55]), (((io_in_a[55] ^ io_in_b[55]) ^ io_in_c[55]) ^ io_in_d[55]) ^ cout_vec_54, (((io_in_a[53] ^ io_in_b[53]) ^ io_in_c[53]) ^ io_in_d[53] ? cout_vec_52 : io_in_d[53]), (((io_in_a[53] ^ io_in_b[53]) ^ io_in_c[53]) ^ io_in_d[53]) ^ cout_vec_52, (((io_in_a[51] ^ io_in_b[51]) ^ io_in_c[51]) ^ io_in_d[51] ? cout_vec_50 : io_in_d[51]), (((io_in_a[51] ^ io_in_b[51]) ^ io_in_c[51]) ^ io_in_d[51]) ^ cout_vec_50, (((io_in_a[49] ^ io_in_b[49]) ^ io_in_c[49]) ^ io_in_d[49] ? cout_vec_48 : io_in_d[49]), (((io_in_a[49] ^ io_in_b[49]) ^ io_in_c[49]) ^ io_in_d[49]) ^ cout_vec_48, (((io_in_a[47] ^ io_in_b[47]) ^ io_in_c[47]) ^ io_in_d[47] ? cout_vec_46 : io_in_d[47]), (((io_in_a[47] ^ io_in_b[47]) ^ io_in_c[47]) ^ io_in_d[47]) ^ cout_vec_46, (((io_in_a[45] ^ io_in_b[45]) ^ io_in_c[45]) ^ io_in_d[45] ? cout_vec_44 : io_in_d[45]), (((io_in_a[45] ^ io_in_b[45]) ^ io_in_c[45]) ^ io_in_d[45]) ^ cout_vec_44, (((io_in_a[43] ^ io_in_b[43]) ^ io_in_c[43]) ^ io_in_d[43] ? cout_vec_42 : io_in_d[43]), (((io_in_a[43] ^ io_in_b[43]) ^ io_in_c[43]) ^ io_in_d[43]) ^ cout_vec_42, (((io_in_a[41] ^ io_in_b[41]) ^ io_in_c[41]) ^ io_in_d[41] ? cout_vec_40 : io_in_d[41]), (((io_in_a[41] ^ io_in_b[41]) ^ io_in_c[41]) ^ io_in_d[41]) ^ cout_vec_40, (((io_in_a[39] ^ io_in_b[39]) ^ io_in_c[39]) ^ io_in_d[39] ? cout_vec_38 : io_in_d[39]), (((io_in_a[39] ^ io_in_b[39]) ^ io_in_c[39]) ^ io_in_d[39]) ^ cout_vec_38, (((io_in_a[37] ^ io_in_b[37]) ^ io_in_c[37]) ^ io_in_d[37] ? cout_vec_36 : io_in_d[37]), (((io_in_a[37] ^ io_in_b[37]) ^ io_in_c[37]) ^ io_in_d[37]) ^ cout_vec_36, (((io_in_a[35] ^ io_in_b[35]) ^ io_in_c[35]) ^ io_in_d[35] ? cout_vec_34 : io_in_d[35]), (((io_in_a[35] ^ io_in_b[35]) ^ io_in_c[35]) ^ io_in_d[35]) ^ cout_vec_34, (((io_in_a[33] ^ io_in_b[33]) ^ io_in_c[33]) ^ io_in_d[33] ? cout_vec_32 : io_in_d[33]), (((io_in_a[33] ^ io_in_b[33]) ^ io_in_c[33]) ^ io_in_d[33]) ^ cout_vec_32, (((io_in_a[31] ^ io_in_b[31]) ^ io_in_c[31]) ^ io_in_d[31] ? cout_vec_30 : io_in_d[31]), (((io_in_a[31] ^ io_in_b[31]) ^ io_in_c[31]) ^ io_in_d[31]) ^ cout_vec_30, (((io_in_a[29] ^ io_in_b[29]) ^ io_in_c[29]) ^ io_in_d[29] ? cout_vec_28 : io_in_d[29]), (((io_in_a[29] ^ io_in_b[29]) ^ io_in_c[29]) ^ io_in_d[29]) ^ cout_vec_28, (((io_in_a[27] ^ io_in_b[27]) ^ io_in_c[27]) ^ io_in_d[27] ? cout_vec_26 : io_in_d[27]), (((io_in_a[27] ^ io_in_b[27]) ^ io_in_c[27]) ^ io_in_d[27]) ^ cout_vec_26, (((io_in_a[25] ^ io_in_b[25]) ^ io_in_c[25]) ^ io_in_d[25] ? cout_vec_24 : io_in_d[25]), (((io_in_a[25] ^ io_in_b[25]) ^ io_in_c[25]) ^ io_in_d[25]) ^ cout_vec_24, (((io_in_a[23] ^ io_in_b[23]) ^ io_in_c[23]) ^ io_in_d[23] ? cout_vec_22 : io_in_d[23]), (((io_in_a[23] ^ io_in_b[23]) ^ io_in_c[23]) ^ io_in_d[23]) ^ cout_vec_22, (((io_in_a[21] ^ io_in_b[21]) ^ io_in_c[21]) ^ io_in_d[21] ? cout_vec_20 : io_in_d[21]), (((io_in_a[21] ^ io_in_b[21]) ^ io_in_c[21]) ^ io_in_d[21]) ^ cout_vec_20, (((io_in_a[19] ^ io_in_b[19]) ^ io_in_c[19]) ^ io_in_d[19] ? cout_vec_18 : io_in_d[19]), (((io_in_a[19] ^ io_in_b[19]) ^ io_in_c[19]) ^ io_in_d[19]) ^ cout_vec_18, (((io_in_a[17] ^ io_in_b[17]) ^ io_in_c[17]) ^ io_in_d[17] ? cout_vec_16 : io_in_d[17]), (((io_in_a[17] ^ io_in_b[17]) ^ io_in_c[17]) ^ io_in_d[17]) ^ cout_vec_16, (((io_in_a[15] ^ io_in_b[15]) ^ io_in_c[15]) ^ io_in_d[15] ? cout_vec_14 : io_in_d[15]), (((io_in_a[15] ^ io_in_b[15]) ^ io_in_c[15]) ^ io_in_d[15]) ^ cout_vec_14, (((io_in_a[13] ^ io_in_b[13]) ^ io_in_c[13]) ^ io_in_d[13] ? cout_vec_12 : io_in_d[13]), (((io_in_a[13] ^ io_in_b[13]) ^ io_in_c[13]) ^ io_in_d[13]) ^ cout_vec_12, (((io_in_a[11] ^ io_in_b[11]) ^ io_in_c[11]) ^ io_in_d[11] ? cout_vec_10 : io_in_d[11]), (((io_in_a[11] ^ io_in_b[11]) ^ io_in_c[11]) ^ io_in_d[11]) ^ cout_vec_10, (((io_in_a[9] ^ io_in_b[9]) ^ io_in_c[9]) ^ io_in_d[9] ? cout_vec_8 : io_in_d[9]), (((io_in_a[9] ^ io_in_b[9]) ^ io_in_c[9]) ^ io_in_d[9]) ^ cout_vec_8, (((io_in_a[7] ^ io_in_b[7]) ^ io_in_c[7]) ^ io_in_d[7] ? cout_vec_6 : io_in_d[7]), (((io_in_a[7] ^ io_in_b[7]) ^ io_in_c[7]) ^ io_in_d[7]) ^ cout_vec_6, (((io_in_a[5] ^ io_in_b[5]) ^ io_in_c[5]) ^ io_in_d[5] ? cout_vec_4 : io_in_d[5]), (((io_in_a[5] ^ io_in_b[5]) ^ io_in_c[5]) ^ io_in_d[5]) ^ cout_vec_4, (((io_in_a[3] ^ io_in_b[3]) ^ io_in_c[3]) ^ io_in_d[3] ? cout_vec_2 : io_in_d[3]), (((io_in_a[3] ^ io_in_b[3]) ^ io_in_c[3]) ^ io_in_d[3]) ^ cout_vec_2, (((io_in_a[1] ^ io_in_b[1]) ^ io_in_c[1]) ^ io_in_d[1] ? cout_vec_0 : io_in_d[1]), (((io_in_a[1] ^ io_in_b[1]) ^ io_in_c[1]) ^ io_in_d[1]) ^ cout_vec_0, 1'h0};
endmodule
module CSA_Nto2With3to2MainPipeline (
	clock,
	io_fire,
	io_in_0,
	io_in_1,
	io_in_2,
	io_in_3,
	io_in_4,
	io_in_5,
	io_in_6,
	io_in_7,
	io_in_8,
	io_in_9,
	io_in_10,
	io_in_11,
	io_in_12,
	io_in_13,
	io_in_14,
	io_in_15,
	io_in_16,
	io_in_17,
	io_in_18,
	io_in_19,
	io_in_20,
	io_in_21,
	io_in_22,
	io_in_23,
	io_in_24,
	io_in_25,
	io_in_26,
	io_out_sum,
	io_out_car
);
	input clock;
	input io_fire;
	input [106:0] io_in_0;
	input [106:0] io_in_1;
	input [106:0] io_in_2;
	input [106:0] io_in_3;
	input [106:0] io_in_4;
	input [106:0] io_in_5;
	input [106:0] io_in_6;
	input [106:0] io_in_7;
	input [106:0] io_in_8;
	input [106:0] io_in_9;
	input [106:0] io_in_10;
	input [106:0] io_in_11;
	input [106:0] io_in_12;
	input [106:0] io_in_13;
	input [106:0] io_in_14;
	input [106:0] io_in_15;
	input [106:0] io_in_16;
	input [106:0] io_in_17;
	input [106:0] io_in_18;
	input [106:0] io_in_19;
	input [106:0] io_in_20;
	input [106:0] io_in_21;
	input [106:0] io_in_22;
	input [106:0] io_in_23;
	input [106:0] io_in_24;
	input [106:0] io_in_25;
	input [106:0] io_in_26;
	output wire [106:0] io_out_sum;
	output wire [106:0] io_out_car;
	wire [106:0] _U_CSA4to2_1_io_out_sum;
	wire [106:0] _U_CSA4to2_1_io_out_car;
	wire [106:0] _U_CSA4to2_io_out_sum;
	wire [106:0] _U_CSA4to2_io_out_car;
	wire [106:0] _U_CSA3to2_18_io_out_sum;
	wire [106:0] _U_CSA3to2_18_io_out_car;
	wire [106:0] _U_CSA3to2_17_io_out_sum;
	wire [106:0] _U_CSA3to2_17_io_out_car;
	wire [106:0] _U_CSA3to2_16_io_out_sum;
	wire [106:0] _U_CSA3to2_16_io_out_car;
	wire [106:0] _U_CSA3to2_15_io_out_sum;
	wire [106:0] _U_CSA3to2_15_io_out_car;
	wire [106:0] _U_CSA3to2_14_io_out_sum;
	wire [106:0] _U_CSA3to2_14_io_out_car;
	wire [106:0] _U_CSA3to2_13_io_out_sum;
	wire [106:0] _U_CSA3to2_13_io_out_car;
	wire [106:0] _U_CSA3to2_12_io_out_sum;
	wire [106:0] _U_CSA3to2_12_io_out_car;
	wire [106:0] _U_CSA3to2_11_io_out_sum;
	wire [106:0] _U_CSA3to2_11_io_out_car;
	wire [106:0] _U_CSA3to2_10_io_out_sum;
	wire [106:0] _U_CSA3to2_10_io_out_car;
	wire [106:0] _U_CSA3to2_9_io_out_sum;
	wire [106:0] _U_CSA3to2_9_io_out_car;
	wire [106:0] _U_CSA3to2_8_io_out_sum;
	wire [106:0] _U_CSA3to2_8_io_out_car;
	wire [106:0] _U_CSA3to2_7_io_out_sum;
	wire [106:0] _U_CSA3to2_7_io_out_car;
	wire [106:0] _U_CSA3to2_6_io_out_sum;
	wire [106:0] _U_CSA3to2_6_io_out_car;
	wire [106:0] _U_CSA3to2_5_io_out_sum;
	wire [106:0] _U_CSA3to2_5_io_out_car;
	wire [106:0] _U_CSA3to2_4_io_out_sum;
	wire [106:0] _U_CSA3to2_4_io_out_car;
	wire [106:0] _U_CSA3to2_3_io_out_sum;
	wire [106:0] _U_CSA3to2_3_io_out_car;
	wire [106:0] _U_CSA3to2_2_io_out_sum;
	wire [106:0] _U_CSA3to2_2_io_out_car;
	wire [106:0] _U_CSA3to2_1_io_out_sum;
	wire [106:0] _U_CSA3to2_1_io_out_car;
	wire [106:0] _U_CSA3to2_io_out_sum;
	wire [106:0] _U_CSA3to2_io_out_car;
	reg [106:0] U_CSA4to2_io_in_a_r;
	reg [106:0] U_CSA4to2_io_in_b_r;
	reg [106:0] U_CSA4to2_io_in_c_r;
	reg [106:0] U_CSA4to2_io_in_d_r;
	always @(posedge clock)
		if (io_fire) begin
			U_CSA4to2_io_in_a_r <= _U_CSA4to2_io_out_sum;
			U_CSA4to2_io_in_b_r <= _U_CSA4to2_io_out_car;
			U_CSA4to2_io_in_c_r <= _U_CSA4to2_1_io_out_sum;
			U_CSA4to2_io_in_d_r <= _U_CSA4to2_1_io_out_car;
		end
	CSA3to2 U_CSA3to2(
		.io_in_a(io_in_0),
		.io_in_b(io_in_1),
		.io_in_c(io_in_2),
		.io_out_sum(_U_CSA3to2_io_out_sum),
		.io_out_car(_U_CSA3to2_io_out_car)
	);
	CSA3to2 U_CSA3to2_1(
		.io_in_a(io_in_3),
		.io_in_b(io_in_4),
		.io_in_c(io_in_5),
		.io_out_sum(_U_CSA3to2_1_io_out_sum),
		.io_out_car(_U_CSA3to2_1_io_out_car)
	);
	CSA3to2 U_CSA3to2_2(
		.io_in_a(io_in_6),
		.io_in_b(io_in_7),
		.io_in_c(io_in_8),
		.io_out_sum(_U_CSA3to2_2_io_out_sum),
		.io_out_car(_U_CSA3to2_2_io_out_car)
	);
	CSA3to2 U_CSA3to2_3(
		.io_in_a(io_in_9),
		.io_in_b(io_in_10),
		.io_in_c(io_in_11),
		.io_out_sum(_U_CSA3to2_3_io_out_sum),
		.io_out_car(_U_CSA3to2_3_io_out_car)
	);
	CSA3to2 U_CSA3to2_4(
		.io_in_a(io_in_12),
		.io_in_b(io_in_13),
		.io_in_c(io_in_14),
		.io_out_sum(_U_CSA3to2_4_io_out_sum),
		.io_out_car(_U_CSA3to2_4_io_out_car)
	);
	CSA3to2 U_CSA3to2_5(
		.io_in_a(io_in_15),
		.io_in_b(io_in_16),
		.io_in_c(io_in_17),
		.io_out_sum(_U_CSA3to2_5_io_out_sum),
		.io_out_car(_U_CSA3to2_5_io_out_car)
	);
	CSA3to2 U_CSA3to2_6(
		.io_in_a(io_in_18),
		.io_in_b(io_in_19),
		.io_in_c(io_in_20),
		.io_out_sum(_U_CSA3to2_6_io_out_sum),
		.io_out_car(_U_CSA3to2_6_io_out_car)
	);
	CSA3to2 U_CSA3to2_7(
		.io_in_a(io_in_21),
		.io_in_b(io_in_22),
		.io_in_c(io_in_23),
		.io_out_sum(_U_CSA3to2_7_io_out_sum),
		.io_out_car(_U_CSA3to2_7_io_out_car)
	);
	CSA3to2 U_CSA3to2_8(
		.io_in_a(io_in_24),
		.io_in_b(io_in_25),
		.io_in_c(io_in_26),
		.io_out_sum(_U_CSA3to2_8_io_out_sum),
		.io_out_car(_U_CSA3to2_8_io_out_car)
	);
	CSA3to2 U_CSA3to2_9(
		.io_in_a(_U_CSA3to2_io_out_sum),
		.io_in_b(_U_CSA3to2_io_out_car),
		.io_in_c(_U_CSA3to2_1_io_out_sum),
		.io_out_sum(_U_CSA3to2_9_io_out_sum),
		.io_out_car(_U_CSA3to2_9_io_out_car)
	);
	CSA3to2 U_CSA3to2_10(
		.io_in_a(_U_CSA3to2_1_io_out_car),
		.io_in_b(_U_CSA3to2_2_io_out_sum),
		.io_in_c(_U_CSA3to2_2_io_out_car),
		.io_out_sum(_U_CSA3to2_10_io_out_sum),
		.io_out_car(_U_CSA3to2_10_io_out_car)
	);
	CSA3to2 U_CSA3to2_11(
		.io_in_a(_U_CSA3to2_3_io_out_sum),
		.io_in_b(_U_CSA3to2_3_io_out_car),
		.io_in_c(_U_CSA3to2_4_io_out_sum),
		.io_out_sum(_U_CSA3to2_11_io_out_sum),
		.io_out_car(_U_CSA3to2_11_io_out_car)
	);
	CSA3to2 U_CSA3to2_12(
		.io_in_a(_U_CSA3to2_4_io_out_car),
		.io_in_b(_U_CSA3to2_5_io_out_sum),
		.io_in_c(_U_CSA3to2_5_io_out_car),
		.io_out_sum(_U_CSA3to2_12_io_out_sum),
		.io_out_car(_U_CSA3to2_12_io_out_car)
	);
	CSA3to2 U_CSA3to2_13(
		.io_in_a(_U_CSA3to2_6_io_out_sum),
		.io_in_b(_U_CSA3to2_6_io_out_car),
		.io_in_c(_U_CSA3to2_7_io_out_sum),
		.io_out_sum(_U_CSA3to2_13_io_out_sum),
		.io_out_car(_U_CSA3to2_13_io_out_car)
	);
	CSA3to2 U_CSA3to2_14(
		.io_in_a(_U_CSA3to2_7_io_out_car),
		.io_in_b(_U_CSA3to2_8_io_out_sum),
		.io_in_c(_U_CSA3to2_8_io_out_car),
		.io_out_sum(_U_CSA3to2_14_io_out_sum),
		.io_out_car(_U_CSA3to2_14_io_out_car)
	);
	CSA3to2 U_CSA3to2_15(
		.io_in_a(_U_CSA3to2_9_io_out_sum),
		.io_in_b(_U_CSA3to2_9_io_out_car),
		.io_in_c(_U_CSA3to2_10_io_out_sum),
		.io_out_sum(_U_CSA3to2_15_io_out_sum),
		.io_out_car(_U_CSA3to2_15_io_out_car)
	);
	CSA3to2 U_CSA3to2_16(
		.io_in_a(_U_CSA3to2_10_io_out_car),
		.io_in_b(_U_CSA3to2_11_io_out_sum),
		.io_in_c(_U_CSA3to2_11_io_out_car),
		.io_out_sum(_U_CSA3to2_16_io_out_sum),
		.io_out_car(_U_CSA3to2_16_io_out_car)
	);
	CSA3to2 U_CSA3to2_17(
		.io_in_a(_U_CSA3to2_12_io_out_sum),
		.io_in_b(_U_CSA3to2_12_io_out_car),
		.io_in_c(_U_CSA3to2_13_io_out_sum),
		.io_out_sum(_U_CSA3to2_17_io_out_sum),
		.io_out_car(_U_CSA3to2_17_io_out_car)
	);
	CSA3to2 U_CSA3to2_18(
		.io_in_a(_U_CSA3to2_13_io_out_car),
		.io_in_b(_U_CSA3to2_14_io_out_sum),
		.io_in_c(_U_CSA3to2_14_io_out_car),
		.io_out_sum(_U_CSA3to2_18_io_out_sum),
		.io_out_car(_U_CSA3to2_18_io_out_car)
	);
	CSA4to2 U_CSA4to2(
		.io_in_a(_U_CSA3to2_15_io_out_sum),
		.io_in_b(_U_CSA3to2_15_io_out_car),
		.io_in_c(_U_CSA3to2_16_io_out_sum),
		.io_in_d(_U_CSA3to2_16_io_out_car),
		.io_out_sum(_U_CSA4to2_io_out_sum),
		.io_out_car(_U_CSA4to2_io_out_car)
	);
	CSA4to2 U_CSA4to2_1(
		.io_in_a(_U_CSA3to2_17_io_out_sum),
		.io_in_b(_U_CSA3to2_17_io_out_car),
		.io_in_c(_U_CSA3to2_18_io_out_sum),
		.io_in_d(_U_CSA3to2_18_io_out_car),
		.io_out_sum(_U_CSA4to2_1_io_out_sum),
		.io_out_car(_U_CSA4to2_1_io_out_car)
	);
	CSA4to2 U_CSA4to2_2(
		.io_in_a(U_CSA4to2_io_in_a_r),
		.io_in_b(U_CSA4to2_io_in_b_r),
		.io_in_c(U_CSA4to2_io_in_c_r),
		.io_in_d(U_CSA4to2_io_in_d_r),
		.io_out_sum(io_out_sum),
		.io_out_car(io_out_car)
	);
endmodule
module FloatFMA (
	clock,
	reset,
	io_fire,
	io_fp_a,
	io_fp_b,
	io_fp_c,
	io_round_mode,
	io_fp_format,
	io_op_code,
	io_fp_result,
	io_fflags,
	io_fp_aIsFpCanonicalNAN,
	io_fp_bIsFpCanonicalNAN,
	io_fp_cIsFpCanonicalNAN
);
	input clock;
	input reset;
	input io_fire;
	input [63:0] io_fp_a;
	input [63:0] io_fp_b;
	input [63:0] io_fp_c;
	input [2:0] io_round_mode;
	input [1:0] io_fp_format;
	input [3:0] io_op_code;
	output wire [63:0] io_fp_result;
	output wire [4:0] io_fflags;
	input io_fp_aIsFpCanonicalNAN;
	input io_fp_bIsFpCanonicalNAN;
	input io_fp_cIsFpCanonicalNAN;
	wire UF_f16;
	wire UF_f32;
	wire UF_f64;
	wire NX_f16;
	wire NX_f32;
	wire NX_f64;
	wire [106:0] _U_CSA3to2_io_out_sum;
	wire [106:0] _U_CSA3to2_io_out_car;
	wire [106:0] _U_CSAnto2_io_out_sum;
	wire [106:0] _U_CSAnto2_io_out_car;
	wire [106:0] _U_BoothEncoder_io_out_pp_0;
	wire [106:0] _U_BoothEncoder_io_out_pp_1;
	wire [106:0] _U_BoothEncoder_io_out_pp_2;
	wire [106:0] _U_BoothEncoder_io_out_pp_3;
	wire [106:0] _U_BoothEncoder_io_out_pp_4;
	wire [106:0] _U_BoothEncoder_io_out_pp_5;
	wire [106:0] _U_BoothEncoder_io_out_pp_6;
	wire [106:0] _U_BoothEncoder_io_out_pp_7;
	wire [106:0] _U_BoothEncoder_io_out_pp_8;
	wire [106:0] _U_BoothEncoder_io_out_pp_9;
	wire [106:0] _U_BoothEncoder_io_out_pp_10;
	wire [106:0] _U_BoothEncoder_io_out_pp_11;
	wire [106:0] _U_BoothEncoder_io_out_pp_12;
	wire [106:0] _U_BoothEncoder_io_out_pp_13;
	wire [106:0] _U_BoothEncoder_io_out_pp_14;
	wire [106:0] _U_BoothEncoder_io_out_pp_15;
	wire [106:0] _U_BoothEncoder_io_out_pp_16;
	wire [106:0] _U_BoothEncoder_io_out_pp_17;
	wire [106:0] _U_BoothEncoder_io_out_pp_18;
	wire [106:0] _U_BoothEncoder_io_out_pp_19;
	wire [106:0] _U_BoothEncoder_io_out_pp_20;
	wire [106:0] _U_BoothEncoder_io_out_pp_21;
	wire [106:0] _U_BoothEncoder_io_out_pp_22;
	wire [106:0] _U_BoothEncoder_io_out_pp_23;
	wire [106:0] _U_BoothEncoder_io_out_pp_24;
	wire [106:0] _U_BoothEncoder_io_out_pp_25;
	wire [106:0] _U_BoothEncoder_io_out_pp_26;
	reg fire_reg0_last_r;
	reg fire_reg1_last_r;
	reg is_fp64_reg0;
	reg is_fp64_reg1;
	reg is_fp64_reg2;
	wire is_fp32 = io_fp_format == 2'h2;
	reg is_fp32_reg0;
	reg is_fp32_reg1;
	reg is_fp32_reg2;
	reg is_sub_f64_reg0;
	reg rshift_guard_reg;
	reg rshift_round_reg;
	reg rshift_sticky_reg;
	reg [161:0] fp_c_rshiftValue_inv_reg;
	reg CSA3to2_in_b_r;
	reg CSA3to2_in_b_r_1;
	reg adder_f32_r;
	reg adder_f16_r;
	reg adder_is_negative_reg1;
	reg adder_is_negative_reg2;
	reg [11:0] E_greater_reg2_r;
	reg [11:0] E_greater_reg2_r_1;
	reg [11:0] E_greater_reg2;
	reg [11:0] lshift_value_max_reg0;
	reg [163:0] tzd_adder_reg1;
	reg [162:0] lzd_adder_inv_mask_reg1;
	reg lshift_mask_valid_reg;
	reg [163:0] adder_f64_reg1;
	reg [76:0] adder_f32_reg1;
	reg [37:0] adder_f16_reg1;
	reg [51:0] fraction_result_no_round_reg;
	reg sign_result_temp_f64_reg2_r;
	reg sign_result_temp_f64_reg2_r_1;
	reg sign_result_temp_f64_reg2_r_2;
	reg sign_result_temp_f64_reg2;
	reg sign_result_temp_f32_reg2_r;
	reg sign_result_temp_f32_reg2_r_1;
	reg sign_result_temp_f32_reg2_r_2;
	reg sign_result_temp_f32_reg2;
	reg sign_result_temp_f16_reg2_r;
	reg sign_result_temp_f16_reg2_r_1;
	reg sign_result_temp_f16_reg2_r_2;
	reg sign_result_temp_f16_reg2;
	reg RNE_reg2_r;
	reg RNE_reg2_r_1;
	reg RNE_reg2;
	reg RTZ_reg2_r;
	reg RTZ_reg2_r_1;
	reg RTZ_reg2;
	reg RDN_reg2_r;
	reg RDN_reg2_r_1;
	reg RDN_reg2;
	reg RUP_reg2_r;
	reg RUP_reg2_r_1;
	reg RUP_reg2;
	reg RMM_reg2_r;
	reg RMM_reg2_r_1;
	reg RMM_reg2;
	reg sticky_f64_reg2_r;
	reg sticky_f64_reg2;
	reg sticky_f32_reg2_r;
	reg sticky_f32_reg2;
	reg sticky_f16_reg2_r;
	reg sticky_f16_reg2;
	reg sticky_uf_f64_reg2_r;
	reg sticky_uf_f64_reg2;
	reg sticky_uf_f32_reg2_r;
	reg sticky_uf_f32_reg2;
	reg sticky_uf_f16_reg2_r;
	reg sticky_uf_f16_reg2;
	reg round_lshift_f64_reg2;
	reg round_lshift_f32_reg2;
	reg round_lshift_f16_reg2;
	reg guard_lshift_f64_reg2;
	reg guard_lshift_f32_reg2;
	reg guard_lshift_f16_reg2;
	wire round_f64 = (adder_is_negative_reg2 & ~sticky_f64_reg2) ^ round_lshift_f64_reg2;
	wire round_f32 = (adder_is_negative_reg2 & ~sticky_f32_reg2) ^ round_lshift_f32_reg2;
	wire round_f16 = (adder_is_negative_reg2 & ~sticky_f16_reg2) ^ round_lshift_f16_reg2;
	wire guard_f64 = ((adder_is_negative_reg2 & ~sticky_f64_reg2) & round_lshift_f64_reg2) ^ guard_lshift_f64_reg2;
	wire guard_f32 = ((adder_is_negative_reg2 & ~sticky_f32_reg2) & round_lshift_f32_reg2) ^ guard_lshift_f32_reg2;
	wire guard_f16 = ((adder_is_negative_reg2 & ~sticky_f16_reg2) & round_lshift_f16_reg2) ^ guard_lshift_f16_reg2;
	reg round_lshift_uf_f64_reg2;
	reg round_lshift_uf_f32_reg2;
	reg round_lshift_uf_f16_reg2;
	wire round_uf_f64 = (adder_is_negative_reg2 & ~sticky_uf_f64_reg2) ^ round_lshift_uf_f64_reg2;
	wire round_uf_f32 = (adder_is_negative_reg2 & ~sticky_uf_f32_reg2) ^ round_lshift_uf_f32_reg2;
	wire round_uf_f16 = (adder_is_negative_reg2 & ~sticky_uf_f16_reg2) ^ round_lshift_uf_f16_reg2;
	wire _round_add1_uf_f64_T_4 = RDN_reg2 & sign_result_temp_f64_reg2;
	wire _NX_f64_T = guard_f64 | round_f64;
	wire round_add1_f64 = (((((RNE_reg2 & guard_f64) & ((fraction_result_no_round_reg[0] | round_f64) | sticky_f64_reg2)) | (_round_add1_uf_f64_T_4 & (_NX_f64_T | sticky_f64_reg2))) | ((RUP_reg2 & ~sign_result_temp_f64_reg2) & (_NX_f64_T | sticky_f64_reg2))) | (RMM_reg2 & guard_f64)) | (((adder_is_negative_reg2 & ~guard_f64) & ~round_f64) & ~sticky_f64_reg2);
	wire _round_add1_uf_f32_T_4 = RDN_reg2 & sign_result_temp_f32_reg2;
	wire _NX_f32_T = guard_f32 | round_f32;
	wire round_add1_f32 = (((((RNE_reg2 & guard_f32) & ((fraction_result_no_round_reg[0] | round_f32) | sticky_f32_reg2)) | (_round_add1_uf_f32_T_4 & (_NX_f32_T | sticky_f32_reg2))) | ((RUP_reg2 & ~sign_result_temp_f32_reg2) & (_NX_f32_T | sticky_f32_reg2))) | (RMM_reg2 & guard_f32)) | (((adder_is_negative_reg2 & ~guard_f32) & ~round_f32) & ~sticky_f32_reg2);
	wire _round_add1_uf_f16_T_4 = RDN_reg2 & sign_result_temp_f16_reg2;
	wire _NX_f16_T = guard_f16 | round_f16;
	wire round_add1_f16 = (((((RNE_reg2 & guard_f16) & ((fraction_result_no_round_reg[0] | round_f16) | sticky_f16_reg2)) | (_round_add1_uf_f16_T_4 & (_NX_f16_T | sticky_f16_reg2))) | ((RUP_reg2 & ~sign_result_temp_f16_reg2) & (_NX_f16_T | sticky_f16_reg2))) | (RMM_reg2 & guard_f16)) | (((adder_is_negative_reg2 & ~guard_f16) & ~round_f16) & ~sticky_f16_reg2);
	wire _round_add1_uf_f64_T_11 = round_f64 | round_uf_f64;
	wire _round_add1_uf_f32_T_11 = round_f32 | round_uf_f32;
	wire _round_add1_uf_f16_T_11 = round_f16 | round_uf_f16;
	wire exponent_add_1_f64 = &fraction_result_no_round_reg & round_add1_f64;
	wire exponent_add_1_f32 = &fraction_result_no_round_reg[22:0] & round_add1_f32;
	wire exponent_add_1_f16 = &fraction_result_no_round_reg[9:0] & round_add1_f16;
	reg is_fix_reg2;
	reg [7:0] lshift_value_reg2;
	wire [11:0] _exponent_result_add_value_f64_T_5 = E_greater_reg2 - {4'h0, lshift_value_reg2};
	wire [11:0] exponent_result_add_value_f64 = (exponent_add_1_f64 | is_fix_reg2 ? _exponent_result_add_value_f64_T_5 + 12'h001 : _exponent_result_add_value_f64_T_5);
	wire [8:0] _GEN = {2'h0, lshift_value_reg2[6:0]};
	wire [8:0] exponent_result_add_value_f32 = (exponent_add_1_f32 | is_fix_reg2 ? (E_greater_reg2[8:0] - _GEN) + 9'h001 : E_greater_reg2[8:0] - _GEN);
	wire [5:0] exponent_result_add_value_f16 = (exponent_add_1_f16 | is_fix_reg2 ? (E_greater_reg2[5:0] - lshift_value_reg2[5:0]) + 6'h01 : E_greater_reg2[5:0] - lshift_value_reg2[5:0]);
	wire exponent_overflow_f64 = exponent_result_add_value_f64[11] | &exponent_result_add_value_f64[10:0];
	wire exponent_overflow_f32 = exponent_result_add_value_f32[8] | &exponent_result_add_value_f32[7:0];
	wire exponent_overflow_f16 = exponent_result_add_value_f16[5] | &exponent_result_add_value_f16[4:0];
	reg exponent_is_min_f64;
	reg exponent_is_min_f32;
	reg exponent_is_min_f16;
	assign NX_f64 = _NX_f64_T | sticky_f64_reg2;
	assign NX_f32 = _NX_f32_T | sticky_f32_reg2;
	assign NX_f16 = _NX_f16_T | sticky_f16_reg2;
	assign UF_f64 = (NX_f64 & exponent_is_min_f64) & (~exponent_add_1_f64 | ~(guard_f64 & (((((RNE_reg2 & round_f64) & ((guard_f64 | round_uf_f64) | sticky_uf_f64_reg2)) | (_round_add1_uf_f64_T_4 & (_round_add1_uf_f64_T_11 | sticky_uf_f64_reg2))) | ((RUP_reg2 & ~sign_result_temp_f64_reg2) & (_round_add1_uf_f64_T_11 | sticky_uf_f64_reg2))) | (RMM_reg2 & round_f64))));
	assign UF_f32 = (NX_f32 & exponent_is_min_f32) & (~exponent_add_1_f32 | ~(guard_f32 & (((((RNE_reg2 & round_f32) & ((guard_f32 | round_uf_f32) | sticky_uf_f32_reg2)) | (_round_add1_uf_f32_T_4 & (_round_add1_uf_f32_T_11 | sticky_uf_f32_reg2))) | ((RUP_reg2 & ~sign_result_temp_f32_reg2) & (_round_add1_uf_f32_T_11 | sticky_uf_f32_reg2))) | (RMM_reg2 & round_f32))));
	assign UF_f16 = (NX_f16 & exponent_is_min_f16) & (~exponent_add_1_f16 | ~(guard_f16 & (((((RNE_reg2 & round_f16) & ((guard_f16 | round_uf_f16) | sticky_uf_f16_reg2)) | (_round_add1_uf_f16_T_4 & (_round_add1_uf_f16_T_11 | sticky_uf_f16_reg2))) | ((RUP_reg2 & ~sign_result_temp_f16_reg2) & (_round_add1_uf_f16_T_11 | sticky_uf_f16_reg2))) | (RMM_reg2 & round_f16))));
	reg normal_result_is_zero_f64_reg2_r;
	reg normal_result_is_zero_f64_reg2;
	reg normal_result_is_zero_f32_reg2_r;
	reg normal_result_is_zero_f32_reg2;
	reg normal_result_is_zero_f16_reg2_r;
	reg normal_result_is_zero_f16_reg2;
	reg has_zero_f64_reg2_r;
	reg has_zero_f64_reg2_r_1;
	reg has_zero_f64_reg2_r_2;
	wire has_zero_f64_reg2 = has_zero_f64_reg2_r_2 | normal_result_is_zero_f64_reg2;
	reg has_zero_f32_reg2_r;
	reg has_zero_f32_reg2_r_1;
	reg has_zero_f32_reg2_r_2;
	wire has_zero_f32_reg2 = has_zero_f32_reg2_r_2 | normal_result_is_zero_f32_reg2;
	reg has_zero_f16_reg2_r;
	reg has_zero_f16_reg2_r_1;
	reg has_zero_f16_reg2_r_2;
	wire has_zero_f16_reg2 = has_zero_f16_reg2_r_2 | normal_result_is_zero_f16_reg2;
	wire [63:0] normal_result_f64 = {sign_result_temp_f64_reg2, (exponent_is_min_f64 ? {10'h000, exponent_add_1_f64} : exponent_result_add_value_f64[10:0]), (round_add1_f64 ? fraction_result_no_round_reg + 52'h0000000000001 : fraction_result_no_round_reg)};
	wire [31:0] normal_result_f32 = {sign_result_temp_f32_reg2, (exponent_is_min_f32 ? {7'h00, exponent_add_1_f32} : exponent_result_add_value_f32[7:0]), (round_add1_f32 ? fraction_result_no_round_reg[22:0] + 23'h000001 : fraction_result_no_round_reg[22:0])};
	wire [15:0] normal_result_f16 = {sign_result_temp_f16_reg2, (exponent_is_min_f16 ? {4'h0, exponent_add_1_f16} : exponent_result_add_value_f16[4:0]), (round_add1_f16 ? fraction_result_no_round_reg[9:0] + 10'h001 : fraction_result_no_round_reg[9:0])};
	reg [63:0] fp_result_fp_a_or_b_is_zero_reg_r;
	reg [63:0] fp_result_fp_a_or_b_is_zero_reg_r_1;
	reg [63:0] fp_result_fp_a_or_b_is_zero_reg;
	reg has_nan_f64_reg2_r;
	reg has_nan_f64_reg2_r_1;
	reg has_nan_f64_reg2;
	reg has_nan_f64_is_NV_reg2_r;
	reg has_nan_f64_is_NV_reg2_r_1;
	reg has_nan_f64_is_NV_reg2;
	reg has_inf_f64_reg2_r;
	reg has_inf_f64_reg2_r_1;
	reg has_inf_f64_reg2;
	reg has_inf_f64_is_NV_reg2_r;
	reg has_inf_f64_is_NV_reg2_r_1;
	reg has_inf_f64_is_NV_reg2;
	reg has_inf_f64_result_inf_sign_reg2_r;
	reg has_inf_f64_result_inf_sign_reg2_r_1;
	reg has_inf_f64_result_inf_sign_reg2;
	reg fp_a_or_b_is_zero_f64_reg2_r;
	reg fp_a_or_b_is_zero_f64_reg2_r_1;
	reg fp_a_or_b_is_zero_f64_reg2;
	reg has_nan_f32_reg2_r;
	reg has_nan_f32_reg2_r_1;
	reg has_nan_f32_reg2;
	reg has_nan_f32_is_NV_reg2_r;
	reg has_nan_f32_is_NV_reg2_r_1;
	reg has_nan_f32_is_NV_reg2;
	reg has_inf_f32_reg2_r;
	reg has_inf_f32_reg2_r_1;
	reg has_inf_f32_reg2;
	reg has_inf_f32_is_NV_reg2_r;
	reg has_inf_f32_is_NV_reg2_r_1;
	reg has_inf_f32_is_NV_reg2;
	reg has_inf_f32_result_inf_sign_reg2_r;
	reg has_inf_f32_result_inf_sign_reg2_r_1;
	reg has_inf_f32_result_inf_sign_reg2;
	reg fp_a_or_b_is_zero_f32_reg2_r;
	reg fp_a_or_b_is_zero_f32_reg2_r_1;
	reg fp_a_or_b_is_zero_f32_reg2;
	reg has_nan_f16_reg2_r;
	reg has_nan_f16_reg2_r_1;
	reg has_nan_f16_reg2;
	reg has_nan_f16_is_NV_reg2_r;
	reg has_nan_f16_is_NV_reg2_r_1;
	reg has_nan_f16_is_NV_reg2;
	reg has_inf_f16_reg2_r;
	reg has_inf_f16_reg2_r_1;
	reg has_inf_f16_reg2;
	reg has_inf_f16_is_NV_reg2_r;
	reg has_inf_f16_is_NV_reg2_r_1;
	reg has_inf_f16_is_NV_reg2;
	reg has_inf_f16_result_inf_sign_reg2_r;
	reg has_inf_f16_result_inf_sign_reg2_r_1;
	reg has_inf_f16_result_inf_sign_reg2;
	reg fp_a_or_b_is_zero_f16_reg2_r;
	reg fp_a_or_b_is_zero_f16_reg2_r_1;
	reg fp_a_or_b_is_zero_f16_reg2;
	always @(posedge clock) begin
		if (reset) begin
			fire_reg0_last_r <= 1'h0;
			fire_reg1_last_r <= 1'h0;
		end
		else begin
			if (io_fire | fire_reg0_last_r)
				fire_reg0_last_r <= io_fire;
			if (fire_reg0_last_r | fire_reg1_last_r)
				fire_reg1_last_r <= fire_reg0_last_r;
		end
		if (io_fire) begin : sv2v_autoblock_1
			reg is_fmul;
			reg is_fnmacc;
			reg fp_a_is_sign_inv;
			reg fp_c_is_sign_inv;
			reg [63:0] fp_c_f64;
			reg [31:0] fp_c_f32;
			reg [15:0] fp_c_f16;
			reg sign_a_b_f16;
			reg sign_a_b_f32;
			reg sign_a_b_f64;
			reg is_sub_f64;
			reg is_sub_f32;
			reg is_sub_f16;
			reg _Ec_fix_f64_T_3;
			reg _Ec_fix_f32_T_3;
			reg _Ec_fix_f16_T_3;
			reg [12:0] _Eab_f64_T_6;
			reg [9:0] _Eab_f32_T_6;
			reg [6:0] _Eab_f16_T_6;
			reg [12:0] _rshift_value_f64_T_2;
			reg [9:0] _rshift_value_f32_T_2;
			reg [6:0] _rshift_value_f16_T_2;
			reg [162:0] rshift_result_with_grs_f64_res_vec_1;
			reg [162:0] rshift_result_with_grs_f64_res_vec_2;
			reg [162:0] rshift_result_with_grs_f64_res_vec_3;
			reg rshift_result_with_grs_f64_sticky_vec_3;
			reg [162:0] rshift_result_with_grs_f64_res_vec_4;
			reg rshift_result_with_grs_f64_sticky_vec_4;
			reg [162:0] rshift_result_with_grs_f64_res_vec_5;
			reg rshift_result_with_grs_f64_sticky_vec_5;
			reg [162:0] rshift_result_with_grs_f64_res_vec_6;
			reg rshift_result_with_grs_f64_sticky_vec_6;
			reg [162:0] rshift_result_with_grs_f64_res_vec_7;
			reg rshift_result_with_grs_f64_sticky_vec_7;
			reg [162:0] rshift_result_with_grs_f64_res_vec_8;
			reg [75:0] rshift_result_with_grs_f32_res_vec_1;
			reg [75:0] rshift_result_with_grs_f32_res_vec_2;
			reg [75:0] rshift_result_with_grs_f32_res_vec_3;
			reg rshift_result_with_grs_f32_sticky_vec_3;
			reg [75:0] rshift_result_with_grs_f32_res_vec_4;
			reg rshift_result_with_grs_f32_sticky_vec_4;
			reg [75:0] rshift_result_with_grs_f32_res_vec_5;
			reg rshift_result_with_grs_f32_sticky_vec_5;
			reg [75:0] rshift_result_with_grs_f32_res_vec_6;
			reg rshift_result_with_grs_f32_sticky_vec_6;
			reg [75:0] rshift_result_with_grs_f32_res_vec_7;
			reg [36:0] rshift_result_with_grs_f16_res_vec_1;
			reg [36:0] rshift_result_with_grs_f16_res_vec_2;
			reg [36:0] rshift_result_with_grs_f16_res_vec_3;
			reg rshift_result_with_grs_f16_sticky_vec_3;
			reg [36:0] rshift_result_with_grs_f16_res_vec_4;
			reg rshift_result_with_grs_f16_sticky_vec_4;
			reg [36:0] rshift_result_with_grs_f16_res_vec_5;
			reg rshift_result_with_grs_f16_sticky_vec_5;
			reg [36:0] rshift_result_with_grs_f16_res_vec_6;
			reg Ec_is_too_big_f64;
			reg Ec_is_too_big_f32;
			reg Ec_is_too_big_f16;
			reg Ec_is_medium_f64;
			reg Ec_is_medium_f32;
			reg Ec_is_medium_f16;
			reg [160:0] rshift_result_f64;
			reg [73:0] rshift_result_f32;
			reg [34:0] rshift_result_f16;
			reg Eab_is_greater_f64;
			reg Eab_is_greater_f32;
			reg Eab_is_greater_f16;
			reg RDN;
			reg fp_a_is_zero_f64;
			reg fp_a_is_zero_f32;
			reg fp_a_is_zero_f16;
			reg fp_b_is_zero_f64;
			reg fp_b_is_zero_f32;
			reg fp_b_is_zero_f16;
			reg fp_c_is_zero_f64;
			reg fp_c_is_zero_f32;
			reg fp_c_is_zero_f16;
			reg _fp_a_or_b_is_zero_f64_reg2_T;
			reg _fp_a_or_b_is_zero_f32_reg2_T;
			reg _fp_a_or_b_is_zero_f16_reg2_T;
			reg fp_a_is_inf_f64;
			reg fp_a_is_inf_f32;
			reg fp_a_is_inf_f16;
			reg fp_b_is_inf_f64;
			reg fp_b_is_inf_f32;
			reg fp_b_is_inf_f16;
			reg fp_c_is_inf_f64;
			reg fp_c_is_inf_f32;
			reg fp_c_is_inf_f16;
			reg _has_inf_f64_result_inf_sign_reg2_T;
			reg _has_inf_f32_result_inf_sign_reg2_T;
			reg _has_inf_f16_result_inf_sign_reg2_T;
			reg _has_inf_f64_is_NV_reg2_T;
			reg _has_inf_f64_is_NV_reg2_T_1;
			reg _has_inf_f32_is_NV_reg2_T;
			reg _has_inf_f32_is_NV_reg2_T_1;
			reg _has_inf_f16_is_NV_reg2_T;
			reg _has_inf_f16_is_NV_reg2_T_1;
			is_fmul = io_op_code == 4'h0;
			is_fnmacc = io_op_code == 4'h2;
			fp_a_is_sign_inv = is_fnmacc | (io_op_code == 4'h4);
			fp_c_is_sign_inv = is_fnmacc | (io_op_code == 4'h3);
			fp_c_f64 = (is_fmul ? 64'h0000000000000000 : {fp_c_is_sign_inv ^ io_fp_c[63], io_fp_c[62:0]});
			fp_c_f32 = (is_fmul ? 32'h00000000 : {fp_c_is_sign_inv ^ io_fp_c[31], io_fp_c[30:0]});
			fp_c_f16 = (is_fmul ? 16'h0000 : {fp_c_is_sign_inv ^ io_fp_c[15], io_fp_c[14:0]});
			sign_a_b_f16 = (fp_a_is_sign_inv ^ io_fp_a[15]) ^ io_fp_b[15];
			sign_a_b_f32 = (fp_a_is_sign_inv ^ io_fp_a[31]) ^ io_fp_b[31];
			sign_a_b_f64 = (fp_a_is_sign_inv ^ io_fp_a[63]) ^ io_fp_b[63];
			is_sub_f64 = sign_a_b_f64 ^ fp_c_f64[63];
			is_sub_f32 = sign_a_b_f32 ^ fp_c_f32[31];
			is_sub_f16 = sign_a_b_f16 ^ fp_c_f16[15];
			_Ec_fix_f64_T_3 = ~(|fp_c_f64[62:52]) | fp_c_f64[52];
			_Ec_fix_f32_T_3 = ~(|fp_c_f32[30:23]) | fp_c_f32[23];
			_Ec_fix_f16_T_3 = ~(|fp_c_f16[14:10]) | fp_c_f16[10];
			_Eab_f64_T_6 = {1'h0, {1'h0, io_fp_a[62:53], ~(|io_fp_a[62:52]) | io_fp_a[52]} + {1'h0, io_fp_b[62:53], ~(|io_fp_b[62:52]) | io_fp_b[52]}} - 13'h03c7;
			_Eab_f32_T_6 = {1'h0, {1'h0, io_fp_a[30:24], ~(|io_fp_a[30:23]) | io_fp_a[23]} + {1'h0, io_fp_b[30:24], ~(|io_fp_b[30:23]) | io_fp_b[23]}} - 10'h064;
			_Eab_f16_T_6 = {1'h0, {1'h0, io_fp_a[14:11], ~(|io_fp_a[14:10]) | io_fp_a[10]} + {1'h0, io_fp_b[14:11], ~(|io_fp_b[14:10]) | io_fp_b[10]}} - 7'h01;
			_rshift_value_f64_T_2 = _Eab_f64_T_6 - {2'h0, fp_c_f64[62:53], _Ec_fix_f64_T_3};
			_rshift_value_f32_T_2 = _Eab_f32_T_6 - {2'h0, fp_c_f32[30:24], _Ec_fix_f32_T_3};
			_rshift_value_f16_T_2 = _Eab_f16_T_6 - {2'h0, fp_c_f16[14:11], _Ec_fix_f16_T_3};
			rshift_result_with_grs_f64_res_vec_1 = (_rshift_value_f64_T_2[0] ? {1'h0, |fp_c_f64[62:52], fp_c_f64[51:0], 109'h0000000000000000000000000000} : {|fp_c_f64[62:52], fp_c_f64[51:0], 110'h0000000000000000000000000000});
			rshift_result_with_grs_f64_res_vec_2 = (_rshift_value_f64_T_2[1] ? {2'h0, rshift_result_with_grs_f64_res_vec_1[162:2]} : rshift_result_with_grs_f64_res_vec_1);
			rshift_result_with_grs_f64_res_vec_3 = (_rshift_value_f64_T_2[2] ? {4'h0, rshift_result_with_grs_f64_res_vec_2[162:4]} : rshift_result_with_grs_f64_res_vec_2);
			rshift_result_with_grs_f64_sticky_vec_3 = _rshift_value_f64_T_2[2] & |rshift_result_with_grs_f64_res_vec_2[3:0];
			rshift_result_with_grs_f64_res_vec_4 = (_rshift_value_f64_T_2[3] ? {8'h00, rshift_result_with_grs_f64_res_vec_3[162:8]} : rshift_result_with_grs_f64_res_vec_3);
			rshift_result_with_grs_f64_sticky_vec_4 = (_rshift_value_f64_T_2[3] ? |{rshift_result_with_grs_f64_sticky_vec_3, rshift_result_with_grs_f64_res_vec_3[7:0]} : rshift_result_with_grs_f64_sticky_vec_3);
			rshift_result_with_grs_f64_res_vec_5 = (_rshift_value_f64_T_2[4] ? {16'h0000, rshift_result_with_grs_f64_res_vec_4[162:16]} : rshift_result_with_grs_f64_res_vec_4);
			rshift_result_with_grs_f64_sticky_vec_5 = (_rshift_value_f64_T_2[4] ? |{rshift_result_with_grs_f64_sticky_vec_4, rshift_result_with_grs_f64_res_vec_4[15:0]} : rshift_result_with_grs_f64_sticky_vec_4);
			rshift_result_with_grs_f64_res_vec_6 = (_rshift_value_f64_T_2[5] ? {32'h00000000, rshift_result_with_grs_f64_res_vec_5[162:32]} : rshift_result_with_grs_f64_res_vec_5);
			rshift_result_with_grs_f64_sticky_vec_6 = (_rshift_value_f64_T_2[5] ? |{rshift_result_with_grs_f64_sticky_vec_5, rshift_result_with_grs_f64_res_vec_5[31:0]} : rshift_result_with_grs_f64_sticky_vec_5);
			rshift_result_with_grs_f64_res_vec_7 = (_rshift_value_f64_T_2[6] ? {64'h0000000000000000, rshift_result_with_grs_f64_res_vec_6[162:64]} : rshift_result_with_grs_f64_res_vec_6);
			rshift_result_with_grs_f64_sticky_vec_7 = (_rshift_value_f64_T_2[6] ? |{rshift_result_with_grs_f64_sticky_vec_6, rshift_result_with_grs_f64_res_vec_6[63:0]} : rshift_result_with_grs_f64_sticky_vec_6);
			rshift_result_with_grs_f64_res_vec_8 = (_rshift_value_f64_T_2[7] ? {128'h00000000000000000000000000000000, rshift_result_with_grs_f64_res_vec_7[162:128]} : rshift_result_with_grs_f64_res_vec_7);
			rshift_result_with_grs_f32_res_vec_1 = (_rshift_value_f32_T_2[0] ? {1'h0, |fp_c_f32[30:23], fp_c_f32[22:0], 51'h0000000000000} : {|fp_c_f32[30:23], fp_c_f32[22:0], 52'h0000000000000});
			rshift_result_with_grs_f32_res_vec_2 = (_rshift_value_f32_T_2[1] ? {2'h0, rshift_result_with_grs_f32_res_vec_1[75:2]} : rshift_result_with_grs_f32_res_vec_1);
			rshift_result_with_grs_f32_res_vec_3 = (_rshift_value_f32_T_2[2] ? {4'h0, rshift_result_with_grs_f32_res_vec_2[75:4]} : rshift_result_with_grs_f32_res_vec_2);
			rshift_result_with_grs_f32_sticky_vec_3 = _rshift_value_f32_T_2[2] & |rshift_result_with_grs_f32_res_vec_2[3:0];
			rshift_result_with_grs_f32_res_vec_4 = (_rshift_value_f32_T_2[3] ? {8'h00, rshift_result_with_grs_f32_res_vec_3[75:8]} : rshift_result_with_grs_f32_res_vec_3);
			rshift_result_with_grs_f32_sticky_vec_4 = (_rshift_value_f32_T_2[3] ? |{rshift_result_with_grs_f32_sticky_vec_3, rshift_result_with_grs_f32_res_vec_3[7:0]} : rshift_result_with_grs_f32_sticky_vec_3);
			rshift_result_with_grs_f32_res_vec_5 = (_rshift_value_f32_T_2[4] ? {16'h0000, rshift_result_with_grs_f32_res_vec_4[75:16]} : rshift_result_with_grs_f32_res_vec_4);
			rshift_result_with_grs_f32_sticky_vec_5 = (_rshift_value_f32_T_2[4] ? |{rshift_result_with_grs_f32_sticky_vec_4, rshift_result_with_grs_f32_res_vec_4[15:0]} : rshift_result_with_grs_f32_sticky_vec_4);
			rshift_result_with_grs_f32_res_vec_6 = (_rshift_value_f32_T_2[5] ? {32'h00000000, rshift_result_with_grs_f32_res_vec_5[75:32]} : rshift_result_with_grs_f32_res_vec_5);
			rshift_result_with_grs_f32_sticky_vec_6 = (_rshift_value_f32_T_2[5] ? |{rshift_result_with_grs_f32_sticky_vec_5, rshift_result_with_grs_f32_res_vec_5[31:0]} : rshift_result_with_grs_f32_sticky_vec_5);
			rshift_result_with_grs_f32_res_vec_7 = (_rshift_value_f32_T_2[6] ? {64'h0000000000000000, rshift_result_with_grs_f32_res_vec_6[75:64]} : rshift_result_with_grs_f32_res_vec_6);
			rshift_result_with_grs_f16_res_vec_1 = (_rshift_value_f16_T_2[0] ? {1'h0, |fp_c_f16[14:10], fp_c_f16[9:0], 25'h0000000} : {|fp_c_f16[14:10], fp_c_f16[9:0], 26'h0000000});
			rshift_result_with_grs_f16_res_vec_2 = (_rshift_value_f16_T_2[1] ? {2'h0, rshift_result_with_grs_f16_res_vec_1[36:2]} : rshift_result_with_grs_f16_res_vec_1);
			rshift_result_with_grs_f16_res_vec_3 = (_rshift_value_f16_T_2[2] ? {4'h0, rshift_result_with_grs_f16_res_vec_2[36:4]} : rshift_result_with_grs_f16_res_vec_2);
			rshift_result_with_grs_f16_sticky_vec_3 = _rshift_value_f16_T_2[2] & |rshift_result_with_grs_f16_res_vec_2[3:0];
			rshift_result_with_grs_f16_res_vec_4 = (_rshift_value_f16_T_2[3] ? {8'h00, rshift_result_with_grs_f16_res_vec_3[36:8]} : rshift_result_with_grs_f16_res_vec_3);
			rshift_result_with_grs_f16_sticky_vec_4 = (_rshift_value_f16_T_2[3] ? |{rshift_result_with_grs_f16_sticky_vec_3, rshift_result_with_grs_f16_res_vec_3[7:0]} : rshift_result_with_grs_f16_sticky_vec_3);
			rshift_result_with_grs_f16_res_vec_5 = (_rshift_value_f16_T_2[4] ? {16'h0000, rshift_result_with_grs_f16_res_vec_4[36:16]} : rshift_result_with_grs_f16_res_vec_4);
			rshift_result_with_grs_f16_sticky_vec_5 = (_rshift_value_f16_T_2[4] ? |{rshift_result_with_grs_f16_sticky_vec_4, rshift_result_with_grs_f16_res_vec_4[15:0]} : rshift_result_with_grs_f16_sticky_vec_4);
			rshift_result_with_grs_f16_res_vec_6 = (_rshift_value_f16_T_2[5] ? {32'h00000000, rshift_result_with_grs_f16_res_vec_5[36:32]} : rshift_result_with_grs_f16_res_vec_5);
			Ec_is_too_big_f64 = $signed(_rshift_value_f64_T_2) < 13'sh0001;
			Ec_is_too_big_f32 = $signed(_rshift_value_f32_T_2) < 10'sh001;
			Ec_is_too_big_f16 = $signed(_rshift_value_f16_T_2) < 7'sh01;
			Ec_is_medium_f64 = ($signed(_rshift_value_f64_T_2) > 13'sh0000) & ($signed(_rshift_value_f64_T_2) < 13'sh00a4);
			Ec_is_medium_f32 = ($signed(_rshift_value_f32_T_2) > 10'sh000) & ($signed(_rshift_value_f32_T_2) < 10'sh04d);
			Ec_is_medium_f16 = ($signed(_rshift_value_f16_T_2) > 7'sh00) & ($signed(_rshift_value_f16_T_2) < 7'sh26);
			rshift_result_f64 = (Ec_is_medium_f64 ? rshift_result_with_grs_f64_res_vec_8[162:2] : (Ec_is_too_big_f64 ? {|fp_c_f64[62:52], fp_c_f64[51:0], 108'h000000000000000000000000000} : 161'h00000000000000000000000000000000000000000));
			rshift_result_f32 = (Ec_is_medium_f32 ? rshift_result_with_grs_f32_res_vec_7[75:2] : (Ec_is_too_big_f32 ? {|fp_c_f32[30:23], fp_c_f32[22:0], 50'h0000000000000} : 74'h0000000000000000000));
			rshift_result_f16 = (Ec_is_medium_f16 ? rshift_result_with_grs_f16_res_vec_6[36:2] : (Ec_is_too_big_f16 ? {|fp_c_f16[14:10], fp_c_f16[9:0], 24'h000000} : 35'h000000000));
			Eab_is_greater_f64 = $signed(_rshift_value_f64_T_2) > 13'sh0000;
			Eab_is_greater_f32 = $signed(_rshift_value_f32_T_2) > 10'sh000;
			Eab_is_greater_f16 = $signed(_rshift_value_f16_T_2) > 7'sh00;
			RDN = io_round_mode == 3'h2;
			fp_a_is_zero_f64 = ~io_fp_aIsFpCanonicalNAN & (io_fp_a[62:0] == 63'h0000000000000000);
			fp_a_is_zero_f32 = ~io_fp_aIsFpCanonicalNAN & (io_fp_a[30:0] == 31'h00000000);
			fp_a_is_zero_f16 = ~io_fp_aIsFpCanonicalNAN & (io_fp_a[14:0] == 15'h0000);
			fp_b_is_zero_f64 = ~io_fp_bIsFpCanonicalNAN & (io_fp_b[62:0] == 63'h0000000000000000);
			fp_b_is_zero_f32 = ~io_fp_bIsFpCanonicalNAN & (io_fp_b[30:0] == 31'h00000000);
			fp_b_is_zero_f16 = ~io_fp_bIsFpCanonicalNAN & (io_fp_b[14:0] == 15'h0000);
			fp_c_is_zero_f64 = ~io_fp_cIsFpCanonicalNAN & ~(|fp_c_f64[62:0]);
			fp_c_is_zero_f32 = ~io_fp_cIsFpCanonicalNAN & ~(|fp_c_f32[30:0]);
			fp_c_is_zero_f16 = ~io_fp_cIsFpCanonicalNAN & ~(|fp_c_f16[14:0]);
			_fp_a_or_b_is_zero_f64_reg2_T = fp_a_is_zero_f64 | fp_b_is_zero_f64;
			_fp_a_or_b_is_zero_f32_reg2_T = fp_a_is_zero_f32 | fp_b_is_zero_f32;
			_fp_a_or_b_is_zero_f16_reg2_T = fp_a_is_zero_f16 | fp_b_is_zero_f16;
			fp_a_is_inf_f64 = (~io_fp_aIsFpCanonicalNAN & (&io_fp_a[62:52])) & (io_fp_a[51:0] == 52'h0000000000000);
			fp_a_is_inf_f32 = (~io_fp_aIsFpCanonicalNAN & (&io_fp_a[30:23])) & (io_fp_a[22:0] == 23'h000000);
			fp_a_is_inf_f16 = (~io_fp_aIsFpCanonicalNAN & (&io_fp_a[14:10])) & (io_fp_a[9:0] == 10'h000);
			fp_b_is_inf_f64 = (~io_fp_bIsFpCanonicalNAN & (&io_fp_b[62:52])) & (io_fp_b[51:0] == 52'h0000000000000);
			fp_b_is_inf_f32 = (~io_fp_bIsFpCanonicalNAN & (&io_fp_b[30:23])) & (io_fp_b[22:0] == 23'h000000);
			fp_b_is_inf_f16 = (~io_fp_bIsFpCanonicalNAN & (&io_fp_b[14:10])) & (io_fp_b[9:0] == 10'h000);
			fp_c_is_inf_f64 = (~io_fp_cIsFpCanonicalNAN & (&fp_c_f64[62:52])) & (fp_c_f64[51:0] == 52'h0000000000000);
			fp_c_is_inf_f32 = (~io_fp_cIsFpCanonicalNAN & (&fp_c_f32[30:23])) & (fp_c_f32[22:0] == 23'h000000);
			fp_c_is_inf_f16 = (~io_fp_cIsFpCanonicalNAN & (&fp_c_f16[14:10])) & (fp_c_f16[9:0] == 10'h000);
			_has_inf_f64_result_inf_sign_reg2_T = fp_a_is_inf_f64 | fp_b_is_inf_f64;
			_has_inf_f32_result_inf_sign_reg2_T = fp_a_is_inf_f32 | fp_b_is_inf_f32;
			_has_inf_f16_result_inf_sign_reg2_T = fp_a_is_inf_f16 | fp_b_is_inf_f16;
			_has_inf_f64_is_NV_reg2_T = fp_a_is_inf_f64 & fp_b_is_zero_f64;
			_has_inf_f64_is_NV_reg2_T_1 = fp_a_is_zero_f64 & fp_b_is_inf_f64;
			_has_inf_f32_is_NV_reg2_T = fp_a_is_inf_f32 & fp_b_is_zero_f32;
			_has_inf_f32_is_NV_reg2_T_1 = fp_a_is_zero_f32 & fp_b_is_inf_f32;
			_has_inf_f16_is_NV_reg2_T = fp_a_is_inf_f16 & fp_b_is_zero_f16;
			_has_inf_f16_is_NV_reg2_T_1 = fp_a_is_zero_f16 & fp_b_is_inf_f16;
			is_fp64_reg0 <= &io_fp_format;
			is_fp32_reg0 <= is_fp32;
			is_sub_f64_reg0 <= is_sub_f64;
			rshift_guard_reg <= (&io_fp_format ? Ec_is_medium_f64 & rshift_result_with_grs_f64_res_vec_8[1] : (is_fp32 ? Ec_is_medium_f32 & rshift_result_with_grs_f32_res_vec_7[1] : Ec_is_medium_f16 & rshift_result_with_grs_f16_res_vec_6[1]));
			rshift_round_reg <= (&io_fp_format ? Ec_is_medium_f64 & rshift_result_with_grs_f64_res_vec_8[0] : (is_fp32 ? Ec_is_medium_f32 & rshift_result_with_grs_f32_res_vec_7[0] : Ec_is_medium_f16 & rshift_result_with_grs_f16_res_vec_6[0]));
			rshift_sticky_reg <= (&io_fp_format ? (Ec_is_medium_f64 ? (_rshift_value_f64_T_2[7] ? |{rshift_result_with_grs_f64_sticky_vec_7, rshift_result_with_grs_f64_res_vec_7[127:0]} : rshift_result_with_grs_f64_sticky_vec_7) : ~Ec_is_too_big_f64 & |fp_c_f64[62:0]) : (is_fp32 ? (Ec_is_medium_f32 ? (_rshift_value_f32_T_2[6] ? |{rshift_result_with_grs_f32_sticky_vec_6, rshift_result_with_grs_f32_res_vec_6[63:0]} : rshift_result_with_grs_f32_sticky_vec_6) : ~Ec_is_too_big_f32 & |fp_c_f32[30:0]) : (Ec_is_medium_f16 ? (_rshift_value_f16_T_2[5] ? |{rshift_result_with_grs_f16_sticky_vec_5, rshift_result_with_grs_f16_res_vec_5[31:0]} : rshift_result_with_grs_f16_sticky_vec_5) : ~Ec_is_too_big_f16 & |fp_c_f16[14:0])));
			fp_c_rshiftValue_inv_reg <= (&io_fp_format ? (is_sub_f64 ? {1'h1, ~rshift_result_f64} : {1'h0, rshift_result_f64}) : {87'h0000000000000000000000, (is_fp32 ? (is_sub_f32 ? {1'h1, ~rshift_result_f32} : {1'h0, rshift_result_f32}) : {39'h0000000000, (is_sub_f16 ? {1'h1, ~rshift_result_f16} : {1'h0, rshift_result_f16})})});
			CSA3to2_in_b_r <= is_sub_f32;
			CSA3to2_in_b_r_1 <= is_sub_f16;
			adder_f32_r <= is_sub_f32;
			adder_f16_r <= is_sub_f16;
			E_greater_reg2_r <= (&io_fp_format ? (Eab_is_greater_f64 ? _Eab_f64_T_6[11:0] : {1'h0, fp_c_f64[62:53], _Ec_fix_f64_T_3}) : {3'h0, (is_fp32 ? (Eab_is_greater_f32 ? _Eab_f32_T_6[8:0] : {1'h0, fp_c_f32[30:24], _Ec_fix_f32_T_3}) : {3'h0, (Eab_is_greater_f16 ? _Eab_f16_T_6[5:0] : {1'h0, fp_c_f16[14:11], _Ec_fix_f16_T_3})})});
			lshift_value_max_reg0 <= (&io_fp_format ? (Eab_is_greater_f64 ? _Eab_f64_T_6[11:0] - 12'h001 : {1'h0, {fp_c_f64[62:53], _Ec_fix_f64_T_3} - 11'h001}) : {3'h0, (is_fp32 ? (Eab_is_greater_f32 ? _Eab_f32_T_6[8:0] - 9'h001 : {1'h0, {fp_c_f32[30:24], _Ec_fix_f32_T_3} - 8'h01}) : {3'h0, (Eab_is_greater_f16 ? _Eab_f16_T_6[5:0] - 6'h01 : {1'h0, {fp_c_f16[14:11], _Ec_fix_f16_T_3} - 5'h01})})});
			sign_result_temp_f64_reg2_r <= fp_c_f64[63];
			sign_result_temp_f64_reg2_r_1 <= sign_a_b_f64;
			sign_result_temp_f32_reg2_r <= fp_c_f32[31];
			sign_result_temp_f32_reg2_r_1 <= sign_a_b_f32;
			sign_result_temp_f16_reg2_r <= fp_c_f16[15];
			sign_result_temp_f16_reg2_r_1 <= sign_a_b_f16;
			RNE_reg2_r <= io_round_mode == 3'h0;
			RTZ_reg2_r <= io_round_mode == 3'h1;
			RDN_reg2_r <= RDN;
			RUP_reg2_r <= io_round_mode == 3'h3;
			RMM_reg2_r <= io_round_mode == 3'h4;
			has_zero_f64_reg2_r <= _fp_a_or_b_is_zero_f64_reg2_T | fp_c_is_zero_f64;
			has_zero_f32_reg2_r <= _fp_a_or_b_is_zero_f32_reg2_T | fp_c_is_zero_f32;
			has_zero_f16_reg2_r <= _fp_a_or_b_is_zero_f16_reg2_T | fp_c_is_zero_f16;
			fp_result_fp_a_or_b_is_zero_reg_r <= (&io_fp_format ? {(fp_c_is_zero_f64 ? (is_fmul ? sign_a_b_f64 : (sign_a_b_f64 & fp_c_f64[63]) | (RDN & is_sub_f64)) : fp_c_f64[63]), fp_c_f64[62:0]} : {32'h00000000, (is_fp32 ? {(fp_c_is_zero_f32 ? (is_fmul ? sign_a_b_f32 : (sign_a_b_f32 & fp_c_f32[31]) | (RDN & is_sub_f32)) : fp_c_f32[31]), fp_c_f32[30:0]} : {16'h0000, (fp_c_is_zero_f16 ? (is_fmul ? sign_a_b_f16 : (sign_a_b_f16 & fp_c_f16[15]) | (RDN & is_sub_f16)) : fp_c_f16[15]), fp_c_f16[14:0]})});
			has_nan_f64_reg2_r <= ((((io_fp_aIsFpCanonicalNAN | (&io_fp_a[62:52] & |io_fp_a[51:0])) | io_fp_bIsFpCanonicalNAN) | (&io_fp_b[62:52] & |io_fp_b[51:0])) | io_fp_cIsFpCanonicalNAN) | (&fp_c_f64[62:52] & |fp_c_f64[51:0]);
			has_nan_f64_is_NV_reg2_r <= ((((((~io_fp_aIsFpCanonicalNAN & (&io_fp_a[62:52])) & ~io_fp_a[51]) & |io_fp_a[50:0]) | (((~io_fp_bIsFpCanonicalNAN & (&io_fp_b[62:52])) & ~io_fp_b[51]) & |io_fp_b[50:0])) | (((~io_fp_cIsFpCanonicalNAN & (&fp_c_f64[62:52])) & ~fp_c_f64[51]) & |fp_c_f64[50:0])) | _has_inf_f64_is_NV_reg2_T) | _has_inf_f64_is_NV_reg2_T_1;
			has_inf_f64_reg2_r <= _has_inf_f64_result_inf_sign_reg2_T | fp_c_is_inf_f64;
			has_inf_f64_is_NV_reg2_r <= (_has_inf_f64_is_NV_reg2_T | _has_inf_f64_is_NV_reg2_T_1) | ((fp_c_is_inf_f64 & _has_inf_f64_result_inf_sign_reg2_T) & is_sub_f64);
			has_inf_f64_result_inf_sign_reg2_r <= (_has_inf_f64_result_inf_sign_reg2_T ? sign_a_b_f64 : fp_c_f64[63]);
			fp_a_or_b_is_zero_f64_reg2_r <= _fp_a_or_b_is_zero_f64_reg2_T;
			has_nan_f32_reg2_r <= ((((io_fp_aIsFpCanonicalNAN | (&io_fp_a[30:23] & |io_fp_a[22:0])) | io_fp_bIsFpCanonicalNAN) | (&io_fp_b[30:23] & |io_fp_b[22:0])) | io_fp_cIsFpCanonicalNAN) | (&fp_c_f32[30:23] & |fp_c_f32[22:0]);
			has_nan_f32_is_NV_reg2_r <= ((((((~io_fp_aIsFpCanonicalNAN & (&io_fp_a[30:23])) & ~io_fp_a[22]) & |io_fp_a[21:0]) | (((~io_fp_bIsFpCanonicalNAN & (&io_fp_b[30:23])) & ~io_fp_b[22]) & |io_fp_b[21:0])) | (((~io_fp_cIsFpCanonicalNAN & (&fp_c_f32[30:23])) & ~fp_c_f32[22]) & |fp_c_f32[21:0])) | _has_inf_f32_is_NV_reg2_T) | _has_inf_f32_is_NV_reg2_T_1;
			has_inf_f32_reg2_r <= _has_inf_f32_result_inf_sign_reg2_T | fp_c_is_inf_f32;
			has_inf_f32_is_NV_reg2_r <= (_has_inf_f32_is_NV_reg2_T | _has_inf_f32_is_NV_reg2_T_1) | ((fp_c_is_inf_f32 & _has_inf_f32_result_inf_sign_reg2_T) & is_sub_f32);
			has_inf_f32_result_inf_sign_reg2_r <= (_has_inf_f32_result_inf_sign_reg2_T ? sign_a_b_f32 : fp_c_f32[31]);
			fp_a_or_b_is_zero_f32_reg2_r <= _fp_a_or_b_is_zero_f32_reg2_T;
			has_nan_f16_reg2_r <= ((((io_fp_aIsFpCanonicalNAN | (&io_fp_a[14:10] & |io_fp_a[9:0])) | io_fp_bIsFpCanonicalNAN) | (&io_fp_b[14:10] & |io_fp_b[9:0])) | io_fp_cIsFpCanonicalNAN) | (&fp_c_f16[14:10] & |fp_c_f16[9:0]);
			has_nan_f16_is_NV_reg2_r <= ((((((~io_fp_aIsFpCanonicalNAN & (&io_fp_a[14:10])) & ~io_fp_a[9]) & |io_fp_a[8:0]) | (((~io_fp_bIsFpCanonicalNAN & (&io_fp_b[14:10])) & ~io_fp_b[9]) & |io_fp_b[8:0])) | (((~io_fp_cIsFpCanonicalNAN & (&fp_c_f16[14:10])) & ~fp_c_f16[9]) & |fp_c_f16[8:0])) | _has_inf_f16_is_NV_reg2_T) | _has_inf_f16_is_NV_reg2_T_1;
			has_inf_f16_reg2_r <= _has_inf_f16_result_inf_sign_reg2_T | fp_c_is_inf_f16;
			has_inf_f16_is_NV_reg2_r <= (_has_inf_f16_is_NV_reg2_T | _has_inf_f16_is_NV_reg2_T_1) | ((fp_c_is_inf_f16 & _has_inf_f16_result_inf_sign_reg2_T) & is_sub_f16);
			has_inf_f16_result_inf_sign_reg2_r <= (_has_inf_f16_result_inf_sign_reg2_T ? sign_a_b_f16 : fp_c_f16[15]);
			fp_a_or_b_is_zero_f16_reg2_r <= _fp_a_or_b_is_zero_f16_reg2_T;
		end
		if (fire_reg0_last_r) begin : sv2v_autoblock_2
			reg [106:0] _adder_lowbit_f64_T;
			reg [55:0] _fp_c_rshift_result_high_inv_add1_T_9;
			reg [55:0] _adder_f64_T_2;
			reg [2:0] _adder_f16_T_4;
			reg [1:0] _adder_f16_T_9;
			reg [2:0] _adder_f64_T_6;
			reg [1:0] _adder_f64_T_10;
			reg [163:0] adder_f64;
			reg [26:0] _adder_f32_T_2;
			reg [2:0] _adder_f32_T_6;
			reg [1:0] _adder_f32_T_10;
			reg [76:0] adder_f32;
			reg [13:0] _adder_f16_T_2;
			reg [2:0] _adder_f16_T_6;
			reg [1:0] _adder_f16_T_10;
			reg [37:0] adder_f16;
			reg [162:0] lshift_value_mask_f64;
			reg [75:0] lshift_value_mask_f32;
			reg [36:0] lshift_value_mask_f16;
			reg [63:0] _tzd_adder_f64_reg_d_T_10;
			reg [63:0] _tzd_adder_f64_reg_d_T_20;
			reg [63:0] _tzd_adder_f64_reg_d_T_30;
			reg [63:0] _tzd_adder_f64_reg_d_T_40;
			reg [63:0] _tzd_adder_f64_reg_d_T_50;
			reg [63:0] _tzd_adder_f64_reg_d_T_70;
			reg [63:0] _tzd_adder_f64_reg_d_T_80;
			reg [63:0] _tzd_adder_f64_reg_d_T_90;
			reg [63:0] _tzd_adder_f64_reg_d_T_100;
			reg [63:0] _tzd_adder_f64_reg_d_T_110;
			reg [31:0] _tzd_adder_f64_reg_d_T_132;
			reg [31:0] _tzd_adder_f64_reg_d_T_142;
			reg [31:0] _tzd_adder_f64_reg_d_T_152;
			reg [31:0] _tzd_adder_f64_reg_d_T_162;
			reg [63:0] _tzd_adder_f32_reg_d_T_9;
			reg [63:0] _tzd_adder_f32_reg_d_T_19;
			reg [63:0] _tzd_adder_f32_reg_d_T_29;
			reg [63:0] _tzd_adder_f32_reg_d_T_39;
			reg [63:0] _tzd_adder_f32_reg_d_T_49;
			reg [7:0] _tzd_adder_f32_reg_d_T_70;
			reg [7:0] _tzd_adder_f32_reg_d_T_80;
			reg [31:0] _tzd_adder_f16_reg_d_T_9;
			reg [31:0] _tzd_adder_f16_reg_d_T_19;
			reg [31:0] _tzd_adder_f16_reg_d_T_29;
			reg [31:0] _tzd_adder_f16_reg_d_T_39;
			reg [162:0] lzd_adder_inv_mask_f64_reg_d;
			reg [75:0] lzd_adder_inv_mask_f32_reg_d;
			reg [36:0] lzd_adder_inv_mask_f16_reg_d;
			_adder_lowbit_f64_T = _U_CSA3to2_io_out_sum + _U_CSA3to2_io_out_car;
			_fp_c_rshift_result_high_inv_add1_T_9 = (is_fp64_reg0 ? fp_c_rshiftValue_inv_reg[161:106] : (is_fp32_reg0 ? {29'h00000000, fp_c_rshiftValue_inv_reg[74:48]} : {42'h00000000000, fp_c_rshiftValue_inv_reg[35:22]})) + 56'h00000000000001;
			_adder_f64_T_2 = (_adder_lowbit_f64_T[106] ? _fp_c_rshift_result_high_inv_add1_T_9 : fp_c_rshiftValue_inv_reg[161:106]);
			_adder_f16_T_4 = {rshift_guard_reg, rshift_round_reg, rshift_sticky_reg};
			_adder_f16_T_9 = {rshift_guard_reg, rshift_round_reg};
			_adder_f64_T_6 = ~_adder_f16_T_4 + 3'h1;
			_adder_f64_T_10 = (is_sub_f64_reg0 ? _adder_f64_T_6[2:1] : _adder_f16_T_9);
			adder_f64 = {_adder_f64_T_2, _adder_lowbit_f64_T[105:0], _adder_f64_T_10};
			_adder_f32_T_2 = (_adder_lowbit_f64_T[48] ? _fp_c_rshift_result_high_inv_add1_T_9[26:0] : fp_c_rshiftValue_inv_reg[74:48]);
			_adder_f32_T_6 = ~_adder_f16_T_4 + 3'h1;
			_adder_f32_T_10 = (adder_f32_r ? _adder_f32_T_6[2:1] : _adder_f16_T_9);
			adder_f32 = {_adder_f32_T_2, _adder_lowbit_f64_T[47:0], _adder_f32_T_10};
			_adder_f16_T_2 = (_adder_lowbit_f64_T[22] ? _fp_c_rshift_result_high_inv_add1_T_9[13:0] : fp_c_rshiftValue_inv_reg[35:22]);
			_adder_f16_T_6 = ~_adder_f16_T_4 + 3'h1;
			_adder_f16_T_10 = (adder_f16_r ? _adder_f16_T_6[2:1] : _adder_f16_T_9);
			adder_f16 = {_adder_f16_T_2, _adder_lowbit_f64_T[21:0], _adder_f16_T_10};
			lshift_value_mask_f64 = (|lshift_value_max_reg0[11:8] ? 163'h00000000000000000000000000000000000000000 : 163'h7ffffffffffffffffffffffffffffffffffffffff >> lshift_value_max_reg0[7:0]);
			lshift_value_mask_f32 = (|lshift_value_max_reg0[8:7] ? 76'h0000000000000000000 : 76'hfffffffffffffffffff >> lshift_value_max_reg0[6:0]);
			lshift_value_mask_f16 = 37'h1fffffffff >> lshift_value_max_reg0[5:0];
			_tzd_adder_f64_reg_d_T_10 = {32'h00000000, _adder_lowbit_f64_T[61:30]} | {_adder_lowbit_f64_T[29:0], _adder_f64_T_10, 32'h00000000};
			_tzd_adder_f64_reg_d_T_20 = {16'h0000, _tzd_adder_f64_reg_d_T_10[63:16] & 48'hffff0000ffff} | {_tzd_adder_f64_reg_d_T_10[47:0] & 48'hffff0000ffff, 16'h0000};
			_tzd_adder_f64_reg_d_T_30 = {8'h00, _tzd_adder_f64_reg_d_T_20[63:8] & 56'hff00ff00ff00ff} | {_tzd_adder_f64_reg_d_T_20[55:0] & 56'hff00ff00ff00ff, 8'h00};
			_tzd_adder_f64_reg_d_T_40 = {4'h0, _tzd_adder_f64_reg_d_T_30[63:4] & 60'hf0f0f0f0f0f0f0f} | {_tzd_adder_f64_reg_d_T_30[59:0] & 60'hf0f0f0f0f0f0f0f, 4'h0};
			_tzd_adder_f64_reg_d_T_50 = {2'h0, _tzd_adder_f64_reg_d_T_40[63:2] & 62'h3333333333333333} | {_tzd_adder_f64_reg_d_T_40[61:0] & 62'h3333333333333333, 2'h0};
			_tzd_adder_f64_reg_d_T_70 = {32'h00000000, _adder_f64_T_2[19:0], _adder_lowbit_f64_T[105:94]} | {_adder_lowbit_f64_T[93:62], 32'h00000000};
			_tzd_adder_f64_reg_d_T_80 = {16'h0000, _tzd_adder_f64_reg_d_T_70[63:16] & 48'hffff0000ffff} | {_tzd_adder_f64_reg_d_T_70[47:0] & 48'hffff0000ffff, 16'h0000};
			_tzd_adder_f64_reg_d_T_90 = {8'h00, _tzd_adder_f64_reg_d_T_80[63:8] & 56'hff00ff00ff00ff} | {_tzd_adder_f64_reg_d_T_80[55:0] & 56'hff00ff00ff00ff, 8'h00};
			_tzd_adder_f64_reg_d_T_100 = {4'h0, _tzd_adder_f64_reg_d_T_90[63:4] & 60'hf0f0f0f0f0f0f0f} | {_tzd_adder_f64_reg_d_T_90[59:0] & 60'hf0f0f0f0f0f0f0f, 4'h0};
			_tzd_adder_f64_reg_d_T_110 = {2'h0, _tzd_adder_f64_reg_d_T_100[63:2] & 62'h3333333333333333} | {_tzd_adder_f64_reg_d_T_100[61:0] & 62'h3333333333333333, 2'h0};
			_tzd_adder_f64_reg_d_T_132 = {16'h0000, _adder_f64_T_2[51:36]} | {_adder_f64_T_2[35:20], 16'h0000};
			_tzd_adder_f64_reg_d_T_142 = {8'h00, _tzd_adder_f64_reg_d_T_132[31:8] & 24'hff00ff} | {_tzd_adder_f64_reg_d_T_132[23:0] & 24'hff00ff, 8'h00};
			_tzd_adder_f64_reg_d_T_152 = {4'h0, _tzd_adder_f64_reg_d_T_142[31:4] & 28'hf0f0f0f} | {_tzd_adder_f64_reg_d_T_142[27:0] & 28'hf0f0f0f, 4'h0};
			_tzd_adder_f64_reg_d_T_162 = {2'h0, _tzd_adder_f64_reg_d_T_152[31:2] & 30'h33333333} | {_tzd_adder_f64_reg_d_T_152[29:0] & 30'h33333333, 2'h0};
			_tzd_adder_f32_reg_d_T_9 = {32'h00000000, _adder_f32_T_2[13:0], _adder_lowbit_f64_T[47:30]} | {_adder_lowbit_f64_T[29:0], _adder_f32_T_10, 32'h00000000};
			_tzd_adder_f32_reg_d_T_19 = {16'h0000, _tzd_adder_f32_reg_d_T_9[63:16] & 48'hffff0000ffff} | {_tzd_adder_f32_reg_d_T_9[47:0] & 48'hffff0000ffff, 16'h0000};
			_tzd_adder_f32_reg_d_T_29 = {8'h00, _tzd_adder_f32_reg_d_T_19[63:8] & 56'hff00ff00ff00ff} | {_tzd_adder_f32_reg_d_T_19[55:0] & 56'hff00ff00ff00ff, 8'h00};
			_tzd_adder_f32_reg_d_T_39 = {4'h0, _tzd_adder_f32_reg_d_T_29[63:4] & 60'hf0f0f0f0f0f0f0f} | {_tzd_adder_f32_reg_d_T_29[59:0] & 60'hf0f0f0f0f0f0f0f, 4'h0};
			_tzd_adder_f32_reg_d_T_49 = {2'h0, _tzd_adder_f32_reg_d_T_39[63:2] & 62'h3333333333333333} | {_tzd_adder_f32_reg_d_T_39[61:0] & 62'h3333333333333333, 2'h0};
			_tzd_adder_f32_reg_d_T_70 = {4'h0, _adder_f32_T_2[21:18]} | {_adder_f32_T_2[17:14], 4'h0};
			_tzd_adder_f32_reg_d_T_80 = {2'h0, _tzd_adder_f32_reg_d_T_70[7:2] & 6'h33} | {_tzd_adder_f32_reg_d_T_70[5:0] & 6'h33, 2'h0};
			_tzd_adder_f16_reg_d_T_9 = {16'h0000, _adder_f16_T_2[7:0], _adder_lowbit_f64_T[21:14]} | {_adder_lowbit_f64_T[13:0], _adder_f16_T_10, 16'h0000};
			_tzd_adder_f16_reg_d_T_19 = {8'h00, _tzd_adder_f16_reg_d_T_9[31:8] & 24'hff00ff} | {_tzd_adder_f16_reg_d_T_9[23:0] & 24'hff00ff, 8'h00};
			_tzd_adder_f16_reg_d_T_29 = {4'h0, _tzd_adder_f16_reg_d_T_19[31:4] & 28'hf0f0f0f} | {_tzd_adder_f16_reg_d_T_19[27:0] & 28'hf0f0f0f, 4'h0};
			_tzd_adder_f16_reg_d_T_39 = {2'h0, _tzd_adder_f16_reg_d_T_29[31:2] & 30'h33333333} | {_tzd_adder_f16_reg_d_T_29[29:0] & 30'h33333333, 2'h0};
			lzd_adder_inv_mask_f64_reg_d = ({163 {_adder_f64_T_2[55]}} ^ {_adder_f64_T_2[54:0], _adder_lowbit_f64_T[105:0], _adder_f64_T_10}) | lshift_value_mask_f64;
			lzd_adder_inv_mask_f32_reg_d = ({76 {_adder_f32_T_2[26]}} ^ {_adder_f32_T_2[25:0], _adder_lowbit_f64_T[47:0], _adder_f32_T_10}) | lshift_value_mask_f32;
			lzd_adder_inv_mask_f16_reg_d = ({37 {_adder_f16_T_2[13]}} ^ {_adder_f16_T_2[12:0], _adder_lowbit_f64_T[21:0], _adder_f16_T_10}) | lshift_value_mask_f16;
			is_fp64_reg1 <= is_fp64_reg0;
			is_fp32_reg1 <= is_fp32_reg0;
			adder_is_negative_reg1 <= (is_fp64_reg0 ? _adder_f64_T_2[55] : (is_fp32_reg0 ? _adder_f32_T_2[26] : _adder_f16_T_2[13]));
			E_greater_reg2_r_1 <= E_greater_reg2_r;
			tzd_adder_reg1 <= (is_fp64_reg0 ? {{1'h0, _tzd_adder_f64_reg_d_T_50[63:1] & 63'h5555555555555555} | {_tzd_adder_f64_reg_d_T_50[62:0] & 63'h5555555555555555, 1'h0}, {1'h0, _tzd_adder_f64_reg_d_T_110[63:1] & 63'h5555555555555555} | {_tzd_adder_f64_reg_d_T_110[62:0] & 63'h5555555555555555, 1'h0}, {1'h0, _tzd_adder_f64_reg_d_T_162[31:1] & 31'h55555555} | {_tzd_adder_f64_reg_d_T_162[30:0] & 31'h55555555, 1'h0}, _adder_f64_T_2[52], _adder_f64_T_2[53], _adder_f64_T_2[54], _adder_f64_T_2[55]} : {87'h0000000000000000000000, (is_fp32_reg0 ? {{1'h0, _tzd_adder_f32_reg_d_T_49[63:1] & 63'h5555555555555555} | {_tzd_adder_f32_reg_d_T_49[62:0] & 63'h5555555555555555, 1'h0}, {1'h0, _tzd_adder_f32_reg_d_T_80[7:1] & 7'h55} | {_tzd_adder_f32_reg_d_T_80[6:0] & 7'h55, 1'h0}, _adder_f32_T_2[22], _adder_f32_T_2[23], _adder_f32_T_2[24], _adder_f32_T_2[25], _adder_f32_T_2[26]} : {39'h0000000000, {1'h0, _tzd_adder_f16_reg_d_T_39[31:1] & 31'h55555555} | {_tzd_adder_f16_reg_d_T_39[30:0] & 31'h55555555, 1'h0}, _adder_f16_T_2[8], _adder_f16_T_2[9], _adder_f16_T_2[10], _adder_f16_T_2[11], _adder_f16_T_2[12], _adder_f16_T_2[13]})});
			lzd_adder_inv_mask_reg1 <= (is_fp64_reg0 ? lzd_adder_inv_mask_f64_reg_d : {87'h0000000000000000000000, (is_fp32_reg0 ? lzd_adder_inv_mask_f32_reg_d : {39'h0000000000, lzd_adder_inv_mask_f16_reg_d})});
			lshift_mask_valid_reg <= (is_fp64_reg0 ? lzd_adder_inv_mask_f64_reg_d == lshift_value_mask_f64 : (is_fp32_reg0 ? lzd_adder_inv_mask_f32_reg_d == lshift_value_mask_f32 : lzd_adder_inv_mask_f16_reg_d == lshift_value_mask_f16));
			adder_f64_reg1 <= adder_f64;
			adder_f32_reg1 <= adder_f32;
			adder_f16_reg1 <= adder_f16;
			sign_result_temp_f64_reg2_r_2 <= (_adder_f64_T_2[55] ? sign_result_temp_f64_reg2_r : sign_result_temp_f64_reg2_r_1);
			sign_result_temp_f32_reg2_r_2 <= (_adder_f32_T_2[26] ? sign_result_temp_f32_reg2_r : sign_result_temp_f32_reg2_r_1);
			sign_result_temp_f16_reg2_r_2 <= (_adder_f16_T_2[13] ? sign_result_temp_f16_reg2_r : sign_result_temp_f16_reg2_r_1);
			RNE_reg2_r_1 <= RNE_reg2_r;
			RTZ_reg2_r_1 <= RTZ_reg2_r;
			RDN_reg2_r_1 <= RDN_reg2_r;
			RUP_reg2_r_1 <= RUP_reg2_r;
			RMM_reg2_r_1 <= RMM_reg2_r;
			sticky_f64_reg2_r <= rshift_sticky_reg;
			sticky_f32_reg2_r <= rshift_sticky_reg;
			sticky_f16_reg2_r <= rshift_sticky_reg;
			sticky_uf_f64_reg2_r <= rshift_sticky_reg;
			sticky_uf_f32_reg2_r <= rshift_sticky_reg;
			sticky_uf_f16_reg2_r <= rshift_sticky_reg;
			normal_result_is_zero_f64_reg2_r <= adder_f64 == 164'h00000000000000000000000000000000000000000;
			normal_result_is_zero_f32_reg2_r <= adder_f32 == 77'h00000000000000000000;
			normal_result_is_zero_f16_reg2_r <= adder_f16 == 38'h0000000000;
			has_zero_f64_reg2_r_1 <= has_zero_f64_reg2_r;
			has_zero_f32_reg2_r_1 <= has_zero_f32_reg2_r;
			has_zero_f16_reg2_r_1 <= has_zero_f16_reg2_r;
			fp_result_fp_a_or_b_is_zero_reg_r_1 <= fp_result_fp_a_or_b_is_zero_reg_r;
			has_nan_f64_reg2_r_1 <= has_nan_f64_reg2_r;
			has_nan_f64_is_NV_reg2_r_1 <= has_nan_f64_is_NV_reg2_r;
			has_inf_f64_reg2_r_1 <= has_inf_f64_reg2_r;
			has_inf_f64_is_NV_reg2_r_1 <= has_inf_f64_is_NV_reg2_r;
			has_inf_f64_result_inf_sign_reg2_r_1 <= has_inf_f64_result_inf_sign_reg2_r;
			fp_a_or_b_is_zero_f64_reg2_r_1 <= fp_a_or_b_is_zero_f64_reg2_r;
			has_nan_f32_reg2_r_1 <= has_nan_f32_reg2_r;
			has_nan_f32_is_NV_reg2_r_1 <= has_nan_f32_is_NV_reg2_r;
			has_inf_f32_reg2_r_1 <= has_inf_f32_reg2_r;
			has_inf_f32_is_NV_reg2_r_1 <= has_inf_f32_is_NV_reg2_r;
			has_inf_f32_result_inf_sign_reg2_r_1 <= has_inf_f32_result_inf_sign_reg2_r;
			fp_a_or_b_is_zero_f32_reg2_r_1 <= fp_a_or_b_is_zero_f32_reg2_r;
			has_nan_f16_reg2_r_1 <= has_nan_f16_reg2_r;
			has_nan_f16_is_NV_reg2_r_1 <= has_nan_f16_is_NV_reg2_r;
			has_inf_f16_reg2_r_1 <= has_inf_f16_reg2_r;
			has_inf_f16_is_NV_reg2_r_1 <= has_inf_f16_is_NV_reg2_r;
			has_inf_f16_result_inf_sign_reg2_r_1 <= has_inf_f16_result_inf_sign_reg2_r;
			fp_a_or_b_is_zero_f16_reg2_r_1 <= fp_a_or_b_is_zero_f16_reg2_r;
		end
		if (fire_reg1_last_r) begin : sv2v_autoblock_3
			reg [63:0] _tzd_adder_f64_reg1_T_11;
			reg [63:0] _tzd_adder_f64_reg1_T_21;
			reg [63:0] _tzd_adder_f64_reg1_T_31;
			reg [63:0] _tzd_adder_f64_reg1_T_41;
			reg [63:0] _tzd_adder_f64_reg1_T_51;
			reg [62:0] _tzd_adder_f64_reg1_T_61;
			reg [63:0] _tzd_adder_f64_reg1_T_71;
			reg [63:0] _tzd_adder_f64_reg1_T_81;
			reg [63:0] _tzd_adder_f64_reg1_T_91;
			reg [63:0] _tzd_adder_f64_reg1_T_101;
			reg [63:0] _tzd_adder_f64_reg1_T_111;
			reg [63:0] _tzd_adder_f64_reg1_T_121;
			reg [31:0] _tzd_adder_f64_reg1_T_133;
			reg [31:0] _tzd_adder_f64_reg1_T_143;
			reg [31:0] _tzd_adder_f64_reg1_T_153;
			reg [31:0] _tzd_adder_f64_reg1_T_163;
			reg [31:0] _tzd_adder_f64_reg1_T_173;
			reg [63:0] _tzd_adder_f32_reg1_T_11;
			reg [63:0] _tzd_adder_f32_reg1_T_21;
			reg [63:0] _tzd_adder_f32_reg1_T_31;
			reg [63:0] _tzd_adder_f32_reg1_T_41;
			reg [63:0] _tzd_adder_f32_reg1_T_51;
			reg [62:0] _tzd_adder_f32_reg1_T_61;
			reg [7:0] _tzd_adder_f32_reg1_T_72;
			reg [7:0] _tzd_adder_f32_reg1_T_82;
			reg [7:0] _tzd_adder_f32_reg1_T_92;
			reg [31:0] _tzd_adder_f16_reg1_T_11;
			reg [31:0] _tzd_adder_f16_reg1_T_21;
			reg [31:0] _tzd_adder_f16_reg1_T_31;
			reg [31:0] _tzd_adder_f16_reg1_T_41;
			reg [30:0] _tzd_adder_f16_reg1_T_51;
			reg [63:0] _lzd_adder_inv_mask_f64_T_11;
			reg [63:0] _lzd_adder_inv_mask_f64_T_21;
			reg [63:0] _lzd_adder_inv_mask_f64_T_31;
			reg [63:0] _lzd_adder_inv_mask_f64_T_41;
			reg [63:0] _lzd_adder_inv_mask_f64_T_51;
			reg [62:0] _lzd_adder_inv_mask_f64_T_61;
			reg [63:0] _lzd_adder_inv_mask_f64_T_71;
			reg [63:0] _lzd_adder_inv_mask_f64_T_81;
			reg [63:0] _lzd_adder_inv_mask_f64_T_91;
			reg [63:0] _lzd_adder_inv_mask_f64_T_101;
			reg [63:0] _lzd_adder_inv_mask_f64_T_111;
			reg [63:0] _lzd_adder_inv_mask_f64_T_121;
			reg [31:0] _lzd_adder_inv_mask_f64_T_133;
			reg [31:0] _lzd_adder_inv_mask_f64_T_143;
			reg [31:0] _lzd_adder_inv_mask_f64_T_153;
			reg [31:0] _lzd_adder_inv_mask_f64_T_163;
			reg [31:0] _lzd_adder_inv_mask_f64_T_173;
			reg [7:0] lzd_adder_inv_mask_f64;
			reg [63:0] _lzd_adder_inv_mask_f32_T_11;
			reg [63:0] _lzd_adder_inv_mask_f32_T_21;
			reg [63:0] _lzd_adder_inv_mask_f32_T_31;
			reg [63:0] _lzd_adder_inv_mask_f32_T_41;
			reg [63:0] _lzd_adder_inv_mask_f32_T_51;
			reg [62:0] _lzd_adder_inv_mask_f32_T_61;
			reg [7:0] _lzd_adder_inv_mask_f32_T_72;
			reg [7:0] _lzd_adder_inv_mask_f32_T_82;
			reg [7:0] _lzd_adder_inv_mask_f32_T_92;
			reg [6:0] lzd_adder_inv_mask_f32;
			reg [31:0] _lzd_adder_inv_mask_f16_T_11;
			reg [31:0] _lzd_adder_inv_mask_f16_T_21;
			reg [31:0] _lzd_adder_inv_mask_f16_T_31;
			reg [31:0] _lzd_adder_inv_mask_f16_T_41;
			reg [30:0] _lzd_adder_inv_mask_f16_T_51;
			reg [5:0] lzd_adder_inv_mask_f16;
			reg [163:0] _lshift_adder_f64_res_vec_1_T_2;
			reg [119:0] _lshift_adder_f64_res_vec_2_T_2;
			reg [87:0] _lshift_adder_f64_res_vec_3_T_2;
			reg [71:0] _lshift_adder_f64_res_vec_4_T_2;
			reg [63:0] _lshift_adder_f64_res_vec_5_T_2;
			reg [59:0] _lshift_adder_f64_res_vec_6_T_2;
			reg [57:0] _lshift_adder_f64_res_vec_7_T_2;
			reg [76:0] _lshift_adder_f32_res_vec_1_T_2;
			reg [58:0] _lshift_adder_f32_res_vec_2_T_2;
			reg [42:0] _lshift_adder_f32_res_vec_3_T_2;
			reg [34:0] _lshift_adder_f32_res_vec_4_T_2;
			reg [30:0] _lshift_adder_f32_res_vec_5_T_2;
			reg [28:0] _lshift_adder_f32_res_vec_6_T_2;
			reg [37:0] _lshift_adder_f16_res_vec_1_T_2;
			reg [29:0] _lshift_adder_f16_res_vec_2_T_2;
			reg [21:0] _lshift_adder_f16_res_vec_3_T_2;
			reg [17:0] _lshift_adder_f16_res_vec_4_T_2;
			reg [15:0] _lshift_adder_f16_res_vec_5_T_2;
			reg [56:0] _lshift_adder_inv_f64_T_3;
			reg [27:0] _lshift_adder_inv_f32_T_3;
			reg [14:0] _lshift_adder_inv_f16_T_3;
			reg [7:0] _sticky_uf_f64_reg2_T;
			reg is_fix_f64;
			reg [6:0] _sticky_uf_f32_reg2_T;
			reg is_fix_f32;
			reg [5:0] _sticky_uf_f16_reg2_T;
			reg is_fix_f16;
			reg [55:0] lshift_adder_inv_fix_f64;
			reg [26:0] lshift_adder_inv_fix_f32;
			reg [13:0] lshift_adder_inv_fix_f16;
			_tzd_adder_f64_reg1_T_11 = {32'h00000000, tzd_adder_reg1[62:31]} | {tzd_adder_reg1[30:0], 33'h100000000};
			_tzd_adder_f64_reg1_T_21 = {16'h0000, _tzd_adder_f64_reg1_T_11[63:16] & 48'hffff0000ffff} | {_tzd_adder_f64_reg1_T_11[47:0] & 48'hffff0000ffff, 16'h0000};
			_tzd_adder_f64_reg1_T_31 = {8'h00, _tzd_adder_f64_reg1_T_21[63:8] & 56'hff00ff00ff00ff} | {_tzd_adder_f64_reg1_T_21[55:0] & 56'hff00ff00ff00ff, 8'h00};
			_tzd_adder_f64_reg1_T_41 = {4'h0, _tzd_adder_f64_reg1_T_31[63:4] & 60'hf0f0f0f0f0f0f0f} | {_tzd_adder_f64_reg1_T_31[59:0] & 60'hf0f0f0f0f0f0f0f, 4'h0};
			_tzd_adder_f64_reg1_T_51 = {2'h0, _tzd_adder_f64_reg1_T_41[63:2] & 62'h3333333333333333} | {_tzd_adder_f64_reg1_T_41[61:0] & 62'h3333333333333333, 2'h0};
			_tzd_adder_f64_reg1_T_61 = (_tzd_adder_f64_reg1_T_51[63:1] & 63'h5555555555555555) | {1'h0, _tzd_adder_f64_reg1_T_51[60:0] & 61'h1555555555555555, 1'h0};
			_tzd_adder_f64_reg1_T_71 = {32'h00000000, tzd_adder_reg1[126:95]} | {tzd_adder_reg1[94:63], 32'h00000000};
			_tzd_adder_f64_reg1_T_81 = {16'h0000, _tzd_adder_f64_reg1_T_71[63:16] & 48'hffff0000ffff} | {_tzd_adder_f64_reg1_T_71[47:0] & 48'hffff0000ffff, 16'h0000};
			_tzd_adder_f64_reg1_T_91 = {8'h00, _tzd_adder_f64_reg1_T_81[63:8] & 56'hff00ff00ff00ff} | {_tzd_adder_f64_reg1_T_81[55:0] & 56'hff00ff00ff00ff, 8'h00};
			_tzd_adder_f64_reg1_T_101 = {4'h0, _tzd_adder_f64_reg1_T_91[63:4] & 60'hf0f0f0f0f0f0f0f} | {_tzd_adder_f64_reg1_T_91[59:0] & 60'hf0f0f0f0f0f0f0f, 4'h0};
			_tzd_adder_f64_reg1_T_111 = {2'h0, _tzd_adder_f64_reg1_T_101[63:2] & 62'h3333333333333333} | {_tzd_adder_f64_reg1_T_101[61:0] & 62'h3333333333333333, 2'h0};
			_tzd_adder_f64_reg1_T_121 = {1'h0, _tzd_adder_f64_reg1_T_111[63:1] & 63'h5555555555555555} | {_tzd_adder_f64_reg1_T_111[62:0] & 63'h5555555555555555, 1'h0};
			_tzd_adder_f64_reg1_T_133 = {16'h0000, tzd_adder_reg1[158:143]} | {tzd_adder_reg1[142:127], 16'h0000};
			_tzd_adder_f64_reg1_T_143 = {8'h00, _tzd_adder_f64_reg1_T_133[31:8] & 24'hff00ff} | {_tzd_adder_f64_reg1_T_133[23:0] & 24'hff00ff, 8'h00};
			_tzd_adder_f64_reg1_T_153 = {4'h0, _tzd_adder_f64_reg1_T_143[31:4] & 28'hf0f0f0f} | {_tzd_adder_f64_reg1_T_143[27:0] & 28'hf0f0f0f, 4'h0};
			_tzd_adder_f64_reg1_T_163 = {2'h0, _tzd_adder_f64_reg1_T_153[31:2] & 30'h33333333} | {_tzd_adder_f64_reg1_T_153[29:0] & 30'h33333333, 2'h0};
			_tzd_adder_f64_reg1_T_173 = {1'h0, _tzd_adder_f64_reg1_T_163[31:1] & 31'h55555555} | {_tzd_adder_f64_reg1_T_163[30:0] & 31'h55555555, 1'h0};
			_tzd_adder_f32_reg1_T_11 = {32'h00000000, tzd_adder_reg1[62:31]} | {tzd_adder_reg1[30:0], 33'h100000000};
			_tzd_adder_f32_reg1_T_21 = {16'h0000, _tzd_adder_f32_reg1_T_11[63:16] & 48'hffff0000ffff} | {_tzd_adder_f32_reg1_T_11[47:0] & 48'hffff0000ffff, 16'h0000};
			_tzd_adder_f32_reg1_T_31 = {8'h00, _tzd_adder_f32_reg1_T_21[63:8] & 56'hff00ff00ff00ff} | {_tzd_adder_f32_reg1_T_21[55:0] & 56'hff00ff00ff00ff, 8'h00};
			_tzd_adder_f32_reg1_T_41 = {4'h0, _tzd_adder_f32_reg1_T_31[63:4] & 60'hf0f0f0f0f0f0f0f} | {_tzd_adder_f32_reg1_T_31[59:0] & 60'hf0f0f0f0f0f0f0f, 4'h0};
			_tzd_adder_f32_reg1_T_51 = {2'h0, _tzd_adder_f32_reg1_T_41[63:2] & 62'h3333333333333333} | {_tzd_adder_f32_reg1_T_41[61:0] & 62'h3333333333333333, 2'h0};
			_tzd_adder_f32_reg1_T_61 = (_tzd_adder_f32_reg1_T_51[63:1] & 63'h5555555555555555) | {1'h0, _tzd_adder_f32_reg1_T_51[60:0] & 61'h1555555555555555, 1'h0};
			_tzd_adder_f32_reg1_T_72 = {4'h0, tzd_adder_reg1[70:67]} | {tzd_adder_reg1[66:63], 4'h0};
			_tzd_adder_f32_reg1_T_82 = {2'h0, _tzd_adder_f32_reg1_T_72[7:2] & 6'h33} | {_tzd_adder_f32_reg1_T_72[5:0] & 6'h33, 2'h0};
			_tzd_adder_f32_reg1_T_92 = {1'h0, _tzd_adder_f32_reg1_T_82[7:1] & 7'h55} | {_tzd_adder_f32_reg1_T_82[6:0] & 7'h55, 1'h0};
			_tzd_adder_f16_reg1_T_11 = {16'h0000, tzd_adder_reg1[30:15]} | {tzd_adder_reg1[14:0], 17'h10000};
			_tzd_adder_f16_reg1_T_21 = {8'h00, _tzd_adder_f16_reg1_T_11[31:8] & 24'hff00ff} | {_tzd_adder_f16_reg1_T_11[23:0] & 24'hff00ff, 8'h00};
			_tzd_adder_f16_reg1_T_31 = {4'h0, _tzd_adder_f16_reg1_T_21[31:4] & 28'hf0f0f0f} | {_tzd_adder_f16_reg1_T_21[27:0] & 28'hf0f0f0f, 4'h0};
			_tzd_adder_f16_reg1_T_41 = {2'h0, _tzd_adder_f16_reg1_T_31[31:2] & 30'h33333333} | {_tzd_adder_f16_reg1_T_31[29:0] & 30'h33333333, 2'h0};
			_tzd_adder_f16_reg1_T_51 = (_tzd_adder_f16_reg1_T_41[31:1] & 31'h55555555) | {1'h0, _tzd_adder_f16_reg1_T_41[28:0] & 29'h15555555, 1'h0};
			_lzd_adder_inv_mask_f64_T_11 = {32'h00000000, lzd_adder_inv_mask_reg1[62:31]} | {lzd_adder_inv_mask_reg1[30:0], 33'h100000000};
			_lzd_adder_inv_mask_f64_T_21 = {16'h0000, _lzd_adder_inv_mask_f64_T_11[63:16] & 48'hffff0000ffff} | {_lzd_adder_inv_mask_f64_T_11[47:0] & 48'hffff0000ffff, 16'h0000};
			_lzd_adder_inv_mask_f64_T_31 = {8'h00, _lzd_adder_inv_mask_f64_T_21[63:8] & 56'hff00ff00ff00ff} | {_lzd_adder_inv_mask_f64_T_21[55:0] & 56'hff00ff00ff00ff, 8'h00};
			_lzd_adder_inv_mask_f64_T_41 = {4'h0, _lzd_adder_inv_mask_f64_T_31[63:4] & 60'hf0f0f0f0f0f0f0f} | {_lzd_adder_inv_mask_f64_T_31[59:0] & 60'hf0f0f0f0f0f0f0f, 4'h0};
			_lzd_adder_inv_mask_f64_T_51 = {2'h0, _lzd_adder_inv_mask_f64_T_41[63:2] & 62'h3333333333333333} | {_lzd_adder_inv_mask_f64_T_41[61:0] & 62'h3333333333333333, 2'h0};
			_lzd_adder_inv_mask_f64_T_61 = (_lzd_adder_inv_mask_f64_T_51[63:1] & 63'h5555555555555555) | {1'h0, _lzd_adder_inv_mask_f64_T_51[60:0] & 61'h1555555555555555, 1'h0};
			_lzd_adder_inv_mask_f64_T_71 = {32'h00000000, lzd_adder_inv_mask_reg1[126:95]} | {lzd_adder_inv_mask_reg1[94:63], 32'h00000000};
			_lzd_adder_inv_mask_f64_T_81 = {16'h0000, _lzd_adder_inv_mask_f64_T_71[63:16] & 48'hffff0000ffff} | {_lzd_adder_inv_mask_f64_T_71[47:0] & 48'hffff0000ffff, 16'h0000};
			_lzd_adder_inv_mask_f64_T_91 = {8'h00, _lzd_adder_inv_mask_f64_T_81[63:8] & 56'hff00ff00ff00ff} | {_lzd_adder_inv_mask_f64_T_81[55:0] & 56'hff00ff00ff00ff, 8'h00};
			_lzd_adder_inv_mask_f64_T_101 = {4'h0, _lzd_adder_inv_mask_f64_T_91[63:4] & 60'hf0f0f0f0f0f0f0f} | {_lzd_adder_inv_mask_f64_T_91[59:0] & 60'hf0f0f0f0f0f0f0f, 4'h0};
			_lzd_adder_inv_mask_f64_T_111 = {2'h0, _lzd_adder_inv_mask_f64_T_101[63:2] & 62'h3333333333333333} | {_lzd_adder_inv_mask_f64_T_101[61:0] & 62'h3333333333333333, 2'h0};
			_lzd_adder_inv_mask_f64_T_121 = {1'h0, _lzd_adder_inv_mask_f64_T_111[63:1] & 63'h5555555555555555} | {_lzd_adder_inv_mask_f64_T_111[62:0] & 63'h5555555555555555, 1'h0};
			_lzd_adder_inv_mask_f64_T_133 = {16'h0000, lzd_adder_inv_mask_reg1[158:143]} | {lzd_adder_inv_mask_reg1[142:127], 16'h0000};
			_lzd_adder_inv_mask_f64_T_143 = {8'h00, _lzd_adder_inv_mask_f64_T_133[31:8] & 24'hff00ff} | {_lzd_adder_inv_mask_f64_T_133[23:0] & 24'hff00ff, 8'h00};
			_lzd_adder_inv_mask_f64_T_153 = {4'h0, _lzd_adder_inv_mask_f64_T_143[31:4] & 28'hf0f0f0f} | {_lzd_adder_inv_mask_f64_T_143[27:0] & 28'hf0f0f0f, 4'h0};
			_lzd_adder_inv_mask_f64_T_163 = {2'h0, _lzd_adder_inv_mask_f64_T_153[31:2] & 30'h33333333} | {_lzd_adder_inv_mask_f64_T_153[29:0] & 30'h33333333, 2'h0};
			_lzd_adder_inv_mask_f64_T_173 = {1'h0, _lzd_adder_inv_mask_f64_T_163[31:1] & 31'h55555555} | {_lzd_adder_inv_mask_f64_T_163[30:0] & 31'h55555555, 1'h0};
			lzd_adder_inv_mask_f64 = (lzd_adder_inv_mask_reg1[162] ? 8'h00 : (lzd_adder_inv_mask_reg1[161] ? 8'h01 : (lzd_adder_inv_mask_reg1[160] ? 8'h02 : (lzd_adder_inv_mask_reg1[159] ? 8'h03 : (_lzd_adder_inv_mask_f64_T_173[0] ? 8'h04 : (_lzd_adder_inv_mask_f64_T_173[1] ? 8'h05 : (_lzd_adder_inv_mask_f64_T_173[2] ? 8'h06 : (_lzd_adder_inv_mask_f64_T_173[3] ? 8'h07 : (_lzd_adder_inv_mask_f64_T_173[4] ? 8'h08 : (_lzd_adder_inv_mask_f64_T_173[5] ? 8'h09 : (_lzd_adder_inv_mask_f64_T_173[6] ? 8'h0a : (_lzd_adder_inv_mask_f64_T_173[7] ? 8'h0b : (_lzd_adder_inv_mask_f64_T_173[8] ? 8'h0c : (_lzd_adder_inv_mask_f64_T_173[9] ? 8'h0d : (_lzd_adder_inv_mask_f64_T_173[10] ? 8'h0e : (_lzd_adder_inv_mask_f64_T_173[11] ? 8'h0f : (_lzd_adder_inv_mask_f64_T_173[12] ? 8'h10 : (_lzd_adder_inv_mask_f64_T_173[13] ? 8'h11 : (_lzd_adder_inv_mask_f64_T_173[14] ? 8'h12 : (_lzd_adder_inv_mask_f64_T_173[15] ? 8'h13 : (_lzd_adder_inv_mask_f64_T_173[16] ? 8'h14 : (_lzd_adder_inv_mask_f64_T_173[17] ? 8'h15 : (_lzd_adder_inv_mask_f64_T_173[18] ? 8'h16 : (_lzd_adder_inv_mask_f64_T_173[19] ? 8'h17 : (_lzd_adder_inv_mask_f64_T_173[20] ? 8'h18 : (_lzd_adder_inv_mask_f64_T_173[21] ? 8'h19 : (_lzd_adder_inv_mask_f64_T_173[22] ? 8'h1a : (_lzd_adder_inv_mask_f64_T_173[23] ? 8'h1b : (_lzd_adder_inv_mask_f64_T_173[24] ? 8'h1c : (_lzd_adder_inv_mask_f64_T_173[25] ? 8'h1d : (_lzd_adder_inv_mask_f64_T_173[26] ? 8'h1e : (_lzd_adder_inv_mask_f64_T_173[27] ? 8'h1f : (_lzd_adder_inv_mask_f64_T_173[28] ? 8'h20 : (_lzd_adder_inv_mask_f64_T_173[29] ? 8'h21 : (_lzd_adder_inv_mask_f64_T_173[30] ? 8'h22 : (_lzd_adder_inv_mask_f64_T_173[31] ? 8'h23 : (_lzd_adder_inv_mask_f64_T_121[0] ? 8'h24 : (_lzd_adder_inv_mask_f64_T_121[1] ? 8'h25 : (_lzd_adder_inv_mask_f64_T_121[2] ? 8'h26 : (_lzd_adder_inv_mask_f64_T_121[3] ? 8'h27 : (_lzd_adder_inv_mask_f64_T_121[4] ? 8'h28 : (_lzd_adder_inv_mask_f64_T_121[5] ? 8'h29 : (_lzd_adder_inv_mask_f64_T_121[6] ? 8'h2a : (_lzd_adder_inv_mask_f64_T_121[7] ? 8'h2b : (_lzd_adder_inv_mask_f64_T_121[8] ? 8'h2c : (_lzd_adder_inv_mask_f64_T_121[9] ? 8'h2d : (_lzd_adder_inv_mask_f64_T_121[10] ? 8'h2e : (_lzd_adder_inv_mask_f64_T_121[11] ? 8'h2f : (_lzd_adder_inv_mask_f64_T_121[12] ? 8'h30 : (_lzd_adder_inv_mask_f64_T_121[13] ? 8'h31 : (_lzd_adder_inv_mask_f64_T_121[14] ? 8'h32 : (_lzd_adder_inv_mask_f64_T_121[15] ? 8'h33 : (_lzd_adder_inv_mask_f64_T_121[16] ? 8'h34 : (_lzd_adder_inv_mask_f64_T_121[17] ? 8'h35 : (_lzd_adder_inv_mask_f64_T_121[18] ? 8'h36 : (_lzd_adder_inv_mask_f64_T_121[19] ? 8'h37 : (_lzd_adder_inv_mask_f64_T_121[20] ? 8'h38 : (_lzd_adder_inv_mask_f64_T_121[21] ? 8'h39 : (_lzd_adder_inv_mask_f64_T_121[22] ? 8'h3a : (_lzd_adder_inv_mask_f64_T_121[23] ? 8'h3b : (_lzd_adder_inv_mask_f64_T_121[24] ? 8'h3c : (_lzd_adder_inv_mask_f64_T_121[25] ? 8'h3d : (_lzd_adder_inv_mask_f64_T_121[26] ? 8'h3e : (_lzd_adder_inv_mask_f64_T_121[27] ? 8'h3f : (_lzd_adder_inv_mask_f64_T_121[28] ? 8'h40 : (_lzd_adder_inv_mask_f64_T_121[29] ? 8'h41 : (_lzd_adder_inv_mask_f64_T_121[30] ? 8'h42 : (_lzd_adder_inv_mask_f64_T_121[31] ? 8'h43 : (_lzd_adder_inv_mask_f64_T_121[32] ? 8'h44 : (_lzd_adder_inv_mask_f64_T_121[33] ? 8'h45 : (_lzd_adder_inv_mask_f64_T_121[34] ? 8'h46 : (_lzd_adder_inv_mask_f64_T_121[35] ? 8'h47 : (_lzd_adder_inv_mask_f64_T_121[36] ? 8'h48 : (_lzd_adder_inv_mask_f64_T_121[37] ? 8'h49 : (_lzd_adder_inv_mask_f64_T_121[38] ? 8'h4a : (_lzd_adder_inv_mask_f64_T_121[39] ? 8'h4b : (_lzd_adder_inv_mask_f64_T_121[40] ? 8'h4c : (_lzd_adder_inv_mask_f64_T_121[41] ? 8'h4d : (_lzd_adder_inv_mask_f64_T_121[42] ? 8'h4e : (_lzd_adder_inv_mask_f64_T_121[43] ? 8'h4f : (_lzd_adder_inv_mask_f64_T_121[44] ? 8'h50 : (_lzd_adder_inv_mask_f64_T_121[45] ? 8'h51 : (_lzd_adder_inv_mask_f64_T_121[46] ? 8'h52 : (_lzd_adder_inv_mask_f64_T_121[47] ? 8'h53 : (_lzd_adder_inv_mask_f64_T_121[48] ? 8'h54 : (_lzd_adder_inv_mask_f64_T_121[49] ? 8'h55 : (_lzd_adder_inv_mask_f64_T_121[50] ? 8'h56 : (_lzd_adder_inv_mask_f64_T_121[51] ? 8'h57 : (_lzd_adder_inv_mask_f64_T_121[52] ? 8'h58 : (_lzd_adder_inv_mask_f64_T_121[53] ? 8'h59 : (_lzd_adder_inv_mask_f64_T_121[54] ? 8'h5a : (_lzd_adder_inv_mask_f64_T_121[55] ? 8'h5b : (_lzd_adder_inv_mask_f64_T_121[56] ? 8'h5c : (_lzd_adder_inv_mask_f64_T_121[57] ? 8'h5d : (_lzd_adder_inv_mask_f64_T_121[58] ? 8'h5e : (_lzd_adder_inv_mask_f64_T_121[59] ? 8'h5f : (_lzd_adder_inv_mask_f64_T_121[60] ? 8'h60 : (_lzd_adder_inv_mask_f64_T_121[61] ? 8'h61 : (_lzd_adder_inv_mask_f64_T_121[62] ? 8'h62 : (_lzd_adder_inv_mask_f64_T_121[63] ? 8'h63 : (_lzd_adder_inv_mask_f64_T_61[0] ? 8'h64 : (_lzd_adder_inv_mask_f64_T_61[1] ? 8'h65 : (_lzd_adder_inv_mask_f64_T_61[2] ? 8'h66 : (_lzd_adder_inv_mask_f64_T_61[3] ? 8'h67 : (_lzd_adder_inv_mask_f64_T_61[4] ? 8'h68 : (_lzd_adder_inv_mask_f64_T_61[5] ? 8'h69 : (_lzd_adder_inv_mask_f64_T_61[6] ? 8'h6a : (_lzd_adder_inv_mask_f64_T_61[7] ? 8'h6b : (_lzd_adder_inv_mask_f64_T_61[8] ? 8'h6c : (_lzd_adder_inv_mask_f64_T_61[9] ? 8'h6d : (_lzd_adder_inv_mask_f64_T_61[10] ? 8'h6e : (_lzd_adder_inv_mask_f64_T_61[11] ? 8'h6f : (_lzd_adder_inv_mask_f64_T_61[12] ? 8'h70 : (_lzd_adder_inv_mask_f64_T_61[13] ? 8'h71 : (_lzd_adder_inv_mask_f64_T_61[14] ? 8'h72 : (_lzd_adder_inv_mask_f64_T_61[15] ? 8'h73 : (_lzd_adder_inv_mask_f64_T_61[16] ? 8'h74 : (_lzd_adder_inv_mask_f64_T_61[17] ? 8'h75 : (_lzd_adder_inv_mask_f64_T_61[18] ? 8'h76 : (_lzd_adder_inv_mask_f64_T_61[19] ? 8'h77 : (_lzd_adder_inv_mask_f64_T_61[20] ? 8'h78 : (_lzd_adder_inv_mask_f64_T_61[21] ? 8'h79 : (_lzd_adder_inv_mask_f64_T_61[22] ? 8'h7a : (_lzd_adder_inv_mask_f64_T_61[23] ? 8'h7b : (_lzd_adder_inv_mask_f64_T_61[24] ? 8'h7c : (_lzd_adder_inv_mask_f64_T_61[25] ? 8'h7d : (_lzd_adder_inv_mask_f64_T_61[26] ? 8'h7e : (_lzd_adder_inv_mask_f64_T_61[27] ? 8'h7f : (_lzd_adder_inv_mask_f64_T_61[28] ? 8'h80 : (_lzd_adder_inv_mask_f64_T_61[29] ? 8'h81 : (_lzd_adder_inv_mask_f64_T_61[30] ? 8'h82 : (_lzd_adder_inv_mask_f64_T_61[31] ? 8'h83 : (_lzd_adder_inv_mask_f64_T_61[32] ? 8'h84 : (_lzd_adder_inv_mask_f64_T_61[33] ? 8'h85 : (_lzd_adder_inv_mask_f64_T_61[34] ? 8'h86 : (_lzd_adder_inv_mask_f64_T_61[35] ? 8'h87 : (_lzd_adder_inv_mask_f64_T_61[36] ? 8'h88 : (_lzd_adder_inv_mask_f64_T_61[37] ? 8'h89 : (_lzd_adder_inv_mask_f64_T_61[38] ? 8'h8a : (_lzd_adder_inv_mask_f64_T_61[39] ? 8'h8b : (_lzd_adder_inv_mask_f64_T_61[40] ? 8'h8c : (_lzd_adder_inv_mask_f64_T_61[41] ? 8'h8d : (_lzd_adder_inv_mask_f64_T_61[42] ? 8'h8e : (_lzd_adder_inv_mask_f64_T_61[43] ? 8'h8f : (_lzd_adder_inv_mask_f64_T_61[44] ? 8'h90 : (_lzd_adder_inv_mask_f64_T_61[45] ? 8'h91 : (_lzd_adder_inv_mask_f64_T_61[46] ? 8'h92 : (_lzd_adder_inv_mask_f64_T_61[47] ? 8'h93 : (_lzd_adder_inv_mask_f64_T_61[48] ? 8'h94 : (_lzd_adder_inv_mask_f64_T_61[49] ? 8'h95 : (_lzd_adder_inv_mask_f64_T_61[50] ? 8'h96 : (_lzd_adder_inv_mask_f64_T_61[51] ? 8'h97 : (_lzd_adder_inv_mask_f64_T_61[52] ? 8'h98 : (_lzd_adder_inv_mask_f64_T_61[53] ? 8'h99 : (_lzd_adder_inv_mask_f64_T_61[54] ? 8'h9a : (_lzd_adder_inv_mask_f64_T_61[55] ? 8'h9b : (_lzd_adder_inv_mask_f64_T_61[56] ? 8'h9c : (_lzd_adder_inv_mask_f64_T_61[57] ? 8'h9d : (_lzd_adder_inv_mask_f64_T_61[58] ? 8'h9e : (_lzd_adder_inv_mask_f64_T_61[59] ? 8'h9f : (_lzd_adder_inv_mask_f64_T_61[60] ? 8'ha0 : (_lzd_adder_inv_mask_f64_T_61[61] ? 8'ha1 : {7'h51, ~_lzd_adder_inv_mask_f64_T_61[62]}))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))));
			_lzd_adder_inv_mask_f32_T_11 = {32'h00000000, lzd_adder_inv_mask_reg1[62:31]} | {lzd_adder_inv_mask_reg1[30:0], 33'h100000000};
			_lzd_adder_inv_mask_f32_T_21 = {16'h0000, _lzd_adder_inv_mask_f32_T_11[63:16] & 48'hffff0000ffff} | {_lzd_adder_inv_mask_f32_T_11[47:0] & 48'hffff0000ffff, 16'h0000};
			_lzd_adder_inv_mask_f32_T_31 = {8'h00, _lzd_adder_inv_mask_f32_T_21[63:8] & 56'hff00ff00ff00ff} | {_lzd_adder_inv_mask_f32_T_21[55:0] & 56'hff00ff00ff00ff, 8'h00};
			_lzd_adder_inv_mask_f32_T_41 = {4'h0, _lzd_adder_inv_mask_f32_T_31[63:4] & 60'hf0f0f0f0f0f0f0f} | {_lzd_adder_inv_mask_f32_T_31[59:0] & 60'hf0f0f0f0f0f0f0f, 4'h0};
			_lzd_adder_inv_mask_f32_T_51 = {2'h0, _lzd_adder_inv_mask_f32_T_41[63:2] & 62'h3333333333333333} | {_lzd_adder_inv_mask_f32_T_41[61:0] & 62'h3333333333333333, 2'h0};
			_lzd_adder_inv_mask_f32_T_61 = (_lzd_adder_inv_mask_f32_T_51[63:1] & 63'h5555555555555555) | {1'h0, _lzd_adder_inv_mask_f32_T_51[60:0] & 61'h1555555555555555, 1'h0};
			_lzd_adder_inv_mask_f32_T_72 = {4'h0, lzd_adder_inv_mask_reg1[70:67]} | {lzd_adder_inv_mask_reg1[66:63], 4'h0};
			_lzd_adder_inv_mask_f32_T_82 = {2'h0, _lzd_adder_inv_mask_f32_T_72[7:2] & 6'h33} | {_lzd_adder_inv_mask_f32_T_72[5:0] & 6'h33, 2'h0};
			_lzd_adder_inv_mask_f32_T_92 = {1'h0, _lzd_adder_inv_mask_f32_T_82[7:1] & 7'h55} | {_lzd_adder_inv_mask_f32_T_82[6:0] & 7'h55, 1'h0};
			lzd_adder_inv_mask_f32 = (lzd_adder_inv_mask_reg1[75] ? 7'h00 : (lzd_adder_inv_mask_reg1[74] ? 7'h01 : (lzd_adder_inv_mask_reg1[73] ? 7'h02 : (lzd_adder_inv_mask_reg1[72] ? 7'h03 : (lzd_adder_inv_mask_reg1[71] ? 7'h04 : (_lzd_adder_inv_mask_f32_T_92[0] ? 7'h05 : (_lzd_adder_inv_mask_f32_T_92[1] ? 7'h06 : (_lzd_adder_inv_mask_f32_T_92[2] ? 7'h07 : (_lzd_adder_inv_mask_f32_T_92[3] ? 7'h08 : (_lzd_adder_inv_mask_f32_T_92[4] ? 7'h09 : (_lzd_adder_inv_mask_f32_T_92[5] ? 7'h0a : (_lzd_adder_inv_mask_f32_T_92[6] ? 7'h0b : (_lzd_adder_inv_mask_f32_T_92[7] ? 7'h0c : (_lzd_adder_inv_mask_f32_T_61[0] ? 7'h0d : (_lzd_adder_inv_mask_f32_T_61[1] ? 7'h0e : (_lzd_adder_inv_mask_f32_T_61[2] ? 7'h0f : (_lzd_adder_inv_mask_f32_T_61[3] ? 7'h10 : (_lzd_adder_inv_mask_f32_T_61[4] ? 7'h11 : (_lzd_adder_inv_mask_f32_T_61[5] ? 7'h12 : (_lzd_adder_inv_mask_f32_T_61[6] ? 7'h13 : (_lzd_adder_inv_mask_f32_T_61[7] ? 7'h14 : (_lzd_adder_inv_mask_f32_T_61[8] ? 7'h15 : (_lzd_adder_inv_mask_f32_T_61[9] ? 7'h16 : (_lzd_adder_inv_mask_f32_T_61[10] ? 7'h17 : (_lzd_adder_inv_mask_f32_T_61[11] ? 7'h18 : (_lzd_adder_inv_mask_f32_T_61[12] ? 7'h19 : (_lzd_adder_inv_mask_f32_T_61[13] ? 7'h1a : (_lzd_adder_inv_mask_f32_T_61[14] ? 7'h1b : (_lzd_adder_inv_mask_f32_T_61[15] ? 7'h1c : (_lzd_adder_inv_mask_f32_T_61[16] ? 7'h1d : (_lzd_adder_inv_mask_f32_T_61[17] ? 7'h1e : (_lzd_adder_inv_mask_f32_T_61[18] ? 7'h1f : (_lzd_adder_inv_mask_f32_T_61[19] ? 7'h20 : (_lzd_adder_inv_mask_f32_T_61[20] ? 7'h21 : (_lzd_adder_inv_mask_f32_T_61[21] ? 7'h22 : (_lzd_adder_inv_mask_f32_T_61[22] ? 7'h23 : (_lzd_adder_inv_mask_f32_T_61[23] ? 7'h24 : (_lzd_adder_inv_mask_f32_T_61[24] ? 7'h25 : (_lzd_adder_inv_mask_f32_T_61[25] ? 7'h26 : (_lzd_adder_inv_mask_f32_T_61[26] ? 7'h27 : (_lzd_adder_inv_mask_f32_T_61[27] ? 7'h28 : (_lzd_adder_inv_mask_f32_T_61[28] ? 7'h29 : (_lzd_adder_inv_mask_f32_T_61[29] ? 7'h2a : (_lzd_adder_inv_mask_f32_T_61[30] ? 7'h2b : (_lzd_adder_inv_mask_f32_T_61[31] ? 7'h2c : (_lzd_adder_inv_mask_f32_T_61[32] ? 7'h2d : (_lzd_adder_inv_mask_f32_T_61[33] ? 7'h2e : (_lzd_adder_inv_mask_f32_T_61[34] ? 7'h2f : (_lzd_adder_inv_mask_f32_T_61[35] ? 7'h30 : (_lzd_adder_inv_mask_f32_T_61[36] ? 7'h31 : (_lzd_adder_inv_mask_f32_T_61[37] ? 7'h32 : (_lzd_adder_inv_mask_f32_T_61[38] ? 7'h33 : (_lzd_adder_inv_mask_f32_T_61[39] ? 7'h34 : (_lzd_adder_inv_mask_f32_T_61[40] ? 7'h35 : (_lzd_adder_inv_mask_f32_T_61[41] ? 7'h36 : (_lzd_adder_inv_mask_f32_T_61[42] ? 7'h37 : (_lzd_adder_inv_mask_f32_T_61[43] ? 7'h38 : (_lzd_adder_inv_mask_f32_T_61[44] ? 7'h39 : (_lzd_adder_inv_mask_f32_T_61[45] ? 7'h3a : (_lzd_adder_inv_mask_f32_T_61[46] ? 7'h3b : (_lzd_adder_inv_mask_f32_T_61[47] ? 7'h3c : (_lzd_adder_inv_mask_f32_T_61[48] ? 7'h3d : (_lzd_adder_inv_mask_f32_T_61[49] ? 7'h3e : (_lzd_adder_inv_mask_f32_T_61[50] ? 7'h3f : (_lzd_adder_inv_mask_f32_T_61[51] ? 7'h40 : (_lzd_adder_inv_mask_f32_T_61[52] ? 7'h41 : (_lzd_adder_inv_mask_f32_T_61[53] ? 7'h42 : (_lzd_adder_inv_mask_f32_T_61[54] ? 7'h43 : (_lzd_adder_inv_mask_f32_T_61[55] ? 7'h44 : (_lzd_adder_inv_mask_f32_T_61[56] ? 7'h45 : (_lzd_adder_inv_mask_f32_T_61[57] ? 7'h46 : (_lzd_adder_inv_mask_f32_T_61[58] ? 7'h47 : (_lzd_adder_inv_mask_f32_T_61[59] ? 7'h48 : (_lzd_adder_inv_mask_f32_T_61[60] ? 7'h49 : (_lzd_adder_inv_mask_f32_T_61[61] ? 7'h4a : (_lzd_adder_inv_mask_f32_T_61[62] ? 7'h4b : 7'h4c))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))));
			_lzd_adder_inv_mask_f16_T_11 = {16'h0000, lzd_adder_inv_mask_reg1[30:15]} | {lzd_adder_inv_mask_reg1[14:0], 17'h10000};
			_lzd_adder_inv_mask_f16_T_21 = {8'h00, _lzd_adder_inv_mask_f16_T_11[31:8] & 24'hff00ff} | {_lzd_adder_inv_mask_f16_T_11[23:0] & 24'hff00ff, 8'h00};
			_lzd_adder_inv_mask_f16_T_31 = {4'h0, _lzd_adder_inv_mask_f16_T_21[31:4] & 28'hf0f0f0f} | {_lzd_adder_inv_mask_f16_T_21[27:0] & 28'hf0f0f0f, 4'h0};
			_lzd_adder_inv_mask_f16_T_41 = {2'h0, _lzd_adder_inv_mask_f16_T_31[31:2] & 30'h33333333} | {_lzd_adder_inv_mask_f16_T_31[29:0] & 30'h33333333, 2'h0};
			_lzd_adder_inv_mask_f16_T_51 = (_lzd_adder_inv_mask_f16_T_41[31:1] & 31'h55555555) | {1'h0, _lzd_adder_inv_mask_f16_T_41[28:0] & 29'h15555555, 1'h0};
			lzd_adder_inv_mask_f16 = (lzd_adder_inv_mask_reg1[36] ? 6'h00 : (lzd_adder_inv_mask_reg1[35] ? 6'h01 : (lzd_adder_inv_mask_reg1[34] ? 6'h02 : (lzd_adder_inv_mask_reg1[33] ? 6'h03 : (lzd_adder_inv_mask_reg1[32] ? 6'h04 : (lzd_adder_inv_mask_reg1[31] ? 6'h05 : (_lzd_adder_inv_mask_f16_T_51[0] ? 6'h06 : (_lzd_adder_inv_mask_f16_T_51[1] ? 6'h07 : (_lzd_adder_inv_mask_f16_T_51[2] ? 6'h08 : (_lzd_adder_inv_mask_f16_T_51[3] ? 6'h09 : (_lzd_adder_inv_mask_f16_T_51[4] ? 6'h0a : (_lzd_adder_inv_mask_f16_T_51[5] ? 6'h0b : (_lzd_adder_inv_mask_f16_T_51[6] ? 6'h0c : (_lzd_adder_inv_mask_f16_T_51[7] ? 6'h0d : (_lzd_adder_inv_mask_f16_T_51[8] ? 6'h0e : (_lzd_adder_inv_mask_f16_T_51[9] ? 6'h0f : (_lzd_adder_inv_mask_f16_T_51[10] ? 6'h10 : (_lzd_adder_inv_mask_f16_T_51[11] ? 6'h11 : (_lzd_adder_inv_mask_f16_T_51[12] ? 6'h12 : (_lzd_adder_inv_mask_f16_T_51[13] ? 6'h13 : (_lzd_adder_inv_mask_f16_T_51[14] ? 6'h14 : (_lzd_adder_inv_mask_f16_T_51[15] ? 6'h15 : (_lzd_adder_inv_mask_f16_T_51[16] ? 6'h16 : (_lzd_adder_inv_mask_f16_T_51[17] ? 6'h17 : (_lzd_adder_inv_mask_f16_T_51[18] ? 6'h18 : (_lzd_adder_inv_mask_f16_T_51[19] ? 6'h19 : (_lzd_adder_inv_mask_f16_T_51[20] ? 6'h1a : (_lzd_adder_inv_mask_f16_T_51[21] ? 6'h1b : (_lzd_adder_inv_mask_f16_T_51[22] ? 6'h1c : (_lzd_adder_inv_mask_f16_T_51[23] ? 6'h1d : (_lzd_adder_inv_mask_f16_T_51[24] ? 6'h1e : (_lzd_adder_inv_mask_f16_T_51[25] ? 6'h1f : (_lzd_adder_inv_mask_f16_T_51[26] ? 6'h20 : (_lzd_adder_inv_mask_f16_T_51[27] ? 6'h21 : (_lzd_adder_inv_mask_f16_T_51[28] ? 6'h22 : (_lzd_adder_inv_mask_f16_T_51[29] ? 6'h23 : {5'h12, ~_lzd_adder_inv_mask_f16_T_51[30]}))))))))))))))))))))))))))))))))))));
			_lshift_adder_f64_res_vec_1_T_2 = (lzd_adder_inv_mask_f64[7] ? {adder_f64_reg1[35:0], 128'h00000000000000000000000000000000} : adder_f64_reg1);
			_lshift_adder_f64_res_vec_2_T_2 = (lzd_adder_inv_mask_f64[6] ? {_lshift_adder_f64_res_vec_1_T_2[99:0], 20'h00000} : _lshift_adder_f64_res_vec_1_T_2[163:44]);
			_lshift_adder_f64_res_vec_3_T_2 = (lzd_adder_inv_mask_f64[5] ? _lshift_adder_f64_res_vec_2_T_2[87:0] : _lshift_adder_f64_res_vec_2_T_2[119:32]);
			_lshift_adder_f64_res_vec_4_T_2 = (lzd_adder_inv_mask_f64[4] ? _lshift_adder_f64_res_vec_3_T_2[71:0] : _lshift_adder_f64_res_vec_3_T_2[87:16]);
			_lshift_adder_f64_res_vec_5_T_2 = (lzd_adder_inv_mask_f64[3] ? _lshift_adder_f64_res_vec_4_T_2[63:0] : _lshift_adder_f64_res_vec_4_T_2[71:8]);
			_lshift_adder_f64_res_vec_6_T_2 = (lzd_adder_inv_mask_f64[2] ? _lshift_adder_f64_res_vec_5_T_2[59:0] : _lshift_adder_f64_res_vec_5_T_2[63:4]);
			_lshift_adder_f64_res_vec_7_T_2 = (lzd_adder_inv_mask_f64[1] ? _lshift_adder_f64_res_vec_6_T_2[57:0] : _lshift_adder_f64_res_vec_6_T_2[59:2]);
			_lshift_adder_f32_res_vec_1_T_2 = (lzd_adder_inv_mask_f32[6] ? {adder_f32_reg1[12:0], 64'h0000000000000000} : adder_f32_reg1);
			_lshift_adder_f32_res_vec_2_T_2 = (lzd_adder_inv_mask_f32[5] ? {_lshift_adder_f32_res_vec_1_T_2[44:0], 14'h0000} : _lshift_adder_f32_res_vec_1_T_2[76:18]);
			_lshift_adder_f32_res_vec_3_T_2 = (lzd_adder_inv_mask_f32[4] ? _lshift_adder_f32_res_vec_2_T_2[42:0] : _lshift_adder_f32_res_vec_2_T_2[58:16]);
			_lshift_adder_f32_res_vec_4_T_2 = (lzd_adder_inv_mask_f32[3] ? _lshift_adder_f32_res_vec_3_T_2[34:0] : _lshift_adder_f32_res_vec_3_T_2[42:8]);
			_lshift_adder_f32_res_vec_5_T_2 = (lzd_adder_inv_mask_f32[2] ? _lshift_adder_f32_res_vec_4_T_2[30:0] : _lshift_adder_f32_res_vec_4_T_2[34:4]);
			_lshift_adder_f32_res_vec_6_T_2 = (lzd_adder_inv_mask_f32[1] ? _lshift_adder_f32_res_vec_5_T_2[28:0] : _lshift_adder_f32_res_vec_5_T_2[30:2]);
			_lshift_adder_f16_res_vec_1_T_2 = (lzd_adder_inv_mask_f16[5] ? {adder_f16_reg1[5:0], 32'h00000000} : adder_f16_reg1);
			_lshift_adder_f16_res_vec_2_T_2 = (lzd_adder_inv_mask_f16[4] ? {_lshift_adder_f16_res_vec_1_T_2[21:0], 8'h00} : _lshift_adder_f16_res_vec_1_T_2[37:8]);
			_lshift_adder_f16_res_vec_3_T_2 = (lzd_adder_inv_mask_f16[3] ? _lshift_adder_f16_res_vec_2_T_2[21:0] : _lshift_adder_f16_res_vec_2_T_2[29:8]);
			_lshift_adder_f16_res_vec_4_T_2 = (lzd_adder_inv_mask_f16[2] ? _lshift_adder_f16_res_vec_3_T_2[17:0] : _lshift_adder_f16_res_vec_3_T_2[21:4]);
			_lshift_adder_f16_res_vec_5_T_2 = (lzd_adder_inv_mask_f16[1] ? _lshift_adder_f16_res_vec_4_T_2[15:0] : _lshift_adder_f16_res_vec_4_T_2[17:2]);
			_lshift_adder_inv_f64_T_3 = {57 {adder_is_negative_reg1}} ^ (lzd_adder_inv_mask_f64[0] ? _lshift_adder_f64_res_vec_7_T_2[56:0] : _lshift_adder_f64_res_vec_7_T_2[57:1]);
			_lshift_adder_inv_f32_T_3 = {28 {adder_is_negative_reg1}} ^ (lzd_adder_inv_mask_f32[0] ? _lshift_adder_f32_res_vec_6_T_2[27:0] : _lshift_adder_f32_res_vec_6_T_2[28:1]);
			_lshift_adder_inv_f16_T_3 = {15 {adder_is_negative_reg1}} ^ (lzd_adder_inv_mask_f16[0] ? _lshift_adder_f16_res_vec_5_T_2[14:0] : _lshift_adder_f16_res_vec_5_T_2[15:1]);
			_sticky_uf_f64_reg2_T = (tzd_adder_reg1[163] ? 8'h00 : (tzd_adder_reg1[162] ? 8'h01 : (tzd_adder_reg1[161] ? 8'h02 : (tzd_adder_reg1[160] ? 8'h03 : (tzd_adder_reg1[159] ? 8'h04 : (_tzd_adder_f64_reg1_T_173[0] ? 8'h05 : (_tzd_adder_f64_reg1_T_173[1] ? 8'h06 : (_tzd_adder_f64_reg1_T_173[2] ? 8'h07 : (_tzd_adder_f64_reg1_T_173[3] ? 8'h08 : (_tzd_adder_f64_reg1_T_173[4] ? 8'h09 : (_tzd_adder_f64_reg1_T_173[5] ? 8'h0a : (_tzd_adder_f64_reg1_T_173[6] ? 8'h0b : (_tzd_adder_f64_reg1_T_173[7] ? 8'h0c : (_tzd_adder_f64_reg1_T_173[8] ? 8'h0d : (_tzd_adder_f64_reg1_T_173[9] ? 8'h0e : (_tzd_adder_f64_reg1_T_173[10] ? 8'h0f : (_tzd_adder_f64_reg1_T_173[11] ? 8'h10 : (_tzd_adder_f64_reg1_T_173[12] ? 8'h11 : (_tzd_adder_f64_reg1_T_173[13] ? 8'h12 : (_tzd_adder_f64_reg1_T_173[14] ? 8'h13 : (_tzd_adder_f64_reg1_T_173[15] ? 8'h14 : (_tzd_adder_f64_reg1_T_173[16] ? 8'h15 : (_tzd_adder_f64_reg1_T_173[17] ? 8'h16 : (_tzd_adder_f64_reg1_T_173[18] ? 8'h17 : (_tzd_adder_f64_reg1_T_173[19] ? 8'h18 : (_tzd_adder_f64_reg1_T_173[20] ? 8'h19 : (_tzd_adder_f64_reg1_T_173[21] ? 8'h1a : (_tzd_adder_f64_reg1_T_173[22] ? 8'h1b : (_tzd_adder_f64_reg1_T_173[23] ? 8'h1c : (_tzd_adder_f64_reg1_T_173[24] ? 8'h1d : (_tzd_adder_f64_reg1_T_173[25] ? 8'h1e : (_tzd_adder_f64_reg1_T_173[26] ? 8'h1f : (_tzd_adder_f64_reg1_T_173[27] ? 8'h20 : (_tzd_adder_f64_reg1_T_173[28] ? 8'h21 : (_tzd_adder_f64_reg1_T_173[29] ? 8'h22 : (_tzd_adder_f64_reg1_T_173[30] ? 8'h23 : (_tzd_adder_f64_reg1_T_173[31] ? 8'h24 : (_tzd_adder_f64_reg1_T_121[0] ? 8'h25 : (_tzd_adder_f64_reg1_T_121[1] ? 8'h26 : (_tzd_adder_f64_reg1_T_121[2] ? 8'h27 : (_tzd_adder_f64_reg1_T_121[3] ? 8'h28 : (_tzd_adder_f64_reg1_T_121[4] ? 8'h29 : (_tzd_adder_f64_reg1_T_121[5] ? 8'h2a : (_tzd_adder_f64_reg1_T_121[6] ? 8'h2b : (_tzd_adder_f64_reg1_T_121[7] ? 8'h2c : (_tzd_adder_f64_reg1_T_121[8] ? 8'h2d : (_tzd_adder_f64_reg1_T_121[9] ? 8'h2e : (_tzd_adder_f64_reg1_T_121[10] ? 8'h2f : (_tzd_adder_f64_reg1_T_121[11] ? 8'h30 : (_tzd_adder_f64_reg1_T_121[12] ? 8'h31 : (_tzd_adder_f64_reg1_T_121[13] ? 8'h32 : (_tzd_adder_f64_reg1_T_121[14] ? 8'h33 : (_tzd_adder_f64_reg1_T_121[15] ? 8'h34 : (_tzd_adder_f64_reg1_T_121[16] ? 8'h35 : (_tzd_adder_f64_reg1_T_121[17] ? 8'h36 : (_tzd_adder_f64_reg1_T_121[18] ? 8'h37 : (_tzd_adder_f64_reg1_T_121[19] ? 8'h38 : (_tzd_adder_f64_reg1_T_121[20] ? 8'h39 : (_tzd_adder_f64_reg1_T_121[21] ? 8'h3a : (_tzd_adder_f64_reg1_T_121[22] ? 8'h3b : (_tzd_adder_f64_reg1_T_121[23] ? 8'h3c : (_tzd_adder_f64_reg1_T_121[24] ? 8'h3d : (_tzd_adder_f64_reg1_T_121[25] ? 8'h3e : (_tzd_adder_f64_reg1_T_121[26] ? 8'h3f : (_tzd_adder_f64_reg1_T_121[27] ? 8'h40 : (_tzd_adder_f64_reg1_T_121[28] ? 8'h41 : (_tzd_adder_f64_reg1_T_121[29] ? 8'h42 : (_tzd_adder_f64_reg1_T_121[30] ? 8'h43 : (_tzd_adder_f64_reg1_T_121[31] ? 8'h44 : (_tzd_adder_f64_reg1_T_121[32] ? 8'h45 : (_tzd_adder_f64_reg1_T_121[33] ? 8'h46 : (_tzd_adder_f64_reg1_T_121[34] ? 8'h47 : (_tzd_adder_f64_reg1_T_121[35] ? 8'h48 : (_tzd_adder_f64_reg1_T_121[36] ? 8'h49 : (_tzd_adder_f64_reg1_T_121[37] ? 8'h4a : (_tzd_adder_f64_reg1_T_121[38] ? 8'h4b : (_tzd_adder_f64_reg1_T_121[39] ? 8'h4c : (_tzd_adder_f64_reg1_T_121[40] ? 8'h4d : (_tzd_adder_f64_reg1_T_121[41] ? 8'h4e : (_tzd_adder_f64_reg1_T_121[42] ? 8'h4f : (_tzd_adder_f64_reg1_T_121[43] ? 8'h50 : (_tzd_adder_f64_reg1_T_121[44] ? 8'h51 : (_tzd_adder_f64_reg1_T_121[45] ? 8'h52 : (_tzd_adder_f64_reg1_T_121[46] ? 8'h53 : (_tzd_adder_f64_reg1_T_121[47] ? 8'h54 : (_tzd_adder_f64_reg1_T_121[48] ? 8'h55 : (_tzd_adder_f64_reg1_T_121[49] ? 8'h56 : (_tzd_adder_f64_reg1_T_121[50] ? 8'h57 : (_tzd_adder_f64_reg1_T_121[51] ? 8'h58 : (_tzd_adder_f64_reg1_T_121[52] ? 8'h59 : (_tzd_adder_f64_reg1_T_121[53] ? 8'h5a : (_tzd_adder_f64_reg1_T_121[54] ? 8'h5b : (_tzd_adder_f64_reg1_T_121[55] ? 8'h5c : (_tzd_adder_f64_reg1_T_121[56] ? 8'h5d : (_tzd_adder_f64_reg1_T_121[57] ? 8'h5e : (_tzd_adder_f64_reg1_T_121[58] ? 8'h5f : (_tzd_adder_f64_reg1_T_121[59] ? 8'h60 : (_tzd_adder_f64_reg1_T_121[60] ? 8'h61 : (_tzd_adder_f64_reg1_T_121[61] ? 8'h62 : (_tzd_adder_f64_reg1_T_121[62] ? 8'h63 : (_tzd_adder_f64_reg1_T_121[63] ? 8'h64 : (_tzd_adder_f64_reg1_T_61[0] ? 8'h65 : (_tzd_adder_f64_reg1_T_61[1] ? 8'h66 : (_tzd_adder_f64_reg1_T_61[2] ? 8'h67 : (_tzd_adder_f64_reg1_T_61[3] ? 8'h68 : (_tzd_adder_f64_reg1_T_61[4] ? 8'h69 : (_tzd_adder_f64_reg1_T_61[5] ? 8'h6a : (_tzd_adder_f64_reg1_T_61[6] ? 8'h6b : (_tzd_adder_f64_reg1_T_61[7] ? 8'h6c : (_tzd_adder_f64_reg1_T_61[8] ? 8'h6d : (_tzd_adder_f64_reg1_T_61[9] ? 8'h6e : (_tzd_adder_f64_reg1_T_61[10] ? 8'h6f : (_tzd_adder_f64_reg1_T_61[11] ? 8'h70 : (_tzd_adder_f64_reg1_T_61[12] ? 8'h71 : (_tzd_adder_f64_reg1_T_61[13] ? 8'h72 : (_tzd_adder_f64_reg1_T_61[14] ? 8'h73 : (_tzd_adder_f64_reg1_T_61[15] ? 8'h74 : (_tzd_adder_f64_reg1_T_61[16] ? 8'h75 : (_tzd_adder_f64_reg1_T_61[17] ? 8'h76 : (_tzd_adder_f64_reg1_T_61[18] ? 8'h77 : (_tzd_adder_f64_reg1_T_61[19] ? 8'h78 : (_tzd_adder_f64_reg1_T_61[20] ? 8'h79 : (_tzd_adder_f64_reg1_T_61[21] ? 8'h7a : (_tzd_adder_f64_reg1_T_61[22] ? 8'h7b : (_tzd_adder_f64_reg1_T_61[23] ? 8'h7c : (_tzd_adder_f64_reg1_T_61[24] ? 8'h7d : (_tzd_adder_f64_reg1_T_61[25] ? 8'h7e : (_tzd_adder_f64_reg1_T_61[26] ? 8'h7f : (_tzd_adder_f64_reg1_T_61[27] ? 8'h80 : (_tzd_adder_f64_reg1_T_61[28] ? 8'h81 : (_tzd_adder_f64_reg1_T_61[29] ? 8'h82 : (_tzd_adder_f64_reg1_T_61[30] ? 8'h83 : (_tzd_adder_f64_reg1_T_61[31] ? 8'h84 : (_tzd_adder_f64_reg1_T_61[32] ? 8'h85 : (_tzd_adder_f64_reg1_T_61[33] ? 8'h86 : (_tzd_adder_f64_reg1_T_61[34] ? 8'h87 : (_tzd_adder_f64_reg1_T_61[35] ? 8'h88 : (_tzd_adder_f64_reg1_T_61[36] ? 8'h89 : (_tzd_adder_f64_reg1_T_61[37] ? 8'h8a : (_tzd_adder_f64_reg1_T_61[38] ? 8'h8b : (_tzd_adder_f64_reg1_T_61[39] ? 8'h8c : (_tzd_adder_f64_reg1_T_61[40] ? 8'h8d : (_tzd_adder_f64_reg1_T_61[41] ? 8'h8e : (_tzd_adder_f64_reg1_T_61[42] ? 8'h8f : (_tzd_adder_f64_reg1_T_61[43] ? 8'h90 : (_tzd_adder_f64_reg1_T_61[44] ? 8'h91 : (_tzd_adder_f64_reg1_T_61[45] ? 8'h92 : (_tzd_adder_f64_reg1_T_61[46] ? 8'h93 : (_tzd_adder_f64_reg1_T_61[47] ? 8'h94 : (_tzd_adder_f64_reg1_T_61[48] ? 8'h95 : (_tzd_adder_f64_reg1_T_61[49] ? 8'h96 : (_tzd_adder_f64_reg1_T_61[50] ? 8'h97 : (_tzd_adder_f64_reg1_T_61[51] ? 8'h98 : (_tzd_adder_f64_reg1_T_61[52] ? 8'h99 : (_tzd_adder_f64_reg1_T_61[53] ? 8'h9a : (_tzd_adder_f64_reg1_T_61[54] ? 8'h9b : (_tzd_adder_f64_reg1_T_61[55] ? 8'h9c : (_tzd_adder_f64_reg1_T_61[56] ? 8'h9d : (_tzd_adder_f64_reg1_T_61[57] ? 8'h9e : (_tzd_adder_f64_reg1_T_61[58] ? 8'h9f : (_tzd_adder_f64_reg1_T_61[59] ? 8'ha0 : (_tzd_adder_f64_reg1_T_61[60] ? 8'ha1 : (_tzd_adder_f64_reg1_T_61[61] ? 8'ha2 : (_tzd_adder_f64_reg1_T_61[62] ? 8'ha3 : 8'ha4)))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))) + lzd_adder_inv_mask_f64;
			is_fix_f64 = _sticky_uf_f64_reg2_T == 8'ha3;
			_sticky_uf_f32_reg2_T = (tzd_adder_reg1[76] ? 7'h00 : (tzd_adder_reg1[75] ? 7'h01 : (tzd_adder_reg1[74] ? 7'h02 : (tzd_adder_reg1[73] ? 7'h03 : (tzd_adder_reg1[72] ? 7'h04 : (tzd_adder_reg1[71] ? 7'h05 : (_tzd_adder_f32_reg1_T_92[0] ? 7'h06 : (_tzd_adder_f32_reg1_T_92[1] ? 7'h07 : (_tzd_adder_f32_reg1_T_92[2] ? 7'h08 : (_tzd_adder_f32_reg1_T_92[3] ? 7'h09 : (_tzd_adder_f32_reg1_T_92[4] ? 7'h0a : (_tzd_adder_f32_reg1_T_92[5] ? 7'h0b : (_tzd_adder_f32_reg1_T_92[6] ? 7'h0c : (_tzd_adder_f32_reg1_T_92[7] ? 7'h0d : (_tzd_adder_f32_reg1_T_61[0] ? 7'h0e : (_tzd_adder_f32_reg1_T_61[1] ? 7'h0f : (_tzd_adder_f32_reg1_T_61[2] ? 7'h10 : (_tzd_adder_f32_reg1_T_61[3] ? 7'h11 : (_tzd_adder_f32_reg1_T_61[4] ? 7'h12 : (_tzd_adder_f32_reg1_T_61[5] ? 7'h13 : (_tzd_adder_f32_reg1_T_61[6] ? 7'h14 : (_tzd_adder_f32_reg1_T_61[7] ? 7'h15 : (_tzd_adder_f32_reg1_T_61[8] ? 7'h16 : (_tzd_adder_f32_reg1_T_61[9] ? 7'h17 : (_tzd_adder_f32_reg1_T_61[10] ? 7'h18 : (_tzd_adder_f32_reg1_T_61[11] ? 7'h19 : (_tzd_adder_f32_reg1_T_61[12] ? 7'h1a : (_tzd_adder_f32_reg1_T_61[13] ? 7'h1b : (_tzd_adder_f32_reg1_T_61[14] ? 7'h1c : (_tzd_adder_f32_reg1_T_61[15] ? 7'h1d : (_tzd_adder_f32_reg1_T_61[16] ? 7'h1e : (_tzd_adder_f32_reg1_T_61[17] ? 7'h1f : (_tzd_adder_f32_reg1_T_61[18] ? 7'h20 : (_tzd_adder_f32_reg1_T_61[19] ? 7'h21 : (_tzd_adder_f32_reg1_T_61[20] ? 7'h22 : (_tzd_adder_f32_reg1_T_61[21] ? 7'h23 : (_tzd_adder_f32_reg1_T_61[22] ? 7'h24 : (_tzd_adder_f32_reg1_T_61[23] ? 7'h25 : (_tzd_adder_f32_reg1_T_61[24] ? 7'h26 : (_tzd_adder_f32_reg1_T_61[25] ? 7'h27 : (_tzd_adder_f32_reg1_T_61[26] ? 7'h28 : (_tzd_adder_f32_reg1_T_61[27] ? 7'h29 : (_tzd_adder_f32_reg1_T_61[28] ? 7'h2a : (_tzd_adder_f32_reg1_T_61[29] ? 7'h2b : (_tzd_adder_f32_reg1_T_61[30] ? 7'h2c : (_tzd_adder_f32_reg1_T_61[31] ? 7'h2d : (_tzd_adder_f32_reg1_T_61[32] ? 7'h2e : (_tzd_adder_f32_reg1_T_61[33] ? 7'h2f : (_tzd_adder_f32_reg1_T_61[34] ? 7'h30 : (_tzd_adder_f32_reg1_T_61[35] ? 7'h31 : (_tzd_adder_f32_reg1_T_61[36] ? 7'h32 : (_tzd_adder_f32_reg1_T_61[37] ? 7'h33 : (_tzd_adder_f32_reg1_T_61[38] ? 7'h34 : (_tzd_adder_f32_reg1_T_61[39] ? 7'h35 : (_tzd_adder_f32_reg1_T_61[40] ? 7'h36 : (_tzd_adder_f32_reg1_T_61[41] ? 7'h37 : (_tzd_adder_f32_reg1_T_61[42] ? 7'h38 : (_tzd_adder_f32_reg1_T_61[43] ? 7'h39 : (_tzd_adder_f32_reg1_T_61[44] ? 7'h3a : (_tzd_adder_f32_reg1_T_61[45] ? 7'h3b : (_tzd_adder_f32_reg1_T_61[46] ? 7'h3c : (_tzd_adder_f32_reg1_T_61[47] ? 7'h3d : (_tzd_adder_f32_reg1_T_61[48] ? 7'h3e : (_tzd_adder_f32_reg1_T_61[49] ? 7'h3f : (_tzd_adder_f32_reg1_T_61[50] ? 7'h40 : (_tzd_adder_f32_reg1_T_61[51] ? 7'h41 : (_tzd_adder_f32_reg1_T_61[52] ? 7'h42 : (_tzd_adder_f32_reg1_T_61[53] ? 7'h43 : (_tzd_adder_f32_reg1_T_61[54] ? 7'h44 : (_tzd_adder_f32_reg1_T_61[55] ? 7'h45 : (_tzd_adder_f32_reg1_T_61[56] ? 7'h46 : (_tzd_adder_f32_reg1_T_61[57] ? 7'h47 : (_tzd_adder_f32_reg1_T_61[58] ? 7'h48 : (_tzd_adder_f32_reg1_T_61[59] ? 7'h49 : (_tzd_adder_f32_reg1_T_61[60] ? 7'h4a : (_tzd_adder_f32_reg1_T_61[61] ? 7'h4b : {6'h26, ~_tzd_adder_f32_reg1_T_61[62]})))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))))) + lzd_adder_inv_mask_f32;
			is_fix_f32 = _sticky_uf_f32_reg2_T == 7'h4c;
			_sticky_uf_f16_reg2_T = (tzd_adder_reg1[37] ? 6'h00 : (tzd_adder_reg1[36] ? 6'h01 : (tzd_adder_reg1[35] ? 6'h02 : (tzd_adder_reg1[34] ? 6'h03 : (tzd_adder_reg1[33] ? 6'h04 : (tzd_adder_reg1[32] ? 6'h05 : (tzd_adder_reg1[31] ? 6'h06 : (_tzd_adder_f16_reg1_T_51[0] ? 6'h07 : (_tzd_adder_f16_reg1_T_51[1] ? 6'h08 : (_tzd_adder_f16_reg1_T_51[2] ? 6'h09 : (_tzd_adder_f16_reg1_T_51[3] ? 6'h0a : (_tzd_adder_f16_reg1_T_51[4] ? 6'h0b : (_tzd_adder_f16_reg1_T_51[5] ? 6'h0c : (_tzd_adder_f16_reg1_T_51[6] ? 6'h0d : (_tzd_adder_f16_reg1_T_51[7] ? 6'h0e : (_tzd_adder_f16_reg1_T_51[8] ? 6'h0f : (_tzd_adder_f16_reg1_T_51[9] ? 6'h10 : (_tzd_adder_f16_reg1_T_51[10] ? 6'h11 : (_tzd_adder_f16_reg1_T_51[11] ? 6'h12 : (_tzd_adder_f16_reg1_T_51[12] ? 6'h13 : (_tzd_adder_f16_reg1_T_51[13] ? 6'h14 : (_tzd_adder_f16_reg1_T_51[14] ? 6'h15 : (_tzd_adder_f16_reg1_T_51[15] ? 6'h16 : (_tzd_adder_f16_reg1_T_51[16] ? 6'h17 : (_tzd_adder_f16_reg1_T_51[17] ? 6'h18 : (_tzd_adder_f16_reg1_T_51[18] ? 6'h19 : (_tzd_adder_f16_reg1_T_51[19] ? 6'h1a : (_tzd_adder_f16_reg1_T_51[20] ? 6'h1b : (_tzd_adder_f16_reg1_T_51[21] ? 6'h1c : (_tzd_adder_f16_reg1_T_51[22] ? 6'h1d : (_tzd_adder_f16_reg1_T_51[23] ? 6'h1e : (_tzd_adder_f16_reg1_T_51[24] ? 6'h1f : (_tzd_adder_f16_reg1_T_51[25] ? 6'h20 : (_tzd_adder_f16_reg1_T_51[26] ? 6'h21 : (_tzd_adder_f16_reg1_T_51[27] ? 6'h22 : (_tzd_adder_f16_reg1_T_51[28] ? 6'h23 : (_tzd_adder_f16_reg1_T_51[29] ? 6'h24 : (_tzd_adder_f16_reg1_T_51[30] ? 6'h25 : 6'h26)))))))))))))))))))))))))))))))))))))) + lzd_adder_inv_mask_f16;
			is_fix_f16 = _sticky_uf_f16_reg2_T == 6'h25;
			lshift_adder_inv_fix_f64 = (is_fix_f64 ? _lshift_adder_inv_f64_T_3[56:1] : _lshift_adder_inv_f64_T_3[55:0]);
			lshift_adder_inv_fix_f32 = (is_fix_f32 ? _lshift_adder_inv_f32_T_3[27:1] : _lshift_adder_inv_f32_T_3[26:0]);
			lshift_adder_inv_fix_f16 = (is_fix_f16 ? _lshift_adder_inv_f16_T_3[14:1] : _lshift_adder_inv_f16_T_3[13:0]);
			is_fp64_reg2 <= is_fp64_reg1;
			is_fp32_reg2 <= is_fp32_reg1;
			adder_is_negative_reg2 <= adder_is_negative_reg1;
			E_greater_reg2 <= E_greater_reg2_r_1;
			fraction_result_no_round_reg <= (is_fp64_reg1 ? lshift_adder_inv_fix_f64[54:3] : {29'h00000000, (is_fp32_reg1 ? lshift_adder_inv_fix_f32[25:3] : {13'h0000, lshift_adder_inv_fix_f16[12:3]})});
			sign_result_temp_f64_reg2 <= sign_result_temp_f64_reg2_r_2;
			sign_result_temp_f32_reg2 <= sign_result_temp_f32_reg2_r_2;
			sign_result_temp_f16_reg2 <= sign_result_temp_f16_reg2_r_2;
			RNE_reg2 <= RNE_reg2_r_1;
			RTZ_reg2 <= RTZ_reg2_r_1;
			RDN_reg2 <= RDN_reg2_r_1;
			RUP_reg2 <= RUP_reg2_r_1;
			RMM_reg2 <= RMM_reg2_r_1;
			sticky_f64_reg2 <= sticky_f64_reg2_r | (_sticky_uf_f64_reg2_T < 8'h6c);
			sticky_f32_reg2 <= sticky_f32_reg2_r | (_sticky_uf_f32_reg2_T < 7'h32);
			sticky_f16_reg2 <= sticky_f16_reg2_r | (_sticky_uf_f16_reg2_T < 6'h18);
			sticky_uf_f64_reg2 <= sticky_uf_f64_reg2_r | (_sticky_uf_f64_reg2_T < 8'h6b);
			sticky_uf_f32_reg2 <= sticky_uf_f32_reg2_r | (_sticky_uf_f32_reg2_T < 7'h31);
			sticky_uf_f16_reg2 <= sticky_uf_f16_reg2_r | (_sticky_uf_f16_reg2_T < 6'h17);
			round_lshift_f64_reg2 <= lshift_adder_inv_fix_f64[1];
			round_lshift_f32_reg2 <= lshift_adder_inv_fix_f32[1];
			round_lshift_f16_reg2 <= lshift_adder_inv_fix_f16[1];
			guard_lshift_f64_reg2 <= lshift_adder_inv_fix_f64[2];
			guard_lshift_f32_reg2 <= lshift_adder_inv_fix_f32[2];
			guard_lshift_f16_reg2 <= lshift_adder_inv_fix_f16[2];
			round_lshift_uf_f64_reg2 <= lshift_adder_inv_fix_f64[0];
			round_lshift_uf_f32_reg2 <= lshift_adder_inv_fix_f32[0];
			round_lshift_uf_f16_reg2 <= lshift_adder_inv_fix_f16[0];
			is_fix_reg2 <= (is_fp64_reg1 ? is_fix_f64 : (is_fp32_reg1 ? is_fix_f32 : is_fix_f16));
			lshift_value_reg2 <= (is_fp64_reg1 ? lzd_adder_inv_mask_f64 : {1'h0, (is_fp32_reg1 ? lzd_adder_inv_mask_f32 : {1'h0, lzd_adder_inv_mask_f16})});
			exponent_is_min_f64 <= (~lshift_adder_inv_fix_f64[55] & lshift_mask_valid_reg) & (_sticky_uf_f64_reg2_T != 8'ha3);
			exponent_is_min_f32 <= (~lshift_adder_inv_fix_f32[26] & lshift_mask_valid_reg) & (_sticky_uf_f32_reg2_T != 7'h4c);
			exponent_is_min_f16 <= (~lshift_adder_inv_fix_f16[13] & lshift_mask_valid_reg) & (_sticky_uf_f16_reg2_T != 6'h25);
			normal_result_is_zero_f64_reg2 <= normal_result_is_zero_f64_reg2_r;
			normal_result_is_zero_f32_reg2 <= normal_result_is_zero_f32_reg2_r;
			normal_result_is_zero_f16_reg2 <= normal_result_is_zero_f16_reg2_r;
			has_zero_f64_reg2_r_2 <= has_zero_f64_reg2_r_1;
			has_zero_f32_reg2_r_2 <= has_zero_f32_reg2_r_1;
			has_zero_f16_reg2_r_2 <= has_zero_f16_reg2_r_1;
			fp_result_fp_a_or_b_is_zero_reg <= fp_result_fp_a_or_b_is_zero_reg_r_1;
			has_nan_f64_reg2 <= has_nan_f64_reg2_r_1;
			has_nan_f64_is_NV_reg2 <= has_nan_f64_is_NV_reg2_r_1;
			has_inf_f64_reg2 <= has_inf_f64_reg2_r_1;
			has_inf_f64_is_NV_reg2 <= has_inf_f64_is_NV_reg2_r_1;
			has_inf_f64_result_inf_sign_reg2 <= has_inf_f64_result_inf_sign_reg2_r_1;
			fp_a_or_b_is_zero_f64_reg2 <= fp_a_or_b_is_zero_f64_reg2_r_1;
			has_nan_f32_reg2 <= has_nan_f32_reg2_r_1;
			has_nan_f32_is_NV_reg2 <= has_nan_f32_is_NV_reg2_r_1;
			has_inf_f32_reg2 <= has_inf_f32_reg2_r_1;
			has_inf_f32_is_NV_reg2 <= has_inf_f32_is_NV_reg2_r_1;
			has_inf_f32_result_inf_sign_reg2 <= has_inf_f32_result_inf_sign_reg2_r_1;
			fp_a_or_b_is_zero_f32_reg2 <= fp_a_or_b_is_zero_f32_reg2_r_1;
			has_nan_f16_reg2 <= has_nan_f16_reg2_r_1;
			has_nan_f16_is_NV_reg2 <= has_nan_f16_is_NV_reg2_r_1;
			has_inf_f16_reg2 <= has_inf_f16_reg2_r_1;
			has_inf_f16_is_NV_reg2 <= has_inf_f16_is_NV_reg2_r_1;
			has_inf_f16_result_inf_sign_reg2 <= has_inf_f16_result_inf_sign_reg2_r_1;
			fp_a_or_b_is_zero_f16_reg2 <= fp_a_or_b_is_zero_f16_reg2_r_1;
		end
	end
	BoothEncoderF64F32F16 U_BoothEncoder(
		.io_in_a((&io_fp_format ? {|io_fp_a[62:52], io_fp_a[51:0]} : (is_fp32 ? {29'h00000000, |io_fp_a[30:23], io_fp_a[22:0]} : {42'h00000000000, |io_fp_a[14:10], io_fp_a[9:0]}))),
		.io_in_b((&io_fp_format ? {|io_fp_b[62:52], io_fp_b[51:0]} : (is_fp32 ? {29'h00000000, |io_fp_b[30:23], io_fp_b[22:0]} : {42'h00000000000, |io_fp_b[14:10], io_fp_b[9:0]}))),
		.io_is_fp64(&io_fp_format),
		.io_is_fp32(is_fp32),
		.io_out_pp_0(_U_BoothEncoder_io_out_pp_0),
		.io_out_pp_1(_U_BoothEncoder_io_out_pp_1),
		.io_out_pp_2(_U_BoothEncoder_io_out_pp_2),
		.io_out_pp_3(_U_BoothEncoder_io_out_pp_3),
		.io_out_pp_4(_U_BoothEncoder_io_out_pp_4),
		.io_out_pp_5(_U_BoothEncoder_io_out_pp_5),
		.io_out_pp_6(_U_BoothEncoder_io_out_pp_6),
		.io_out_pp_7(_U_BoothEncoder_io_out_pp_7),
		.io_out_pp_8(_U_BoothEncoder_io_out_pp_8),
		.io_out_pp_9(_U_BoothEncoder_io_out_pp_9),
		.io_out_pp_10(_U_BoothEncoder_io_out_pp_10),
		.io_out_pp_11(_U_BoothEncoder_io_out_pp_11),
		.io_out_pp_12(_U_BoothEncoder_io_out_pp_12),
		.io_out_pp_13(_U_BoothEncoder_io_out_pp_13),
		.io_out_pp_14(_U_BoothEncoder_io_out_pp_14),
		.io_out_pp_15(_U_BoothEncoder_io_out_pp_15),
		.io_out_pp_16(_U_BoothEncoder_io_out_pp_16),
		.io_out_pp_17(_U_BoothEncoder_io_out_pp_17),
		.io_out_pp_18(_U_BoothEncoder_io_out_pp_18),
		.io_out_pp_19(_U_BoothEncoder_io_out_pp_19),
		.io_out_pp_20(_U_BoothEncoder_io_out_pp_20),
		.io_out_pp_21(_U_BoothEncoder_io_out_pp_21),
		.io_out_pp_22(_U_BoothEncoder_io_out_pp_22),
		.io_out_pp_23(_U_BoothEncoder_io_out_pp_23),
		.io_out_pp_24(_U_BoothEncoder_io_out_pp_24),
		.io_out_pp_25(_U_BoothEncoder_io_out_pp_25),
		.io_out_pp_26(_U_BoothEncoder_io_out_pp_26)
	);
	CSA_Nto2With3to2MainPipeline U_CSAnto2(
		.clock(clock),
		.io_fire(io_fire),
		.io_in_0(_U_BoothEncoder_io_out_pp_0),
		.io_in_1(_U_BoothEncoder_io_out_pp_1),
		.io_in_2(_U_BoothEncoder_io_out_pp_2),
		.io_in_3(_U_BoothEncoder_io_out_pp_3),
		.io_in_4(_U_BoothEncoder_io_out_pp_4),
		.io_in_5(_U_BoothEncoder_io_out_pp_5),
		.io_in_6(_U_BoothEncoder_io_out_pp_6),
		.io_in_7(_U_BoothEncoder_io_out_pp_7),
		.io_in_8(_U_BoothEncoder_io_out_pp_8),
		.io_in_9(_U_BoothEncoder_io_out_pp_9),
		.io_in_10(_U_BoothEncoder_io_out_pp_10),
		.io_in_11(_U_BoothEncoder_io_out_pp_11),
		.io_in_12(_U_BoothEncoder_io_out_pp_12),
		.io_in_13(_U_BoothEncoder_io_out_pp_13),
		.io_in_14(_U_BoothEncoder_io_out_pp_14),
		.io_in_15(_U_BoothEncoder_io_out_pp_15),
		.io_in_16(_U_BoothEncoder_io_out_pp_16),
		.io_in_17(_U_BoothEncoder_io_out_pp_17),
		.io_in_18(_U_BoothEncoder_io_out_pp_18),
		.io_in_19(_U_BoothEncoder_io_out_pp_19),
		.io_in_20(_U_BoothEncoder_io_out_pp_20),
		.io_in_21(_U_BoothEncoder_io_out_pp_21),
		.io_in_22(_U_BoothEncoder_io_out_pp_22),
		.io_in_23(_U_BoothEncoder_io_out_pp_23),
		.io_in_24(_U_BoothEncoder_io_out_pp_24),
		.io_in_25(_U_BoothEncoder_io_out_pp_25),
		.io_in_26(_U_BoothEncoder_io_out_pp_26),
		.io_out_sum(_U_CSAnto2_io_out_sum),
		.io_out_car(_U_CSAnto2_io_out_car)
	);
	CSA3to2 U_CSA3to2(
		.io_in_a(_U_CSAnto2_io_out_sum),
		.io_in_b((is_fp64_reg0 ? {_U_CSAnto2_io_out_car[106:1], ((is_sub_f64_reg0 & ~rshift_guard_reg) & ~rshift_round_reg) & ~rshift_sticky_reg} : (is_fp32_reg0 ? {_U_CSAnto2_io_out_car[106:59], 1'h0, _U_CSAnto2_io_out_car[57:1], ((CSA3to2_in_b_r & ~rshift_guard_reg) & ~rshift_round_reg) & ~rshift_sticky_reg} : {_U_CSAnto2_io_out_car[106:85], 1'h0, _U_CSAnto2_io_out_car[83:59], 1'h0, _U_CSAnto2_io_out_car[57:27], 1'h0, _U_CSAnto2_io_out_car[25:1], ((CSA3to2_in_b_r_1 & ~rshift_guard_reg) & ~rshift_round_reg) & ~rshift_sticky_reg}))),
		.io_in_c((is_fp64_reg0 ? {1'h0, fp_c_rshiftValue_inv_reg[105:0]} : (is_fp32_reg0 ? {59'h000000000000000, fp_c_rshiftValue_inv_reg[47:0]} : {85'h0000000000000000000000, fp_c_rshiftValue_inv_reg[21:0]}))),
		.io_out_sum(_U_CSA3to2_io_out_sum),
		.io_out_car(_U_CSA3to2_io_out_car)
	);
	assign io_fp_result = (is_fp64_reg2 ? (has_nan_f64_reg2 ? 64'h7ff8000000000000 : (has_inf_f64_reg2 ? (has_inf_f64_is_NV_reg2 ? 64'h7ff8000000000000 : {has_inf_f64_result_inf_sign_reg2, 63'h7ff0000000000000}) : (exponent_overflow_f64 ? {sign_result_temp_f64_reg2, ((RTZ_reg2 | (RDN_reg2 & ~sign_result_temp_f64_reg2)) | (RUP_reg2 & sign_result_temp_f64_reg2) ? 63'h7fefffffffffffff : 63'h7ff0000000000000)} : (has_zero_f64_reg2 ? (fp_a_or_b_is_zero_f64_reg2 ? fp_result_fp_a_or_b_is_zero_reg : (normal_result_is_zero_f64_reg2 ? {RDN_reg2, 63'h0000000000000000} : normal_result_f64)) : normal_result_f64)))) : (is_fp32_reg2 ? {32'hffffffff, (has_nan_f32_reg2 ? 32'h7fc00000 : (has_inf_f32_reg2 ? (has_inf_f32_is_NV_reg2 ? 32'h7fc00000 : {has_inf_f32_result_inf_sign_reg2, 31'h7f800000}) : (exponent_overflow_f32 ? {sign_result_temp_f32_reg2, ((RTZ_reg2 | (RDN_reg2 & ~sign_result_temp_f32_reg2)) | (RUP_reg2 & sign_result_temp_f32_reg2) ? 31'h7f7fffff : 31'h7f800000)} : (has_zero_f32_reg2 ? (fp_a_or_b_is_zero_f32_reg2 ? fp_result_fp_a_or_b_is_zero_reg[31:0] : (normal_result_is_zero_f32_reg2 ? {RDN_reg2, 31'h00000000} : normal_result_f32)) : normal_result_f32))))} : {48'hffffffffffff, (has_nan_f16_reg2 ? 16'h7e00 : (has_inf_f16_reg2 ? (has_inf_f16_is_NV_reg2 ? 16'h7e00 : {has_inf_f16_result_inf_sign_reg2, 15'h7c00}) : (exponent_overflow_f16 ? {sign_result_temp_f16_reg2, ((RTZ_reg2 | (RDN_reg2 & ~sign_result_temp_f16_reg2)) | (RUP_reg2 & sign_result_temp_f16_reg2) ? 15'h7bff : 15'h7c00)} : (has_zero_f16_reg2 ? (fp_a_or_b_is_zero_f16_reg2 ? fp_result_fp_a_or_b_is_zero_reg[15:0] : (normal_result_is_zero_f16_reg2 ? {RDN_reg2, 15'h0000} : normal_result_f16)) : normal_result_f16))))}));
	assign io_fflags = (is_fp64_reg2 ? (has_nan_f64_reg2 ? {has_nan_f64_is_NV_reg2, 4'h0} : (has_inf_f64_reg2 ? {has_inf_f64_is_NV_reg2, 4'h0} : (exponent_overflow_f64 ? 5'h05 : (has_zero_f64_reg2 & (fp_a_or_b_is_zero_f64_reg2 | normal_result_is_zero_f64_reg2) ? 5'h00 : {3'h0, UF_f64, NX_f64})))) : (is_fp32_reg2 ? (has_nan_f32_reg2 ? {has_nan_f32_is_NV_reg2, 4'h0} : (has_inf_f32_reg2 ? {has_inf_f32_is_NV_reg2, 4'h0} : (exponent_overflow_f32 ? 5'h05 : (has_zero_f32_reg2 & (fp_a_or_b_is_zero_f32_reg2 | normal_result_is_zero_f32_reg2) ? 5'h00 : {3'h0, UF_f32, NX_f32})))) : (has_nan_f16_reg2 ? {has_nan_f16_is_NV_reg2, 4'h0} : (has_inf_f16_reg2 ? {has_inf_f16_is_NV_reg2, 4'h0} : (exponent_overflow_f16 ? 5'h05 : (has_zero_f16_reg2 & (fp_a_or_b_is_zero_f16_reg2 | normal_result_is_zero_f16_reg2) ? 5'h00 : {3'h0, UF_f16, NX_f16}))))));
endmodule
module yunsuan_float_fma_fp32_wrapper (
	clk_i,
	rst_ni,
	operand_a_i,
	operand_b_i,
	operand_c_i,
	rnd_mode_i,
	op_i,
	in_valid_i,
	result_o,
	status_o
);
	input wire clk_i;
	input wire rst_ni;
	input wire [31:0] operand_a_i;
	input wire [31:0] operand_b_i;
	input wire [31:0] operand_c_i;
	input wire [2:0] rnd_mode_i;
	input wire [3:0] op_i;
	input wire in_valid_i;
	output wire [31:0] result_o;
	output wire [4:0] status_o;
	wire [63:0] result_full;
	FloatFMA i_fma(
		.clock(clk_i),
		.reset(~rst_ni),
		.io_fire(in_valid_i),
		.io_fp_a({32'b00000000000000000000000000000000, operand_a_i}),
		.io_fp_b({32'b00000000000000000000000000000000, operand_b_i}),
		.io_fp_c({32'b00000000000000000000000000000000, operand_c_i}),
		.io_round_mode(rnd_mode_i),
		.io_fp_format(2'b10),
		.io_op_code(op_i),
		.io_fp_result(result_full),
		.io_fflags(status_o),
		.io_fp_aIsFpCanonicalNAN(operand_a_i[30:0] == 31'h7fc00000),
		.io_fp_bIsFpCanonicalNAN(operand_b_i[30:0] == 31'h7fc00000),
		.io_fp_cIsFpCanonicalNAN(operand_c_i[30:0] == 31'h7fc00000)
	);
	assign result_o = result_full[31:0];
endmodule
