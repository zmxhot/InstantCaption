param([switch]$Initialize)
$ErrorActionPreference = 'Stop'
# Keystore and recovery password never enter the source tree or command arguments.
$keyDirectory = Join-Path $env:USERPROFILE '.codex\secrets\instant-caption'
$keyFile = Join-Path $keyDirectory 'release.p12'
$passwordFile = Join-Path $keyDirectory 'recovery-password.txt'
if ($Initialize -and -not (Test-Path -LiteralPath $keyFile)) {
    if (Test-Path -LiteralPath $passwordFile) { throw 'Incomplete signing setup: preserve the recovery password and inspect the key directory.' }
    New-Item -ItemType Directory -Path $keyDirectory -Force | Out-Null
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
    & icacls.exe $keyDirectory /inheritance:r /grant:r "${identity}:(OI)(CI)F" 'SYSTEM:(OI)(CI)F' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot protect signing directory' }
    $randomBytes = New-Object byte[] 48
    $randomGenerator = [Security.Cryptography.RandomNumberGenerator]::Create()
    $randomGenerator.GetBytes($randomBytes)
    $randomGenerator.Dispose()
    [IO.File]::WriteAllText($passwordFile, [Convert]::ToBase64String($randomBytes))
    $env:CAPTION_STORE_PASSWORD = [IO.File]::ReadAllText($passwordFile).Trim()
    $keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
    & $keytool -genkeypair -keystore $keyFile -storetype PKCS12 -alias instant-caption -keyalg RSA -keysize 4096 -validity 10000 -dname 'CN=Instant Caption Release' -storepass:env CAPTION_STORE_PASSWORD -keypass:env CAPTION_STORE_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw 'Signing key creation failed; preserve the password file for recovery.' }
}
if (-not (Test-Path -LiteralPath $keyFile) -or -not (Test-Path -LiteralPath $passwordFile)) { throw 'Signing credentials unavailable. Run tools/signing.ps1 -Initialize once, or provide CAPTION_* environment variables independently.' }
$env:CAPTION_KEYSTORE = $keyFile
$env:CAPTION_KEY_ALIAS = 'instant-caption'
$env:CAPTION_STORE_PASSWORD = [IO.File]::ReadAllText($passwordFile).Trim()
$env:CAPTION_KEY_PASSWORD = $env:CAPTION_STORE_PASSWORD
Write-Host 'Release signing credentials loaded from the private user directory.'
