# Generic adaptive grid placement for SRAM macros. The script discovers
# synthesized block instances and computes a grid from the actual core
# boundary, so it works for both full-chip and backend-level flows.
set db [ord::get_db]
set block [[$db getChip] getBlock]
set dbu [[$db getTech] getDbUnitsPerMicron]
set macro_names {}
set max_w 0.0
set max_h 0.0
foreach inst [$block getInsts] {
  if {[[$inst getMaster] isBlock]} {
    set m [$inst getMaster]
    set w [expr {[$m getWidth] / double($dbu)}]
    set h [expr {[$m getHeight] / double($dbu)}]
    if {$w > $max_w} { set max_w $w }
    if {$h > $max_h} { set max_h $h }
    lappend macro_names [$inst getName]
  }
}
set macro_names [lsort $macro_names]
if {[llength $macro_names] == 0} {
  error "compute-unit placement expects SRAM macros, found none"
}

set core [$block getCoreArea]
set core_x_min [expr {[$core xMin] / double($dbu)}]
set core_y_min [expr {[$core yMin] / double($dbu)}]
set core_w [expr {([$core xMax] - [$core xMin]) / double($dbu)}]
set core_h [expr {([$core yMax] - [$core yMin]) / double($dbu)}]
# Keep a real routing channel between adjacent SRAM macros. The original 2 um
# gaps created tight horizontal strips that the global router could not use.
set macro_channel 10.0
set pitch_x [expr {$max_w + $macro_channel}]
set pitch_y [expr {$max_h + $macro_channel}]
set margin 10.0
set max_cols [expr {int(($core_w - 2 * $margin) / $pitch_x)}]
set max_rows [expr {int(($core_h - 2 * $margin) / $pitch_y)}]
if {$max_cols < 1} {
  set max_cols 1
}
if {$max_rows < 1} {
  set max_rows 1
}
set count [llength $macro_names]
set best_rows 1
set best_penalty {}
for {set rows 1} {$rows <= $max_rows} {incr rows} {
  set cols [expr {int(ceil($count / double($rows)))}]
  if {$cols > $max_cols} {
    continue
  }
  set span_w [expr {$cols * $pitch_x - $macro_channel}]
  set span_h [expr {$rows * $pitch_y - $macro_channel}]
  set penalty [expr {abs($span_w - $span_h)}]
  if {$best_penalty eq {} || $penalty < $best_penalty} {
    set best_penalty $penalty
    set best_rows $rows
  }
}
set columns [expr {int(ceil($count / double($best_rows)))}]
set rows_count $best_rows
set span_w [expr {$columns * $pitch_x - $macro_channel}]
set span_h [expr {$rows_count * $pitch_y - $macro_channel}]
set grid_x0 [expr {$core_x_min + $margin + ($core_w - 2 * $margin - $span_w) / 2.0}]
set grid_y0 [expr {$core_y_min + $margin + ($core_h - 2 * $margin - $span_h) / 2.0}]
if {$grid_x0 < $core_x_min + $margin} {
  set grid_x0 [expr {$core_x_min + $margin}]
}
if {$grid_y0 < $core_y_min + $margin} {
  set grid_y0 [expr {$core_y_min + $margin}]
}
for {set index 0} {$index < [llength $macro_names]} {incr index} {
  set column [expr {$index % $columns}]
  set row [expr {$index / $columns}]
  set x [expr {$grid_x0 + $pitch_x * $column}]
  set y [expr {$grid_y0 + $pitch_y * $row}]
  place_macro \
    -macro_name [lindex $macro_names $index] \
    -location [list $x $y] \
    -orientation R0 \
    -exact
}
