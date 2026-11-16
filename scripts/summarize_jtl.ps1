param(
    [string]$Path = "target\jmeter_results.jtl"
)

$rows = Import-Csv $Path
if ($rows.Count -eq 0) {
    Write-Output "no samples"
    exit 1
}

$elapsed = $rows | ForEach-Object { [double]$_.elapsed } | Sort-Object
$count = $elapsed.Count
$p50 = $elapsed[[Math]::Max([int]($count * 0.50) - 1, 0)]
$p95 = $elapsed[[Math]::Max([int]($count * 0.95) - 1, 0)]
$p99 = $elapsed[[Math]::Max([int]($count * 0.99) - 1, 0)]
$start = ($rows | Measure-Object -Property timeStamp -Minimum).Minimum
$end = ($rows | ForEach-Object { [long]$_.timeStamp + [double]$_.elapsed } | Measure-Object -Maximum).Maximum
$durationSec = ($end - [long]$start) / 1000.0
$qps = if ($durationSec -gt 0) { $count / $durationSec } else { 0 }

Write-Output ("requests={0} duration={1:N1}s QPS={2:N1} P50={3:N0}ms P95={4:N0}ms P99={5:N0}ms" -f `
    $count, $durationSec, $qps, $p50, $p95, $p99)
