# Integer execution

`IntegerExecuteStage` is the registered elastic boundary for RV32I ALU
operations. It selects PC or rs1 for the left operand and immediate or rs2 for
the right operand using the decode table controls.

`MultiplyExecuteStage` implements `MUL`, `MULH`, `MULHSU`, and `MULHU` behind a
backpressured registered boundary. `DivideExecuteStage` produces one quotient
bit per cycle for `DIV`, `DIVU`, `REM`, and `REMU`, including divide-by-zero
and signed-overflow results. Both share the scalar commit path.
