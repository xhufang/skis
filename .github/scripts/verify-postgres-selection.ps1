param(
    [switch]$UnitReports,
    [string]$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
)
$ErrorActionPreference = 'Stop'
$lock = Get-Content -LiteralPath (Join-Path $RepositoryRoot '.github/quality/postgresql-image.json') -Raw | ConvertFrom-Json
if ($lock.image -ne "postgres:$($lock.tag)@$($lock.digest)" -or
    $lock.tag -ne '16.15-bookworm' -or $lock.digest -notmatch '^sha256:[a-f0-9]{64}$') {
    throw 'Invalid PostgreSQL image lock'
}
$workflow = Get-Content -LiteralPath (Join-Path $RepositoryRoot '.github/workflows/ci.yml') -Raw
foreach ($match in [regex]::Matches($workflow, 'postgres:16[^\s''"]+')) {
    if ($match.Value -ne $lock.image) { throw 'CI service differs from PostgreSQL image lock' }
}
$registry = Get-Content -LiteralPath (Join-Path $RepositoryRoot '.github/quality/postgresql-suites.json') -Raw | ConvertFrom-Json
$declared = @($registry.suites.PSObject.Properties | ForEach-Object { $_.Value })
$discovered = @()
foreach ($module in Get-ChildItem -LiteralPath $RepositoryRoot -Directory) {
    $sourceRoot = Join-Path $module.FullName 'src/test/java'
    if (-not (Test-Path -LiteralPath $sourceRoot)) { continue }
    foreach ($file in Get-ChildItem -LiteralPath $sourceRoot -Filter '*.java' -Recurse) {
        $source = [System.IO.File]::ReadAllText($file.FullName)
        if ($source -match '@PostgresContract\b') {
            if ($source -match 'assumeTrue|disabledWithoutDocker|@Disabled|@EnabledIf') {
                throw "PG contract may not skip: $($file.Name)"
            }
            $package = [regex]::Match($source, '(?m)^package\s+([\w.]+);').Groups[1].Value
            $discovered += "$package.$($file.BaseName)"
        }
    }
}
if (@(Compare-Object @($declared | Sort-Object) @($discovered | Sort-Object)).Count -ne 0) {
    throw 'Every PostgreSQL contract must be registered as a required report'
}
if ($UnitReports) {
    foreach ($module in $registry.suites.PSObject.Properties) {
        $reports = Join-Path $RepositoryRoot "$($module.Name)/target/surefire-reports"
        foreach ($suite in $module.Value) {
            if (Test-Path -LiteralPath (Join-Path $reports "TEST-$suite.xml")) {
                throw "PG suite reached ordinary Surefire: $suite"
            }
        }
    }
    & (Join-Path $PSScriptRoot 'verify-test-reports.ps1') `
        -ReportDirectory (Join-Path $RepositoryRoot 'skis-integration-tests/target/surefire-reports') `
        -RequiredSuites io.skis.integration.SkisEmployeeDepartmentScalarH2ContractTest
}
Write-Host 'PostgreSQL image lock, suite registration and explicit selection verified.'
