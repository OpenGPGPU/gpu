#include "Vfp32_fma_dual_equiv_wrapper.h"
#include "verilated.h"

#include <cstdint>
#include <cstdio>
#include <random>

static void tick(Vfp32_fma_dual_equiv_wrapper &dut) {
  dut.clk_i = 0;
  dut.eval();
  dut.clk_i = 1;
  dut.eval();
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  Vfp32_fma_dual_equiv_wrapper dut;
  std::mt19937_64 random(0x5eedf32aULL);
  dut.rst_ni = 0;
  dut.in_valid_i = 0;
  tick(dut);
  tick(dut);
  dut.rst_ni = 1;

  constexpr int kVectors = 20000;
  for (int i = 0; i < kVectors + 8; ++i) {
    dut.in_valid_i = i < kVectors;
    if (i < kVectors) {
      dut.operand_a_i = static_cast<uint32_t>(random());
      dut.operand_b_i = static_cast<uint32_t>(random());
      dut.operand_c_i = static_cast<uint32_t>(random());
      dut.rnd_mode_i = static_cast<uint8_t>(random() % 5);
      dut.op_i = static_cast<uint8_t>(random() % 4);
      dut.op_mod_i = static_cast<uint8_t>(random() & 1);
      dut.tag_i = static_cast<uint16_t>(i);
    }
    dut.clk_i = 0;
    dut.eval();
    if (dut.in_valid_i && !dut.both_ready_o) {
      std::fprintf(stderr, "unexpected backpressure at vector %d\n", i);
      return 1;
    }
    tick(dut);
    if (dut.mismatch_o) {
      std::fprintf(stderr, "serial/dual mismatch near vector %d\n", i);
      return 1;
    }
  }
  std::puts("fp32_fma_dual_equiv_test PASS (20000 vectors)");
  return 0;
}
