[CmdletBinding()]
param(
    [string]$SharedRoot = '',
    [string]$AndroidSdk = '',
    [switch]$Online
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path -LiteralPath (Join-Path $repositoryRoot 'gradlew.bat'))) {
    throw 'Run this script from an EVCam checkout.'
}
if (-not $SharedRoot) {
    if (Test-Path -LiteralPath (Join-Path $repositoryRoot 'work/toolchain')) {
        $SharedRoot = $repositoryRoot
    } else {
        $SharedRoot = 'E:\GPT\EVCam'
    }
}
$SharedRoot = (Resolve-Path -LiteralPath $SharedRoot).Path
$jdkDirectory = Get-ChildItem -LiteralPath (Join-Path $SharedRoot 'work/toolchain') -Directory |
    Where-Object { $_.Name -like 'jdk-*' } | Sort-Object Name -Descending | Select-Object -First 1
if (-not $jdkDirectory -or -not (Test-Path -LiteralPath (Join-Path $jdkDirectory.FullName 'bin/java.exe'))) {
    throw 'Portable JDK missing. Set SharedRoot to the main project folder.'
}
if (-not $AndroidSdk) {
    $AndroidSdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
}
if (-not (Test-Path -LiteralPath (Join-Path $AndroidSdk 'platforms/android-36/android.jar'))) {
    throw 'Android SDK platform 36 missing. Set AndroidSdk to the installed SDK folder.'
}
$logDirectory = Join-Path $SharedRoot 'work/verification'
New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
$logPath = Join-Path $logDirectory ('build-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '.log')
$previousJava = $env:JAVA_HOME
$previousSdk = $env:ANDROID_HOME
$previousSdkRoot = $env:ANDROID_SDK_ROOT
try {
    $env:JAVA_HOME = $jdkDirectory.FullName
    $env:ANDROID_HOME = $AndroidSdk
    $env:ANDROID_SDK_ROOT = $AndroidSdk
    $gradleArguments = @(':app:testIndependentDebugUnitTest', ':app:testCompositeDebugUnitTest', ':app:assembleDebug', ':app:assembleRelease', '--no-daemon')
    if (-not $Online) { $gradleArguments += '--offline' }
    Push-Location $repositoryRoot
    try {
        & (Join-Path $repositoryRoot 'gradlew.bat') @gradleArguments *> $logPath
        $buildExitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($buildExitCode -ne 0) {
        Get-Content -LiteralPath $logPath -Tail 70
        throw "Gradle failed with exit code $buildExitCode. Log: $logPath"
    }
    $testFiles = @()
    foreach ($variant in @('Independent', 'Composite')) {
        $testFiles += @(Get-ChildItem -LiteralPath (Join-Path $repositoryRoot ('app/build/test-results/test' + $variant + 'DebugUnitTest')) -Filter 'TEST-*.xml')
    }
    if ($testFiles.Count -eq 0) { throw 'No JVM test result files were produced.' }
    $tests = 0; $failures = 0; $errors = 0; $skipped = 0
    foreach ($testFile in $testFiles) {
        [xml]$suite = Get-Content -LiteralPath $testFile.FullName -Raw
        $tests += [int]$suite.testsuite.tests
        $failures += [int]$suite.testsuite.failures
        $errors += [int]$suite.testsuite.errors
        $skipped += [int]$suite.testsuite.skipped
    }
    if ($failures -or $errors) { throw 'JVM tests reported failures.' }
    [pscustomobject]@{
        Repository = $repositoryRoot
        Java = $jdkDirectory.FullName
        Tests = $tests
        Failures = $failures
        Errors = $errors
        Skipped = $skipped
        Log = $logPath
    } | ConvertTo-Json
} finally {
    $env:JAVA_HOME = $previousJava
    $env:ANDROID_HOME = $previousSdk
    $env:ANDROID_SDK_ROOT = $previousSdkRoot
}
