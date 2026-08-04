#include "Vfp32_fma_lane_wrapper.h"
#include "verilated.h"

#include <cstdint>
#include <cstdio>
#include <cstdlib>

static void tick(Vfp32_fma_lane_wrapper &dut) {
  dut.clk_i = 0;
  dut.eval();
  dut.clk_i = 1;
  dut.eval();
}

static void execute(Vfp32_fma_lane_wrapper &dut, uint8_t op, bool modifier,
                    uint32_t a, uint32_t b, uint32_t c, uint32_t expected,
                    uint16_t tag) {
  dut.op_i = op;
  dut.op_mod_i = modifier;
  dut.operand_a_i = a;
  dut.operand_b_i = b;
  dut.operand_c_i = c;
  dut.tag_i = tag;
  dut.in_valid_i = 1;
  dut.clk_i = 0;
  dut.eval();
  while (!dut.in_ready_o) tick(dut);
  tick(dut);
  dut.in_valid_i = 0;

  while (!dut.out_valid_o) tick(dut);
  if (dut.result_o != expected || dut.status_o != 0 || dut.tag_o != tag) {
    std::fprintf(stderr,
                 "op=%u result=%08x expected=%08x status=%02x tag=%04x\n",
                 op, dut.result_o, expected, dut.status_o, dut.tag_o);
    std::exit(1);
  }

  for (int i = 0; i < 2; ++i) {
    tick(dut);
    if (!dut.out_valid_o || dut.result_o != expected || dut.tag_o != tag) {
      std::fprintf(stderr, "result changed while output was blocked\n");
      std::exit(1);
    }
  }
  dut.out_ready_i = 1;
  tick(dut);
  dut.out_ready_i = 0;
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  Vfp32_fma_lane_wrapper dut;
  dut.rst_ni = 0;
  dut.rnd_mode_i = 0;
  dut.in_valid_i = 0;
  dut.out_ready_i = 0;
  dut.flush_i = 0;
  tick(dut);
  tick(dut);
  dut.rst_ni = 1;

  execute(dut, 0, false, 0x3fc00000, 0x40000000, 0x3f000000,
          0x40600000, 0x1234); // 1.5 * 2.0 + 0.5 = 3.5
  execute(dut, 0, true, 0x3fc00000, 0x40000000, 0x3f000000,
          0x40200000, 0x2345); // 1.5 * 2.0 - 0.5 = 2.5
  execute(dut, 2, false, 0, 0x3fc00000, 0x40000000,
          0x40600000, 0x3456); // ADD consumes operand B + operand C
  execute(dut, 3, false, 0x3fc00000, 0x40000000, 0,
          0x40400000, 0x4567); // 1.5 * 2.0 = 3.0
  std::puts("fp32_fma_lane_test PASS");
  return 0;
}
