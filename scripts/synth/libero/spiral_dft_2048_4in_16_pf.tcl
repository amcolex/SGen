set script_dir [file dirname [file normalize [info script]]]
set repo_root [file normalize [file join $script_dir ".." ".." ".."]]
set reports_dir [file join $repo_root "reports" "libero"]
set project_name "spiral_dft_2048_4in_16_pf"
set project_dir [file join $repo_root "build" "libero" $project_name]
set source_file [file join $repo_root "designs" "reference" "spiral" "spiral_dft_it_4in_2048_16bit_scaled.v"]

file mkdir $reports_dir
file delete -force $project_dir

new_project \
  -location $project_dir \
  -name $project_name \
  -hdl VERILOG \
  -family PolarFireSoC \
  -die MPFS095TL \
  -package FCSG325 \
  -speed STD \
  -die_voltage 1.0 \
  -part_range EXT \
  -instantiate_in_smartdesign 1 \
  -ondemand_build_dh 1 \
  -use_enhanced_constraint_flow 1

import_files -convert_EDN_to_HDL 0 -library {work} -hdl_source $source_file
set_root -module {spiral_dft_it_4in_2048_16bit_scaled::work}
run_tool -name SYNTHESIZE

if {[catch {run_tool -name COMPILE} result]} {
  puts "COMPILE did not finish cleanly: $result"
  puts "This is expected on MPFS095TL-FCSG325E because the design exceeds the package I/O limit."
}

foreach pair {
  {"designer/spiral_dft_it_4in_2048_16bit_scaled/spiral_dft_it_4in_2048_16bit_scaled_compile_netlist_resources.xml" "spiral_dft_it_4in_2048_16bit_scaled_compile_netlist_resources.xml"}
  {"designer/spiral_dft_it_4in_2048_16bit_scaled/spiral_dft_it_4in_2048_16bit_scaled_compile_netlist.log" "spiral_dft_it_4in_2048_16bit_scaled_compile_netlist.log"}
  {"synthesis/synlog/report/spiral_dft_it_4in_2048_16bit_scaled_fpga_mapper_resourceusage.rpt" "spiral_dft_it_4in_2048_16bit_scaled_fpga_mapper_resourceusage.rpt"}
} {
  lassign $pair rel_path dest_name
  set src [file join $project_dir $rel_path]
  if {[file exists $src]} {
    file copy -force $src [file join $reports_dir $dest_name]
  }
}

save_project
