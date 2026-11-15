param([Parameter(ValueFromRemainingArguments=$true)][string[]]$Tasks = @(':core:test', ':app:assembleDebug'))
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$localJdk = Get-ChildItem -LiteralPath "$projectRoot\.toolchain" -Directory -Filter 'jdk-*' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($localJdk) { $env:JAVA_HOME = $localJdk.FullName }
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 17 or install the project-local toolchain described in README.md.' }
$env:GRADLE_USER_HOME = "$projectRoot\.toolchain\gradle-home"
$env:ANDROID_USER_HOME = "$projectRoot\.toolchain\android-user"
if (Test-Path -LiteralPath "$projectRoot\.toolchain\android-sdk") {
    $env:ANDROID_HOME = "$projectRoot\.toolchain\android-sdk"
}
Push-Location $projectRoot
try {
    & "$projectRoot\gradlew.bat" @Tasks --console=plain
    exit $LASTEXITCODE
} finally { Pop-Location }
