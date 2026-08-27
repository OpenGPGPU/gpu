# Control-flow execution

`ScalarBranchResolver` handles scalar conditional branches, JAL, and JALR. It
uses the decoded `BranchOp`, including distinct signed and unsigned compares,
and clears bit zero of a JALR target.

`ScalarBranchExecuteStage` couples the resolver to issued operand metadata. It
preserves the active mask and produces both the scheduler redirect and the
JAL/JALR link-register information under backpressure.

`SimtBranchResolver` intersects each lane's predicate with the warp's active
mask. Uniform branches continue directly. A divergent branch selects the taken
lanes as the current path and emits explicit alternate-path and reconvergence
stack entries.

`SimtBranchStackSequencer` converts that result into ordered, backpressured
stack writes. Reconvergence is written before the alternate path, followed by
release of the current path. This interface keeps branch comparison separate
from the small sequential stack controller and avoids a long combinational
branch-to-fetch path.
