source $::env(SCRIPTS_DIR)/load.tcl
load_design 6_final.odb 6_final.sdc
read_spef "$::env(RESULTS_DIR)/6_final.spef"

puts "=== GLOBAL CORE-CLOCK SETUP PATHS ==="
report_checks -path_delay max -group_count 20 -endpoint_count 1 \
  -fields {slew capacitance input_pins nets fanout} -digits 3

set mid_sum_regs [get_cells -hierarchical *mid_pipe_sum_q*]
puts "=== MID-SUM REGISTER TO DOWNSTREAM PATHS ==="
puts "mid_sum_register_count=[llength $mid_sum_regs]"
if {[llength $mid_sum_regs] > 0} {
  report_checks -from $mid_sum_regs -path_delay max -group_count 20 \
    -endpoint_count 1 -fields {slew capacitance input_pins nets fanout} -digits 3
}
