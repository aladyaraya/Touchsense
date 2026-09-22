param(
  [string]$AdbPath,
  [string]$Serial,
  [string]$ApkPath,
  [string]$OutputDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not $AdbPath) {
  $command = Get-Command adb -ErrorAction SilentlyContinue
  if (-not $command) {
    throw 'adb was not found. Pass -AdbPath with the Android SDK platform-tools adb.exe path.'
  }
  $AdbPath = $command.Source
}
if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
  throw "adb executable not found: $AdbPath"
}

$deviceLines = @(& $AdbPath devices -l 2>&1)
if ($LASTEXITCODE -ne 0) { throw 'adb devices failed; no evidence was written.' }
$activeSerials = @($deviceLines | ForEach-Object {
  if ([string]$_ -match '^(\S+)\s+device(?:\s|$)') { $Matches[1] }
})
$physicalSerials = @($activeSerials | Where-Object { $_ -notmatch '^emulator-' })
if ($physicalSerials.Count -eq 0) {
  throw 'No active physical Android device. Emulators and offline/unauthorized entries cannot satisfy the physical Gate.'
}
if ($Serial) {
  if ($Serial -notin $physicalSerials) { throw "Requested physical device $Serial is not active in adb devices -l." }
  $deviceSerial = $Serial
} elseif ($physicalSerials.Count -eq 1) {
  $deviceSerial = $physicalSerials[0]
} else {
  throw "Multiple physical Android devices are active: $($physicalSerials -join ', '). Pass -Serial explicitly."
}

function Get-DeviceProperty([string]$propertyName) {
  $value = @(& $AdbPath -s $deviceSerial shell getprop $propertyName 2>&1)
  if ($LASTEXITCODE -ne 0) { throw "adb getprop $propertyName failed." }
  return (($value -join "`n").Trim())
}

$isEmulator = Get-DeviceProperty 'ro.kernel.qemu'
if ($isEmulator -eq '1') {
  throw "$deviceSerial reports ro.kernel.qemu=1; refusing to label an emulator as a physical Gate."
}
$model = Get-DeviceProperty 'ro.product.model'
$manufacturer = Get-DeviceProperty 'ro.product.manufacturer'
$androidVersion = Get-DeviceProperty 'ro.build.version.release'
$sdkVersion = Get-DeviceProperty 'ro.build.version.sdk'
$abi = Get-DeviceProperty 'ro.product.cpu.abi'

$apkHash = $null
if ($ApkPath) {
  if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) { throw "APK not found: $ApkPath" }
  $apkHash = (Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash
}

$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $OutputDirectory) {
  $safeSerial = $deviceSerial -replace '[^A-Za-z0-9._-]', '_'
  $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
  $OutputDirectory = Join-Path $projectRoot "实际开发/test-reports/android-gate-$safeSerial-$stamp"
}
if (Test-Path -LiteralPath $OutputDirectory) {
  throw "Output directory already exists; refusing to overwrite evidence: $OutputDirectory"
}
New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
$reportDirectory = (Resolve-Path -LiteralPath $OutputDirectory).Path

$rawLogcat = @(& $AdbPath -s $deviceSerial logcat -d -v epoch 2>&1)
$logcatExit = $LASTEXITCODE
$traceLines = @($rawLogcat | Where-Object { [string]$_ -like '*TouchSceneTrace*' })
$traceLines | Set-Content -LiteralPath (Join-Path $reportDirectory 'touchscene-logcat.txt') -Encoding UTF8

$battery = @(& $AdbPath -s $deviceSerial shell dumpsys battery 2>&1)
$batteryExit = $LASTEXITCODE
$battery | Set-Content -LiteralPath (Join-Path $reportDirectory 'battery.txt') -Encoding UTF8
$thermal = @(& $AdbPath -s $deviceSerial shell dumpsys thermalservice 2>&1)
$thermalExit = $LASTEXITCODE
$thermal | Set-Content -LiteralPath (Join-Path $reportDirectory 'thermal.txt') -Encoding UTF8

$traceDirectory = '/sdcard/Android/data/com.insta360.kmpsdk.demo/files/touchscene-traces'
$traceNames = @(& $AdbPath -s $deviceSerial shell ls -t $traceDirectory 2>&1)
$traceListExit = $LASTEXITCODE
$latestTrace = @($traceNames | ForEach-Object { ([string]$_).Trim() } |
  Where-Object { $_ -match '^touchscene-[A-Za-z0-9._-]+\.jsonl$' } | Select-Object -First 1)
$tracePullStatus = 'not_available'
if ($traceListExit -eq 0 -and $latestTrace.Count -gt 0) {
  $traceOutput = Join-Path $reportDirectory 'traces'
  New-Item -ItemType Directory -Path $traceOutput | Out-Null
  & $AdbPath -s $deviceSerial pull "$traceDirectory/$($latestTrace[0])" $traceOutput | Out-Null
  $tracePullStatus = if ($LASTEXITCODE -eq 0) { 'latest_trace_pulled' } else { 'pull_failed' }
}

$manifest = [ordered]@{
  schema = 'touchscene-android-gate-evidence/v1'
  collected_at = (Get-Date).ToString('o')
  device_serial = $deviceSerial
  manufacturer = $manufacturer
  model = $model
  android_version = $androidVersion
  sdk_level = $sdkVersion
  cpu_abi = $abi
  reported_emulator = $isEmulator
  apk_path = $ApkPath
  apk_sha256 = $apkHash
  touchscene_logcat_lines = $traceLines.Count
  logcat_exit_code = $logcatExit
  battery_exit_code = $batteryExit
  thermal_exit_code = $thermalExit
  latest_trace_file = if ($latestTrace.Count -gt 0) { $latestTrace[0] } else { $null }
  trace_pull_status = $tracePullStatus
  gate_result = 'not_assessed_by_collector'
}
$manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $reportDirectory 'manifest.json') -Encoding UTF8
Write-Host "Collected read-only Android evidence in $reportDirectory"
Write-Host 'No Ace, haptic, TalkBack, performance, or user Gate was marked as passed.'
