param(
    [Parameter(Mandatory = $true)][string]$ReportPath,
    [string]$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
)

$ErrorActionPreference = 'Stop'
$manifestPath = Join-Path $RepositoryRoot '.github/quality/regression-suites.json'
$registry = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
if ($registry.schemaVersion -ne 1 -or $null -eq $registry.suites -or
    @($registry.suites.PSObject.Properties).Count -eq 0)
{
    throw 'Required regression suite registry must be nonempty and use schema 1'
}
$commit = & git -C $RepositoryRoot rev-parse HEAD
if ($LASTEXITCODE -ne 0 -or $commit -notmatch '^[0-9a-f]{40}$' -or
    ($env:GITHUB_SHA -and $env:GITHUB_SHA -ne $commit))
{
    throw 'Regression evidence must identify the actual checkout commit'
}

$results = @()
$verified = @{}
foreach ($module in $registry.suites.PSObject.Properties)
{
    if ($module.Name -notmatch '^skis-[a-z0-9-]+$' -or
        $module.Value -isnot [array] -or $module.Value.Count -eq 0)
    {
        throw "Required regression module must declare a nonempty suite array: $($module.Name)"
    }
    $directory = Join-Path $RepositoryRoot "$($module.Name)/target/surefire-reports"
    $suites = @(& (Join-Path $PSScriptRoot 'verify-test-reports.ps1') `
        -ReportDirectory $directory -RequiredSuites $module.Value -PassThru)
    foreach ($suite in $suites)
    {
        $verified["$($module.Name)/$($suite.suite)"] = $true
        $results += [pscustomobject]@{
            module = $module.Name
            suite = $suite.suite
            tests = $suite.tests
            sha256 = $suite.sha256
        }
    }
}

# Scan every ordinary report too, including newly added and unregistered suites.
foreach ($module in Get-ChildItem -LiteralPath $RepositoryRoot -Directory -Filter 'skis-*')
{
    $directory = Join-Path $module.FullName 'target/surefire-reports'
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) { continue }
    $files = @(Get-ChildItem -LiteralPath $directory -File -Filter 'TEST-*.xml' | Where-Object {
        -not $verified.ContainsKey("$($module.Name)/$($_.BaseName.Substring(5))")
    })
    if ($files.Count -gt 0)
    {
        $names = @($files | ForEach-Object { $_.BaseName.Substring(5) })
        & (Join-Path $PSScriptRoot 'verify-test-reports.ps1') `
            -ReportDirectory $directory -RequiredSuites $names
    }
}

$evidence = [ordered]@{
    schemaVersion = 1
    commit = $commit
    runId = $env:GITHUB_RUN_ID
    runAttempt = $env:GITHUB_RUN_ATTEMPT
    job = $env:GITHUB_JOB
    registrySha256 = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
    suiteCount = $results.Count
    tests = ($results | Measure-Object -Property tests -Sum).Sum
    suites = $results
}
$parent = Split-Path -Parent ([System.IO.Path]::GetFullPath($ReportPath))
[System.IO.Directory]::CreateDirectory($parent) | Out-Null
$evidence | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $ReportPath -Encoding utf8
Write-Host "Required regression evidence: $($results.Count) suites at $commit."
