param(
  [string]$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
)

$ErrorActionPreference = "Stop"

function Read-Pom([string]$Path) {
  return [xml][System.IO.File]::ReadAllText($Path)
}

function Get-ReactorProjects([string]$PomPath, [hashtable]$Projects) {
  $resolvedPom = (Resolve-Path $PomPath).Path
  if ($Projects.ContainsKey($resolvedPom)) {
    return
  }

  $pom = Read-Pom $resolvedPom
  $artifactId = [string]$pom.project.artifactId
  if ([string]::IsNullOrWhiteSpace($artifactId)) {
    throw "POM has no artifactId: $resolvedPom"
  }
  $Projects[$resolvedPom] = $pom

  foreach ($module in @($pom.project.modules.module)) {
    $modulePom = Join-Path (Split-Path $resolvedPom) ([string]$module)
    $modulePom = Join-Path $modulePom "pom.xml"
    if (-not (Test-Path -LiteralPath $modulePom)) {
      throw "Reactor module has no POM: $modulePom"
    }
    Get-ReactorProjects $modulePom $Projects
  }
}

function Get-DeploySkip([string]$PomPath, [hashtable]$Projects) {
  $resolvedPom = (Resolve-Path $PomPath).Path
  $pom = $Projects[$resolvedPom]
  $localValue = [string]$pom.project.properties.'maven.deploy.skip'
  if (-not [string]::IsNullOrWhiteSpace($localValue)) {
    return [System.Convert]::ToBoolean($localValue)
  }

  $parent = $pom.project.parent
  if ($null -eq $parent) {
    return $false
  }
  $relativePath = [string]$parent.relativePath
  if ([string]::IsNullOrWhiteSpace($relativePath)) {
    $relativePath = "../pom.xml"
  }
  $parentPom = Join-Path (Split-Path $resolvedPom) $relativePath
  if (-not (Test-Path -LiteralPath $parentPom)) {
    return $false
  }
  $resolvedParent = (Resolve-Path $parentPom).Path
  if (-not $Projects.ContainsKey($resolvedParent)) {
    return $false
  }
  return Get-DeploySkip $resolvedParent $Projects
}

function Assert-SameValues([string]$Description, [string[]]$Expected, [string[]]$Actual) {
  $expectedValues = @($Expected | Sort-Object -Unique)
  $actualValues = @($Actual | Sort-Object -Unique)
  $difference = Compare-Object $expectedValues $actualValues
  if ($null -ne $difference) {
    $details = $difference | ForEach-Object { "$($_.SideIndicator) $($_.InputObject)" }
    throw "$Description differs:`n$($details -join [Environment]::NewLine)"
  }
}

$rootPomPath = Join-Path $RepositoryRoot "pom.xml"
$bomPomPath = Join-Path $RepositoryRoot "skis-bom/pom.xml"
$allowlistPath = Join-Path $RepositoryRoot ".github/release-components.txt"

$projects = @{}
Get-ReactorProjects $rootPomPath $projects

$artifactPoms = @{}
foreach ($entry in $projects.GetEnumerator()) {
  $artifactId = [string]$entry.Value.project.artifactId
  if ($artifactPoms.ContainsKey($artifactId)) {
    throw "Duplicate reactor artifactId: $artifactId"
  }
  $artifactPoms[$artifactId] = $entry.Key
}

$allowlist = @(
  Get-Content -LiteralPath $allowlistPath |
    ForEach-Object { $_.Trim() } |
    Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
)
$normalizedAllowlist = @($allowlist | Sort-Object -Unique)
if ($allowlist.Count -ne $normalizedAllowlist.Count -or
    ($allowlist -join "`n") -ne ($normalizedAllowlist -join "`n")) {
  throw "Release allowlist must be sorted and contain each artifact exactly once"
}

$bom = Read-Pom $bomPomPath
$bomArtifacts = @($bom.project.dependencyManagement.dependencies.dependency.artifactId)
$expectedFromBom = @("skis-parent", "skis-bom") + $bomArtifacts
Assert-SameValues "Release allowlist and public BOM" $expectedFromBom $allowlist

$missingReactorArtifacts = @(
  $allowlist | Where-Object { -not $artifactPoms.ContainsKey([string]$_) }
)
if ($missingReactorArtifacts.Count -gt 0) {
  throw "Public release artifacts are missing from the reactor: $($missingReactorArtifacts -join ', ')"
}

foreach ($artifactId in $artifactPoms.Keys) {
  $deploySkipped = Get-DeploySkip $artifactPoms[$artifactId] $projects
  if ($allowlist -contains $artifactId) {
    if ($deploySkipped) {
      throw "Public release artifact unexpectedly skips deployment: $artifactId"
    }
  } elseif (-not $deploySkipped) {
    throw "Internal reactor artifact does not skip deployment: $artifactId"
  }
}

$rootPom = Read-Pom $rootPomPath
$releaseProfile = @($rootPom.project.profiles.profile) | Where-Object { $_.id -eq "release" }
$centralPlugin = @($releaseProfile.build.plugins.plugin) |
  Where-Object { $_.artifactId -eq "central-publishing-maven-plugin" }
$configuredExclusions = @(
  ([string]$centralPlugin.configuration.excludeArtifacts).Split(",") |
    ForEach-Object { $_.Trim() } |
    Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
)
$expectedExclusions = @($artifactPoms.Keys | Where-Object { $allowlist -notcontains $_ })
Assert-SameValues "Central exclusions and internal reactor artifacts" $expectedExclusions $configuredExclusions

Write-Host "Release boundary verified: $($allowlist.Count) public components, $($expectedExclusions.Count) internal components."
