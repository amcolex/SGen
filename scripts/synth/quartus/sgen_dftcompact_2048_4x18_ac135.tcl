package require ::quartus::project
package require ::quartus::flow

set script_dir [file dirname [file normalize [info script]]]
set repo_root [file normalize [file join $script_dir ".." ".." ".."]]
set reports_dir [file join $repo_root "reports" "quartus"]
set project_name "quartus_sgen_dftcompact_2048_4x18_ac135"
set project_dir [file join $repo_root "build" "quartus" $project_name]
set output_dir [file join $project_dir "output_files"]
set source_file [file join $repo_root "designs" "generated" "sgen" "dftcompact_2048_4x18.v"]

file mkdir $reports_dir
file delete -force $project_dir
file mkdir $project_dir
cd $project_dir

project_new $project_name -overwrite
set_global_assignment -name DEVICE A3CW135BM16AE6S
set_global_assignment -name TOP_LEVEL_ENTITY main
set_global_assignment -name VERILOG_FILE $source_file
set_global_assignment -name PROJECT_OUTPUT_DIRECTORY output_files
set_global_assignment -name NUM_PARALLEL_PROCESSORS 4
set_global_assignment -name VERILOG_CONSTANT_LOOP_LIMIT 50000
# Virtual pins avoid the package I/O limit so Quartus can complete fit.
set_instance_assignment -name VIRTUAL_PIN ON -to * -entity main

export_assignments
execute_flow -compile

foreach report_name {
  quartus_sgen_dftcompact_2048_4x18_ac135.fit.summary
  quartus_sgen_dftcompact_2048_4x18_ac135.fit.rpt
  quartus_sgen_dftcompact_2048_4x18_ac135.syn.summary
  quartus_sgen_dftcompact_2048_4x18_ac135.sta.summary
} {
  set src [file join $output_dir $report_name]
  if {[file exists $src]} {
    file copy -force $src [file join $reports_dir $report_name]
  }
}

project_close
