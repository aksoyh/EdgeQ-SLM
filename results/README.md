# Results Directory

This directory contains benchmark results exported from the EdgeQ-SLM app.

## Expected Files

After running a benchmark, the following files will be generated:

| File | Format | Description |
|------|--------|-------------|
| `latency_memory_cpu_[timestamp].csv` | CSV | Raw results for all runs |
| `aggregated_stats_[timestamp].csv` | CSV | Grouped statistics (mean±std) |
| `sanity_checks_[timestamp].csv` | CSV | Output quality check results |
| `sample_outputs_[timestamp].json` | JSON | Sample outputs for review |
| `run_metadata_[timestamp].json` | JSON | Experiment configuration |
| `device_info_[timestamp].txt` | Text | Device specifications |

## CSV Schema

### latency_memory_cpu.csv

```csv
timestamp,prompt_id,prompt_category,temperature,engine_variant,repeat_index,is_warmup,
total_latency_ms,ttft_ms,decode_ms,tokens_generated,tokens_per_sec,
peak_memory_mb,avg_memory_mb,memory_method,cpu_percent,cpu_method,
output_chars,output_tokens,sanity_is_empty,sanity_repetition,sanity_is_coherent,
success,error
```

### aggregated_stats.csv

```csv
prompt_id,prompt_category,temperature,engine_variant,run_count,
latency_mean_ms,latency_std_ms,ttft_mean_ms,ttft_std_ms,
decode_mean_ms,decode_std_ms,tok_per_sec_mean,tok_per_sec_std,
memory_mean_mb,memory_std_mb,cpu_mean_percent,cpu_std_percent,
tokens_mean,tokens_std,empty_outputs,high_repetition,success_rate
```

## Sample Data

See the `_sample` files for example output format.

## Note

Large result files should not be committed to git. Add to `.gitignore`:
```
results/*.csv
results/*.json
!results/README.md
!results/*_sample.*
```
