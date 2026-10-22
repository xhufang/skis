param(
    [Parameter(Mandatory = $true)][string]$Classpath,
    [switch]$ChildFailure
)
$ErrorActionPreference = 'Stop'
if ($ChildFailure) {
    $output = (& java -cp $Classpath io.skis.testsupport.PostgresConsumerLauncher -- bash -c 'exit 7' 2>&1 | Out-String)
    if ($LASTEXITCODE -eq 0 -or $output -notmatch 'consumer failed, exit=7') {
        throw 'Consumer child failure was hidden or environment setup failed'
    }
    Write-Host 'Consumer child failure propagated after database and container cleanup.'
    exit 0
}
$names = @('SKIS_POSTGRES_URL', 'SKIS_POSTGRES_USER', 'SKIS_POSTGRES_PASSWORD', 'DOCKER_HOST', 'TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE')
$saved = @{}
foreach ($name in $names) { $saved[$name] = [Environment]::GetEnvironmentVariable($name) }
$variables = @(
    @{ SKIS_POSTGRES_URL = ' '; Expected = 'configuration' },
    @{ SKIS_POSTGRES_URL = 'jdbc:h2:mem:hidden'; Expected = 'configuration' },
    @{ SKIS_POSTGRES_URL = 'jdbc:postgresql://localhost/skis'; Expected = 'configuration' },
    @{ SKIS_POSTGRES_URL = 'jdbc:postgresql://127.0.0.1:1/skis'; SKIS_POSTGRES_USER = 'probe'; SKIS_POSTGRES_PASSWORD = 'probe-secret'; Expected = 'connection/version probe' }
)
try {
    foreach ($case in $variables) {
        foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $null) }
        # Explicit blank configuration must stay distinct from an absent URL.
        foreach ($name in $case.Keys | Where-Object { $_ -ne 'Expected' }) {
            [Environment]::SetEnvironmentVariable($name, $case[$name])
        }
        $output = (& java -cp $Classpath io.skis.testsupport.PostgresConsumerLauncher -- java -version 2>&1 | Out-String)
        if ($LASTEXITCODE -eq 0 -or $output -notmatch [regex]::Escape($case.Expected)) {
            throw "Required PostgreSQL negative case did not fail at expected phase: $($case.Expected)"
        }
        if ($output -match 'probe-secret') { throw 'PostgreSQL failure exposed a credential' }
        Write-Host "PostgreSQL negative case rejected at phase: $($case.Expected)"
    }
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $saved[$name]) }
}
# A network-isolated JVM with no mounted daemon socket cannot discover the host Docker fallback.
# Merely setting a broken DOCKER_HOST is insufficient: Testcontainers also tries local sockets.
$mount = "type=bind,source=$((Get-Location).Path),target=/workspace,readonly"
$output = (& docker run --rm --network none --mount $mount -w /workspace eclipse-temurin:21-jdk `
    java -cp $Classpath io.skis.testsupport.PostgresConsumerLauncher -- java -version 2>&1 | Out-String)
if ($LASTEXITCODE -eq 0 -or $output -notmatch 'PostgreSQL environment startup failed') {
    throw 'Missing Docker did not fail explicitly during PostgreSQL environment startup'
}
Write-Host 'PostgreSQL selection with no Docker fails instead of skipping or falling back.'
$global:LASTEXITCODE = 0
