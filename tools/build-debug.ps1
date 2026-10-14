# Copyright (c) 2026 Chirag Goyal. All rights reserved.
# Build/test on Windows using the developer's locally installed Android SDK and Gradle.
[CmdletBinding()]
param([switch]$SkipUnitTests, [switch]$RunLint)
$ErrorActionPreference = 'Stop'
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $project

$java = Get-Command java -ErrorAction SilentlyContinue
if (-not $java) { throw 'Java/JDK 17 is missing. Configure Android Studio Gradle JDK to 17.' }
$javaInfo = (& java -version 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0) { throw 'Java is not working.' }
Write-Host $javaInfo

$gradleCmd = if (Test-Path '.\gradlew.bat') { '.\gradlew.bat' }
             elseif (Get-Command gradle -ErrorAction SilentlyContinue) { 'gradle' }
             else { throw 'Gradle not found. Install Gradle 8.13, then run: gradle wrapper --gradle-version 8.13 --distribution-type bin' }
$tasks = @(':app:assembleDebug')
if (-not $SkipUnitTests) { $tasks += ':app:testDebugUnitTest' }
if ($RunLint) { $tasks += ':app:lintDebug' }
Write-Host "Running: $gradleCmd $($tasks -join ' ')"
& $gradleCmd --no-daemon --stacktrace @tasks
if ($LASTEXITCODE -ne 0) { throw 'Android compilation/tests failed. See the Gradle errors above.' }
$apk = Join-Path $project 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $apk)) { throw 'Gradle returned success but debug APK was not found.' }
$sha = (Get-FileHash $apk -Algorithm SHA256).Hash
Write-Host "APK: $apk"
Write-Host "SHA-256: $sha"
Write-Host 'NOTE: A debug APK is for local tests, not a public audited release.'
