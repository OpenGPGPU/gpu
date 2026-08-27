# Scalar issue

`ScalarIssueStage` converts table-derived scalar decode controls into an atomic
register reservation. It uses the synchronous per-warp macro register manager
and its scoreboard.

Decoded instruction metadata enters a four-entry FIFO only when the matching
register request is accepted. Metadata and operands leave together, so RAW/WAW
stalls and downstream backpressure cannot associate operands with the wrong
warp or instruction.

Illegal instructions and instruction access faults reserve no source or
destination registers. They still pass through issue metadata for a later trap
dispatcher.

`ScalarExecutionDispatch` selects exactly one destination for every issued
instruction. Trap events override decoded execution controls; MUL and DIV are
separated before the base integer output. Backpressure is taken only from the
selected destination.
