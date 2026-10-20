param(
    [Parameter(Mandatory = $true)]
    [string]$ReportDirectory,
    [Parameter(Mandatory = $true)]
    [string[]]$RequiredSuites
)

$ErrorActionPreference = "Stop"

# A green Maven invocation is insufficient when database contracts were skipped.
foreach ($suite in $RequiredSuites)
{
    $path = Join-Path $ReportDirectory "TEST-$suite.xml"
    if (-not (Test-Path -LiteralPath $path -PathType Leaf))
    {
        throw "Required test report is missing: $suite"
    }
    $report = [xml][System.IO.File]::ReadAllText($path)
    $result = $report.testsuite
    if ([string]$result.name -ne $suite -or [int]$result.tests -le 0 -or
        [int]$result.failures -ne 0 -or [int]$result.errors -ne 0 -or
        [int]$result.skipped -ne 0 -or @($result.SelectNodes("testcase/skipped")).Count -gt 0)
    {
        throw "Required suite did not execute completely and successfully: $suite"
    }
    Write-Host "$suite : $($result.tests) executed, zero failures/errors/skips."
}
