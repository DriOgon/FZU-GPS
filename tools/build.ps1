param(
    [ValidateSet('Debug', 'Check', 'Release')][string]$Task = 'Check'
)
$ErrorActionPreference = 'Stop'
$workspaceRoot = Split-Path -Parent $PSScriptRoot
$toolRoot = Join-Path $workspaceRoot '.tools'
if (Test-Path -LiteralPath (Join-Path $toolRoot 'jdk17\bin\java.exe')) {
    $env:JAVA_HOME = Join-Path $toolRoot 'jdk17'
}
if (Test-Path -LiteralPath (Join-Path $toolRoot 'sdk\platforms\android-36')) {
    $env:ANDROID_HOME = Join-Path $toolRoot 'sdk'
}
$env:GRADLE_USER_HOME = Join-Path $toolRoot 'gradle-user'
$env:ANDROID_USER_HOME = Join-Path $toolRoot 'android-user'
$projectPath = Join-Path $workspaceRoot 'android-app'
$tasks = switch ($Task) {
    'Debug' { @('assembleDebug') }
    'Check' { @('assembleDebug', 'testDebugUnitTest', 'lintDebug') }
    'Release' {
        if (-not (Test-Path -LiteralPath (Join-Path $workspaceRoot '.signing\release.properties'))) {
            throw 'Run tools/new-signing-key.ps1 first. Signing keys must stay private.'
        }
        @('assembleRelease', 'lintRelease')
    }
}
& (Join-Path $projectPath 'gradlew.bat') -p $projectPath @tasks --console plain
if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
