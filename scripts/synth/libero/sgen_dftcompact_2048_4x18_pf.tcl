set script_dir [file dirname [file normalize [info script]]]
set repo_root [file normalize [file join $script_dir ".." ".." ".."]]
set reports_dir [file join $repo_root "reports" "libero"]
set project_name "sgen_dftcompact_2048_4x18_pf"
set project_dir [file join $::env(HOME) "libero_ws" $project_name]
set source_file [file join $repo_root "designs" "generated" "sgen" "dftcompact_2048_4x18.v"]

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
set_root -module {main::work}

configure_tool -name {SYNTHESIZE} -params {SYNPLIFY_OPTIONS:set_option -seqshift_to_uram 1; set_option -infer_seqShift 1}

run_tool -name SYNTHESIZE

if {[catch {run_tool -name COMPILE} result]} {
  puts "COMPILE did not finish cleanly: $result"
  puts "This is expected on MPFS095TL-FCSG325E because the design exceeds the package I/O limit."
}

foreach pair {
  {"designer/main/main_compile_netlist_resources.xml" "sgen_dftcompact_2048_4x18_compile_netlist_resources.xml"}
  {"designer/main/main_compile_netlist.log" "sgen_dftcompact_2048_4x18_compile_netlist.log"}
  {"synthesis/synlog/report/main_fpga_mapper_resourceusage.rpt" "sgen_dftcompact_2048_4x18_fpga_mapper_resourceusage.rpt"}
  {"synthesis/synlog/main_fpga_mapper.srr" "sgen_dftcompact_2048_4x18_fpga_mapper.srr"}
} {
  lassign $pair rel_path dest_name
  set src [file join $project_dir $rel_path]
  if {[file exists $src]} {
    file copy -force $src [file join $reports_dir $dest_name]
  }
}

save_project
