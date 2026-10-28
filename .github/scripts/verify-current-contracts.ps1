param(
    [string]$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
)

$ErrorActionPreference = "Stop"

# Current-source contracts only; no reactor build or old-artifact comparison.
$rootPom = [xml][System.IO.File]::ReadAllText((Join-Path $RepositoryRoot "pom.xml"))
$configurationFiles = @((Join-Path $RepositoryRoot "pom.xml")) + @(
    Get-ChildItem -LiteralPath (Join-Path $RepositoryRoot ".github/workflows") -File |
        Select-Object -ExpandProperty FullName
)
foreach ($path in $configurationFiles)
{
    if ([System.IO.File]::ReadAllText($path) -match '(?i)japicmp|revapi|api-compatibility|API Compatibility')
    {
        throw "Cross-version compatibility gate found in $path"
    }
}

& java (Join-Path $PSScriptRoot "VerifyQueryApiBudget.java") $RepositoryRoot
if ($LASTEXITCODE -ne 0)
{
    throw "Current query API budget verification failed"
}

$consumerRoot = Join-Path $RepositoryRoot "skis-integration-tests/src/consumer"
$consumers = @("spi-only", "listener-only", "minimal-h2", "minimal-postgresql")
foreach ($consumer in $consumers)
{
    $directory = Join-Path $consumerRoot $consumer
    $pom = [xml][System.IO.File]::ReadAllText((Join-Path $directory "pom.xml"))
    if ($null -ne $pom.project.parent)
    {
        throw "Independent consumer inherits a parent: $consumer"
    }
    if (@($rootPom.project.modules.module) -contains "skis-integration-tests/src/consumer/$consumer")
    {
        throw "Independent consumer must not join the reactor: $consumer"
    }
    foreach ($dependency in @($pom.project.dependencies.dependency))
    {
        if ([string]$dependency.artifactId -match '^skis-(test-|integration-tests|examples|benchmark)')
        {
            throw "Consumer depends on repository fixtures: $consumer / $($dependency.artifactId)"
        }
    }
    if ($consumer -in @("spi-only", "listener-only"))
    {
        $expected = if ($consumer -eq "spi-only") { "skis-dialect-api" } else { "skis-core" }
        $skisDependencies = @($pom.project.dependencies.dependency | Where-Object {
            [string]$_.groupId -eq "io.github.xhufang"
        })
        if ($skisDependencies.Count -ne 1 -or [string]$skisDependencies[0].artifactId -ne $expected)
        {
            throw "$consumer must depend on only $expected from SKIS"
        }
    }
}

foreach ($source in Get-ChildItem -LiteralPath $consumerRoot -Recurse -File -Filter "*.java" |
        Where-Object { $_.FullName -notmatch '[\\/]target[\\/]' })
{
    if ([System.IO.File]::ReadAllText($source.FullName) -match 'io\.skis\.[\w.]*\binternal\b')
    {
        throw "Independent consumer references internal code: $($source.FullName)"
    }
}

$supportRoot = Join-Path $RepositoryRoot "skis-dialect-support/src/main/java"
foreach ($source in Get-ChildItem -LiteralPath $supportRoot -Recurse -File -Filter "*.java")
{
    if ([System.IO.File]::ReadAllText($source.FullName) -match 'io\.skis\.dialect\.internal\b')
    {
        throw "Dialect support references private dialect implementation: $($source.FullName)"
    }
}

$mutationRoot = Join-Path $RepositoryRoot 'skis-mutation/src/main/java/io/skis/mutation'
if (Test-Path -LiteralPath (Join-Path $mutationRoot 'InsertResult.java')) {
    throw 'Removed InsertResult must not return as a compatibility shell'
}
foreach ($relative in @('skis-mutation/src/main/java/io/skis/mutation/MutationOperations.java',
    'skis-runtime/src/main/java/io/skis/runtime/DefaultSkisExecutor.java',
    'skis-runtime/src/main/java/io/skis/runtime/DefaultSkisSession.java')) {
    $source = [System.IO.File]::ReadAllText((Join-Path $RepositoryRoot $relative))
    if ($source -match '\b(insert|insertResult|insertReturning|batchInsert)\s*\(') {
        throw "Obsolete entity insertion entrypoint: $relative"
    }
    if ($source -notmatch '\binsertInto\s*\(') { throw "Missing insertInto: $relative" }
}
foreach ($stage in @('InsertInto', 'InsertWrite', 'BatchInsertWrite', 'ReturningWrite', 'Written',
    'UpdateSet', 'UpdateWrite', 'DeleteWrite')) {
    if (-not (Test-Path -LiteralPath (Join-Path $mutationRoot "$stage.java"))) {
        throw "Missing staged mutation contract: $stage"
    }
}

Write-Host "Current contracts verified: query API budget; staged mutations; four independent consumers; no old compatibility gate."
