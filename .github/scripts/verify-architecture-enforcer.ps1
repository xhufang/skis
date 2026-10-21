param(
    [string]$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
)

$ErrorActionPreference = 'Stop'
$corePom = [xml][System.IO.File]::ReadAllText((Join-Path $RepositoryRoot 'skis-core/pom.xml'))
$rootPom = [xml][System.IO.File]::ReadAllText((Join-Path $RepositoryRoot 'pom.xml'))
$execution = @($corePom.project.build.plugins.plugin | Where-Object artifactId -eq 'maven-enforcer-plugin')[0].executions.execution |
    Where-Object id -eq 'enforce-runtime-boundaries'
if ($null -eq $execution) { throw 'Actual core Enforcer policy is missing' }
$fixtureRoot = if ($env:GITHUB_ACTIONS -eq 'true') {
    Join-Path $env:RUNNER_TEMP 'skis-enforcer-evidence'
} else {
    Join-Path ([System.IO.Path]::GetTempPath()) ('skis-enforcer-' + [guid]::NewGuid().ToString('N'))
}
[System.IO.Directory]::CreateDirectory($fixtureRoot) | Out-Null
$parentPath = [System.Security.SecurityElement]::Escape((Join-Path $RepositoryRoot 'pom.xml').Replace('\', '/'))
$version = [string]$rootPom.project.version
$wrapper = if ($IsWindows) { Join-Path $RepositoryRoot 'mvnw.cmd' } else { Join-Path $RepositoryRoot 'mvnw' }

# Uses the exact production execution, with direct and transitive dependency failures; no compilation.
$fixtures = @(
    @{ Name = 'compile-spring'; Scope = 'compile'; Artifact = 'spring-core'; ExpectedFailure = $true },
    @{ Name = 'transitive-spring'; Scope = 'compile'; Artifact = 'spring-jdbc'; ExpectedFailure = $true },
    @{ Name = 'test-spring'; Scope = 'test'; Artifact = 'spring-core'; ExpectedFailure = $false }
)
foreach ($fixture in $fixtures) {
    $executionXml = $execution.OuterXml
    if ($fixture.Name -eq 'transitive-spring') {
        # Allow this direct coordinate only; its forbidden spring-core/beans/tx closure must still fail.
        $executionXml = $executionXml.Replace('</includes>', '<include>org.springframework:spring-jdbc</include></includes>')
    }
    $pom = @"
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <parent><groupId>io.github.xhufang</groupId><artifactId>skis-parent</artifactId><version>$version</version><relativePath>$parentPath</relativePath></parent>
  <artifactId>skis-enforcer-fixture</artifactId><packaging>pom</packaging>
  <dependencies><dependency><groupId>org.springframework</groupId><artifactId>$($fixture.Artifact)</artifactId><scope>$($fixture.Scope)</scope></dependency></dependencies>
  <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-enforcer-plugin</artifactId><executions>$executionXml</executions></plugin></plugins></build>
</project>
"@
    $pomPath = Join-Path $fixtureRoot ($fixture.Name + '.xml')
    [System.IO.File]::WriteAllText($pomPath, $pom)
    $output = & $wrapper -B -ntp -f $pomPath validate 2>&1
    $exitCode = $LASTEXITCODE
    $logPath = Join-Path $fixtureRoot ($fixture.Name + '.log')
    $output | Out-File -LiteralPath $logPath -Encoding utf8
    if ($fixture.ExpectedFailure) {
        if ($exitCode -eq 0 -or ($output -join "`n") -notmatch 'bannedDependencies|BannedDependencies') {
            throw "Enforcer negative fixture did not fail for the intended rule: $($fixture.Name); see $logPath"
        }
    } elseif ($exitCode -ne 0) {
        throw "Enforcer test-scope fixture failed: $logPath"
    }
    Write-Host "Enforcer fixture $($fixture.Name): expected outcome verified. Evidence: $logPath"
}
