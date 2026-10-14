# Copyright (c) 2026 Chirag Goyal. All rights reserved.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$apk = Join-Path $PSScriptRoot '..\app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path $apk)) { throw 'No APK exists yet. Run .\tools\build-debug.ps1 first.' }
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw 'ADB is missing. Install Android SDK Platform-Tools and add it to PATH.'
}
$devices = @(& adb devices | Select-String '\sdevice$')
if ($devices.Count -ne 1) {
    throw "Expected exactly one authorized Android phone. Found $($devices.Count). Check USB debugging and adb devices."
}
& adb install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'ADB installation failed.' }
Write-Host 'Installed debug APK. Test with synthetic data only.'
