#include "VGpuCore.h"
#include "verilated.h"

#include <cstdio>
#include <cstdlib>

static void tick(VGpuCore &dut) {
  dut.clock = 0;
  dut.eval();
  dut.clock = 1;
  dut.eval();
}

static void initializeFp(VGpuCore &dut, int reg, uint32_t data) {
  dut.io_fpuInitialize_valid = 1;
  dut.io_fpuInitialize_bits_warpId = 0;
  dut.io_fpuInitialize_bits_rd = reg;
  dut.io_fpuInitialize_bits_data = data;
  dut.clock = 0;
  dut.eval();
  int timeout = 20;
  while (!dut.io_fpuInitialize_ready && --timeout) tick(dut);
  if (!timeout) std::exit(2);
  tick(dut);
  dut.io_fpuInitialize_valid = 0;
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  VGpuCore dut;
  dut.io_fetchRequest_ready = 1;
  dut.io_fpu_ready = 1;
  dut.io_vector_ready = 1;
  dut.io_vectorMemoryRequest_ready = 1;
  dut.io_vectorPageTableRequest_ready = 1;
  dut.io_memory_ready = 1;
  dut.io_system_ready = 1;
  dut.io_trap_ready = 1;

  dut.reset = 1;
  tick(dut);
  tick(dut);
  dut.reset = 0;
  initializeFp(dut, 1, 0x3fc00000); // 1.5
  initializeFp(dut, 2, 0x40000000); // 2.0

  dut.io_launch_valid = 1;
  dut.io_launch_bits_startPc = 0x100;
  dut.io_launch_bits_activeMask = 0xff;
  dut.clock = 0;
  dut.eval();
  int timeout = 50;
  while (!dut.io_launch_ready && --timeout) tick(dut);
  if (!timeout) std::exit(3);
  tick(dut);
  dut.io_launch_valid = 0;

  timeout = 50;
  while (!dut.io_fetchRequest_valid && --timeout) tick(dut);
  if (!timeout || dut.io_fetchRequest_bits_pc != 0x100) std::exit(4);

  // fadd.s f4, f1, f2
  dut.io_fetchResponse_valid = 1;
  dut.io_fetchResponse_bits_instruction = 0x00208253;
  dut.io_fetchResponse_bits_accessFault = 0;
  timeout = 50;
  while (!dut.io_fetchResponse_ready && --timeout) tick(dut);
  if (!timeout) std::exit(5);
  tick(dut);
  dut.io_fetchResponse_valid = 0;

  timeout = 100;
  while (!dut.io_committedFpuWriteback_valid && --timeout) tick(dut);
  if (!timeout) {
    std::fprintf(stderr, "timed out waiting for FP writeback\n");
    return 6;
  }
  if (dut.io_committedFpuWriteback_bits_warpId != 0 ||
      dut.io_committedFpuWriteback_bits_rd != 4 ||
      dut.io_committedFpuWriteback_bits_data != 0x40600000 ||
      !dut.io_committedFpuFlags_valid || dut.io_committedFpuFlags_bits_flags != 0) {
    std::fprintf(stderr, "bad FP commit warp=%u rd=%u data=%08x flags=%02x\n",
                 dut.io_committedFpuWriteback_bits_warpId,
                 dut.io_committedFpuWriteback_bits_rd,
                 dut.io_committedFpuWriteback_bits_data,
                 dut.io_committedFpuFlags_bits_flags);
    return 7;
  }
  std::puts("gpu_core_fpu_test PASS");
  return 0;
}
