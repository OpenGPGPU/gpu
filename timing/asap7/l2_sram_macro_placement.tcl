# Deterministic 6 x 3 placement for one two-way SharedL2Slice. The script
# discovers synthesized macro instance names so it remains stable across
# Yosys/OpenROAD renaming changes.
set db [ord::get_db]
set block [[$db getChip] getBlock]
set macro_names {}
foreach inst [$block getInsts] {
  if {[[$inst getMaster] isBlock]} {
    lappend macro_names [$inst getName]
  }
}
set macro_names [lsort $macro_names]
if {[llength $macro_names] != 18} {
  error "SharedL2Slice placement expects 18 SRAM macros, found [llength $macro_names]"
}

for {set index 0} {$index < 18} {incr index} {
  set column [expr {$index % 6}]
  set row [expr {$index / 6}]
  set x [expr {25.0 + 50.0 * $column}]
  set y [expr {25.0 + 100.0 * $row}]
  place_macro \
    -macro_name [lindex $macro_names $index] \
    -location [list $x $y] \
    -orientation R0 \
    -exact
}
