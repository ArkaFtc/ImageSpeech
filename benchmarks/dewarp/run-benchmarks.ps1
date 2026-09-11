param([string]$Phase = 'alone')
$ErrorActionPreference = 'Stop'
$adbPath = 'C:\Users\aruns\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$methods = if ($Phase -eq 'alone') { @('dewarp-gpu', 'dewarp-cpu', 'uvdoc') } else { @('baseline', 'dewarp-gpu', 'dewarp-cpu', 'uvdoc') }
$chain = if ($Phase -eq 'alone') { 'false' } else { 'true' }
$iterations = if ($Phase -eq 'alone') { 5 } else { 3 }
foreach ($method in $methods) {
    Write-Output "Starting $method / $Phase"
    & $adbPath -s R5GL6478N0N shell am instrument -w -e method $method -e chain $chain -e iterations $iterations com.example.dewarpbenchmark/bench.ChainBenchmark |
        Tee-Object -FilePath (Join-Path $PSScriptRoot "$method-$Phase.log")
    if ($LASTEXITCODE -ne 0) { throw "ADB failed for $method" }
}
