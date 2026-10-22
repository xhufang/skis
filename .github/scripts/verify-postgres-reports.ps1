param(
    [Parameter(Mandatory = $true)][ValidateSet('testcontainers', 'external-url')][string]$ExpectedSource,
    [string]$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
)
$ErrorActionPreference = 'Stop'
$registry = Get-Content -LiteralPath (Join-Path $RepositoryRoot '.github/quality/postgresql-suites.json') -Raw | ConvertFrom-Json
foreach ($module in $registry.suites.PSObject.Properties) {
    $directory = Join-Path $RepositoryRoot "$($module.Name)/target/failsafe-reports"
    & (Join-Path $PSScriptRoot 'verify-test-reports.ps1') -ReportDirectory $directory -RequiredSuites $module.Value
    $all = @(Get-ChildItem -LiteralPath $directory -Filter 'TEST-*.xml' | ForEach-Object {
        ([xml][System.IO.File]::ReadAllText($_.FullName)).testsuite.name
    })
    # Newly tagged suites run automatically and must also be nonempty and without skips.
    & (Join-Path $PSScriptRoot 'verify-test-reports.ps1') -ReportDirectory $directory -RequiredSuites $all
    $output = (Get-ChildItem -LiteralPath $directory -Filter 'TEST-*.xml' | ForEach-Object {
        [System.IO.File]::ReadAllText($_.FullName)
    }) -join "`n"
    if ($output -notmatch "PostgreSQL environment: source=$ExpectedSource,") {
        throw "PG reports do not identify the required environment source: $ExpectedSource"
    }
}
