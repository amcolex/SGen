set script_dir [file dirname [file normalize [info script]]]
set repo_root [file normalize [file join $script_dir ".." ".." ".."]]
set reports_dir [file join $repo_root "reports" "vivado"]
set source_file [file join $repo_root "designs" "generated" "sgen" "dftcompact_2048_4x18.v"]

file mkdir $reports_dir

create_project -in_memory -part xczu2cg-sbva484-2-i
read_verilog $source_file
synth_design -top main -part xczu2cg-sbva484-2-i -directive PerformanceOptimized -global_retiming on -fsm_extraction one_hot -keep_equivalent_registers -resource_sharing off -no_lc -shreg_min_size 5 -mode out_of_context -retiming

report_utilization -file [file join $reports_dir "vivado_sgen_dftcompact_2048_4x18_util.rpt"]
report_timing_summary -file [file join $reports_dir "vivado_sgen_dftcompact_2048_4x18_timing.rpt"]

exit
