param(
    [Parameter(Mandatory = $true)]
    [string]$ReportDirectory,
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string[]]$RequiredSuites,
    [switch]$PassThru
)

$ErrorActionPreference = "Stop"

# Missing counters, inconsistent totals and rerun failures must not become a green gate.
if ($RequiredSuites.Count -eq 0 -or
    @($RequiredSuites | Select-Object -Unique).Count -ne $RequiredSuites.Count)
{
    throw 'Required suites must be nonempty and unique'
}
foreach ($suite in $RequiredSuites)
{
    if ($suite -notmatch '^[A-Za-z_$][\w$]*(\.[A-Za-z_$][\w$]*)+$')
    {
        throw "Invalid required suite name: $suite"
    }
    $path = Join-Path $ReportDirectory "TEST-$suite.xml"
    if (-not (Test-Path -LiteralPath $path -PathType Leaf))
    {
        throw "Required test report is missing: $suite"
    }
    $report = [xml][System.IO.File]::ReadAllText($path)
    $result = $report.DocumentElement
    # XML attributes shadow CLR properties in PowerShell's XML adapter (notably name).
    if ($result.get_Name() -cne 'testsuite' -or $result.get_NamespaceURI() -cne '' -or
        $result.GetAttribute('name') -cne $suite)
    {
        throw "Required report has the wrong suite: $suite"
    }
    $counts = @{}
    foreach ($attribute in @('tests', 'failures', 'errors', 'skipped'))
    {
        $value = $result.GetAttribute($attribute)
        [long]$count = 0
        if ($value -notmatch '^[0-9]+$' -or -not [long]::TryParse($value, [ref]$count))
        {
            throw "Required report has an invalid $attribute counter: $suite"
        }
        $counts[$attribute] = $count
    }
    $cases = @($result.SelectNodes('testcase'))
    $failed = $result.SelectNodes(
        './/failure | .//error | .//skipped | .//flakyFailure | .//flakyError | .//rerunFailure | .//rerunError')
    if ($counts.tests -le 0 -or $counts.failures -ne 0 -or $counts.errors -ne 0 -or
        $counts.skipped -ne 0 -or $cases.Count -ne $counts.tests -or $failed.Count -ne 0 -or
        @($cases | Where-Object { [string]::IsNullOrWhiteSpace($_.GetAttribute('name')) }).Count -ne 0)
    {
        throw "Required suite did not execute completely and successfully: $suite"
    }
    Write-Host "$suite : $($counts.tests) executed, zero failures/errors/skips."
    if ($PassThru)
    {
        [pscustomobject]@{
            suite = $suite
            tests = $counts.tests
            sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    }
}
