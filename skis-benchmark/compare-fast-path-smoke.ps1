param(
  [Parameter(Mandatory = $true)]
  [string]$BaselinePath,

  [Parameter(Mandatory = $true)]
  [string]$CurrentPath
)

$ErrorActionPreference = "Stop"

function Read-Result([string]$Path, [string]$Suffix) {
  $results = Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
  $result = @($results | Where-Object { $_.benchmark.EndsWith($Suffix) })
  if ($result.Count -ne 1) {
    throw "Expected one benchmark ending with '$Suffix' in $Path; found $($result.Count)"
  }
  return $result[0]
}

function Ratio([double]$Numerator, [double]$Denominator, [string]$Description) {
  if ($Denominator -le 0) {
    throw "$Description denominator must be positive"
  }
  return $Numerator / $Denominator
}

function Assert-CurrentPair(
  [string]$Name,
  [string]$JdbcSuffix,
  [string]$SkisSuffix,
  [double]$MaximumTimeRatio,
  [double]$MaximumAllocationRatio
) {
  $currentJdbcResult = Read-Result $CurrentPath $JdbcSuffix
  $currentSkisResult = Read-Result $CurrentPath $SkisSuffix
  $timeRatio = Ratio `
    $currentSkisResult.primaryMetric.score `
    $currentJdbcResult.primaryMetric.score `
    "$Name time"
  $allocationRatio = Ratio `
    $currentSkisResult.secondaryMetrics.'gc.alloc.rate.norm'.score `
    $currentJdbcResult.secondaryMetrics.'gc.alloc.rate.norm'.score `
    "$Name allocation"

  Write-Host ("{0}: time={1:P2}, allocation={2:P2}" -f $Name, $timeRatio, $allocationRatio)
  if ($timeRatio -gt $MaximumTimeRatio) {
    throw ("$Name SKIS/JDBC time ratio {0:N3} exceeds smoke limit {1:N3}" -f $timeRatio, $MaximumTimeRatio)
  }
  if ($allocationRatio -gt $MaximumAllocationRatio) {
    throw ("$Name SKIS/JDBC allocation ratio {0:N3} exceeds smoke limit {1:N3}" -f $allocationRatio, $MaximumAllocationRatio)
  }
}

$baselineJdbc = Read-Result $BaselinePath ".jdbc"
$baselineSkis = Read-Result $BaselinePath ".skis"
$currentJdbc = Read-Result $CurrentPath ".jdbc"
$currentSkis = Read-Result $CurrentPath ".skis"

$baselineTimeRatio = Ratio $baselineSkis.primaryMetric.score $baselineJdbc.primaryMetric.score "Baseline time"
$currentTimeRatio = Ratio $currentSkis.primaryMetric.score $currentJdbc.primaryMetric.score "Current time"
$baselineAllocationRatio = Ratio `
  $baselineSkis.secondaryMetrics.'gc.alloc.rate.norm'.score `
  $baselineJdbc.secondaryMetrics.'gc.alloc.rate.norm'.score `
  "Baseline allocation"
$currentAllocationRatio = Ratio `
  $currentSkis.secondaryMetrics.'gc.alloc.rate.norm'.score `
  $currentJdbc.secondaryMetrics.'gc.alloc.rate.norm'.score `
  "Current allocation"

Write-Host ("0.1.1 reference: time={0:P2}, allocation={1:P2}" -f $baselineTimeRatio, $baselineAllocationRatio)
Write-Host ("Current smoke:   time={0:P2}, allocation={1:P2}" -f $currentTimeRatio, $currentAllocationRatio)

$maximumTimeRatio = [Math]::Max(1.20, $baselineTimeRatio * 1.10)
$maximumAllocationRatio = [Math]::Max(1.25, $baselineAllocationRatio * 1.10)
if ($currentTimeRatio -gt $maximumTimeRatio) {
  throw ("SKIS/JDBC time ratio {0:N3} exceeds smoke limit {1:N3}" -f $currentTimeRatio, $maximumTimeRatio)
}
if ($currentAllocationRatio -gt $maximumAllocationRatio) {
  throw ("SKIS/JDBC allocation ratio {0:N3} exceeds smoke limit {1:N3}" -f $currentAllocationRatio, $maximumAllocationRatio)
}

Assert-CurrentPair "Unconditional entity query" ".jdbcAll" ".skisAll" 1.35 1.50
Assert-CurrentPair "Single-equality entity query" ".jdbcEquality" ".skisEquality" 1.35 1.50

Write-Host "Fast Path smoke remains within the configured guardrails."
