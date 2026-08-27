# Scalar backend

`ScalarBackend` connects scalar issue, execution dispatch, the implemented
RV32I ALU, RV32M multiply/divide, and branch units, commit arbitration, RF
writeback, and scoreboard release.

The memory, system, and trap classes remain explicit Decoupled outputs. Until
their units are connected, backpressure stops only the selected instruction
class without corrupting another execution path.
