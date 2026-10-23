$ErrorActionPreference = 'Stop'
$verifier = Join-Path $PSScriptRoot '../verify-test-reports.ps1'
$directory = Join-Path ([System.IO.Path]::GetTempPath()) ('skis-report-contracts-' + [guid]::NewGuid())
[System.IO.Directory]::CreateDirectory($directory) | Out-Null
$path = Join-Path $directory 'TEST-example.ContractTest.xml'
$valid = '<testsuite name="example.ContractTest" tests="1" failures="0" errors="0" skipped="0"><testcase name="contract" classname="example.ContractTest"/></testsuite>'
$script:checked = 0

function Assert-Rejected([string]$Name, [scriptblock]$Action)
{
    $rejected = $false
    try { & $Action } catch { $rejected = $true }
    if (-not $rejected) { throw "Report gate accepted invalid evidence: $Name" }
    $script:checked++
    Write-Host "Rejected: $Name"
}

function Assert-InvalidReport([string]$Name, [string]$Xml)
{
    [System.IO.File]::WriteAllText($path, $Xml)
    Assert-Rejected $Name { & $verifier -ReportDirectory $directory -RequiredSuites example.ContractTest }
}

try
{
    [System.IO.File]::WriteAllText($path, $valid)
    $result = @(& $verifier -ReportDirectory $directory -RequiredSuites example.ContractTest -PassThru)
    if ($result.Count -ne 1 -or $result[0].tests -ne 1 -or
        $result[0].sha256 -ne (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant())
    {
        throw 'Valid report did not produce its actual count and SHA-256'
    }
    $script:checked++
    # Parameterized and dynamic test display names may repeat in valid Surefire XML.
    $twoCases = $valid.Replace('tests="1"', 'tests="2"').Replace('</testsuite>',
        '<testcase name="contract" classname="example.ContractTest"/></testsuite>')
    [System.IO.File]::WriteAllText($path, $twoCases)
    & $verifier -ReportDirectory $directory -RequiredSuites example.ContractTest
    $script:checked++

    Assert-Rejected 'missing report' {
        & $verifier -ReportDirectory $directory -RequiredSuites example.MissingTest
    }
    Assert-Rejected 'empty required suites' {
        & $verifier -ReportDirectory $directory -RequiredSuites @()
    }
    Assert-Rejected 'duplicate required suites' {
        & $verifier -ReportDirectory $directory -RequiredSuites @('example.ContractTest', 'example.ContractTest')
    }
    Assert-Rejected 'suite path traversal' {
        & $verifier -ReportDirectory $directory -RequiredSuites '../example.ContractTest'
    }
    Assert-InvalidReport 'malformed XML' '<testsuite'
    Assert-InvalidReport 'empty XML' ''
    Assert-InvalidReport 'wrong suite' ($valid.Replace('name="example.ContractTest"', 'name="example.OtherTest"'))
    Assert-InvalidReport 'wrong root' ($valid.Replace('testsuite', 'testsuites'))
    Assert-InvalidReport 'namespaced root' ($valid.Replace('<testsuite ', '<testsuite xmlns="urn:other" '))
    foreach ($attribute in @('tests', 'failures', 'errors', 'skipped'))
    {
        Assert-InvalidReport "missing $attribute" ([regex]::Replace($valid, " $attribute=`"[0-9]+`"", ''))
        foreach ($value in @('-1', '1.5', 'NaN', '', '9223372036854775808'))
        {
            Assert-InvalidReport "invalid $attribute=$value" ([regex]::Replace(
                $valid, "$attribute=`"[0-9]+`"", "$attribute=`"$value`""))
        }
    }
    foreach ($attribute in @('failures', 'errors', 'skipped'))
    {
        Assert-InvalidReport "nonzero $attribute" ($valid.Replace("$attribute=`"0`"", "$attribute=`"1`""))
    }
    Assert-InvalidReport 'zero tests' ($valid.Replace('tests="1"', 'tests="0"'))
    Assert-InvalidReport 'missing testcase' ($valid.Replace('<testcase name="contract" classname="example.ContractTest"/>', ''))
    Assert-InvalidReport 'inconsistent total' ($valid.Replace('tests="1"', 'tests="2"'))
    Assert-InvalidReport 'missing testcase name' ($valid.Replace(' name="contract"', ''))
    foreach ($node in @('failure', 'error', 'skipped', 'flakyFailure', 'flakyError', 'rerunFailure', 'rerunError'))
    {
        Assert-InvalidReport "hidden $node" ($valid.Replace('/>', "><$node/></testcase>"))
    }
    [System.IO.File]::WriteAllText($path, $valid)
    & $verifier -ReportDirectory $directory -RequiredSuites example.ContractTest
    Write-Host "Report gate contracts passed: $script:checked checks."
}
finally
{
    if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force }
    Remove-Item -LiteralPath $directory -Force
}
