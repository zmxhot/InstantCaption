param([switch]$Offline, [switch]$Npu, [switch]$Tests, [switch]$Release, [switch]$Portable)
$ErrorActionPreference = 'Stop'
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 17 or 21.' }
$buildType = if ($Release) { 'Release' } elseif ($Npu) { 'Npu' } elseif ($Offline) { 'Offline' } else { 'Debug' }
$tasks = @(":app:assemble$buildType", "-PtestBuildType=$($buildType.ToLowerInvariant())")
if ($Portable) { $tasks += '-Pportable=true' }
if ($Tests) { $tasks += ":app:assemble${buildType}AndroidTest" }
Push-Location $PSScriptRoot
try { & .\gradlew.bat --no-daemon @tasks; if ($LASTEXITCODE -ne 0) { throw "Gradle build failed ($LASTEXITCODE)" } }
finally { Pop-Location }
