create_clock -name clock -period 1.000 [get_ports clock]

# Reserve 100 ps on each side for the surrounding pipeline and clock effects.
set_input_delay 0.100 -clock clock [all_inputs]
set_output_delay 0.100 -clock clock [all_outputs]

# Reset is a control signal, not a timed datapath input.
set_false_path -from [get_ports reset]
