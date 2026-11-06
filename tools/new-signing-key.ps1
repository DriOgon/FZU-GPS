$ErrorActionPreference = 'Stop'
$workspaceRoot = Split-Path -Parent $PSScriptRoot
$signingDirectory = Join-Path $workspaceRoot '.signing'
$propertiesPath = Join-Path $signingDirectory 'release.properties'
$keystorePath = Join-Path $signingDirectory 'fzu-release.jks'
if ((Test-Path -LiteralPath $propertiesPath) -or (Test-Path -LiteralPath $keystorePath)) {
    throw 'Signing material already exists. Reuse it; never replace the release identity.'
}
$keytoolPath = Join-Path $workspaceRoot '.tools\jdk17\bin\keytool.exe'
if (-not (Test-Path -LiteralPath $keytoolPath)) {
    if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 17.' }
    $keytoolPath = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
}
New-Item -ItemType Directory -Path $signingDirectory -Force | Out-Null
$randomBytes = New-Object byte[] 32
$generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$generator.GetBytes($randomBytes)
$generator.Dispose()
$password = ([BitConverter]::ToString($randomBytes)).Replace('-', '').ToLowerInvariant()
$env:FZU_RELEASE_STORE_PASSWORD = $password
try {
    & $keytoolPath -genkeypair -keystore $keystorePath -storetype JKS -alias fzu-release `
        -storepass:env FZU_RELEASE_STORE_PASSWORD -keypass:env FZU_RELEASE_STORE_PASSWORD `
        -keyalg RSA -keysize 3072 -validity 10000 `
        -dname 'CN=FZU GPS, OU=Personal Apps, O=DriOgon, C=CN'
    if ($LASTEXITCODE -ne 0) { throw 'keytool did not create a signing key.' }
    $properties = "storeFile=fzu-release.jks`nstorePassword=$password`nkeyAlias=fzu-release`nkeyPassword=$password`n"
    [System.IO.File]::WriteAllText($propertiesPath, $properties, (New-Object System.Text.UTF8Encoding($false)))
    Write-Output 'Release signing key created locally. Back up .signing privately; do not upload it.'
} finally {
    $env:FZU_RELEASE_STORE_PASSWORD = $null
    $password = $null
}
