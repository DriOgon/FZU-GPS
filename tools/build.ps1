param(
    [ValidateSet('Debug', 'Check', 'Release', 'DeviceTest')][string]$Task = 'Check'
)
$ErrorActionPreference = 'Stop'
$workspaceRoot = Split-Path -Parent $PSScriptRoot
$mappedDrive = $null
$buildRoot = $workspaceRoot
$previousEnvironment = @{
    JAVA_HOME = $env:JAVA_HOME
    ANDROID_HOME = $env:ANDROID_HOME
    GRADLE_USER_HOME = $env:GRADLE_USER_HOME
    ANDROID_USER_HOME = $env:ANDROID_USER_HOME
}
try {
# JDK 17 worker argument files and native Android tools can misread Chinese paths on Windows.
# SUBST aliases the same directory; it does not copy or relocate project files.
if ($workspaceRoot -match '[^\x00-\x7F]') {
    foreach ($candidateLetter in @('R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z')) {
        $candidateDrive = $candidateLetter + ':'
        if (-not ([Environment]::GetLogicalDrives() -contains ($candidateDrive + '\'))) {
            & subst.exe $candidateDrive $workspaceRoot
            if ($LASTEXITCODE -ne 0) { throw 'Could not create an ASCII build-path alias.' }
            $mappedDrive = $candidateDrive
            $buildRoot = $candidateDrive + '\'
            break
        }
    }
    if (-not $mappedDrive) { throw 'No free drive letter for the temporary ASCII build path.' }
}
$toolRoot = Join-Path $buildRoot '.tools'
if (Test-Path -LiteralPath (Join-Path $toolRoot 'jdk17\bin\java.exe')) {
    $env:JAVA_HOME = Join-Path $toolRoot 'jdk17'
}
if (Test-Path -LiteralPath (Join-Path $toolRoot 'sdk\platforms\android-36')) {
    $env:ANDROID_HOME = Join-Path $toolRoot 'sdk'
}
$env:GRADLE_USER_HOME = Join-Path $toolRoot 'gradle-user'
$env:ANDROID_USER_HOME = Join-Path $toolRoot 'android-user'
$projectPath = Join-Path $buildRoot 'android-app'
$tasks = @(switch ($Task) {
    'Debug' { @('assembleDebug') }
    'Check' { @('assembleDebug', 'testDebugUnitTest', 'lintDebug') }
    'DeviceTest' { @('connectedDebugAndroidTest') }
    'Release' {
        if (-not (Test-Path -LiteralPath (Join-Path $workspaceRoot '.signing\release.properties'))) {
            throw 'Run tools/new-signing-key.ps1 first. Signing keys must stay private.'
        }
        @('assembleRelease', 'lintRelease')
    }
})
    & (Join-Path $projectPath 'gradlew.bat') -p $projectPath @tasks --no-daemon --console plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
} finally {
    if ($mappedDrive) { & subst.exe $mappedDrive /D }
    foreach ($entry in $previousEnvironment.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
    }
}
