set script_dir [file dirname [file normalize [info script]]]
set repo_root [file normalize [file join $script_dir ".." ".." ".."]]
set reports_dir [file join $repo_root "reports" "vivado"]
set source_file [file join $repo_root "designs" "reference" "spiral" "spiral_dft_it_4in_2048_16bit_scaled.v"]

file mkdir $reports_dir

create_project -in_memory -part xczu2cg-sbva484-2-i
read_verilog $source_file
synth_design -top spiral_dft_it_4in_2048_16bit_scaled -part xczu2cg-sbva484-2-i -directive PerformanceOptimized -global_retiming on -fsm_extraction one_hot -keep_equivalent_registers -resource_sharing off -no_lc -shreg_min_size 5 -mode out_of_context -retiming

report_utilization -file [file join $reports_dir "vivado_spiral_dft_2048_4in_16_util.rpt"]
report_timing_summary -file [file join $reports_dir "vivado_spiral_dft_2048_4in_16_timing.rpt"]

exit
