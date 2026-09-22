# Exercises the collector with a fake ADB executable, never a physical-device Gate.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$collector = Join-Path $projectRoot 'scripts/collect_touchscene_android_gate.ps1'
$fakeAdb = Join-Path $PSScriptRoot 'fake_touchscene_adb.ps1'
$testRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("TouchSceneGateSelfTest-$([guid]::NewGuid().ToString('N'))")
$evidenceDirectory = Join-Path $testRoot 'evidence'

New-Item -ItemType Directory -Path $testRoot | Out-Null
try {
  & $collector -AdbPath $fakeAdb -Serial 'MOCK-USB-001' -ApkPath $fakeAdb -OutputDirectory $evidenceDirectory
  $manifest = Get-Content -LiteralPath (Join-Path $evidenceDirectory 'manifest.json') -Raw | ConvertFrom-Json
  if ($manifest.device_serial -ne 'MOCK-USB-001') { throw 'Wrong mock serial in manifest.' }
  if ($manifest.model -ne 'FakePhone') { throw 'Wrong mock model in manifest.' }
  if ($manifest.touchscene_logcat_lines -ne 1) { throw 'Logcat filtering failed.' }
  if ($manifest.trace_pull_status -ne 'latest_trace_pulled') { throw 'Trace pull was not recorded.' }
  if ($manifest.gate_result -ne 'not_assessed_by_collector') { throw 'Collector incorrectly assessed a Gate.' }
  if ($manifest.apk_sha256 -ne (Get-FileHash -LiteralPath $fakeAdb -Algorithm SHA256).Hash) {
    throw 'APK hash field did not match the supplied test file.'
  }
  if (-not (Test-Path -LiteralPath (Join-Path $evidenceDirectory 'traces/touchscene-test.jsonl') -PathType Leaf)) {
    throw 'Trace file is absent despite a successful mock pull.'
  }
  $logLines = @(Get-Content -LiteralPath (Join-Path $evidenceDirectory 'touchscene-logcat.txt'))
  if ($logLines.Count -ne 1 -or $logLines[0] -notlike '*TouchSceneTrace*') {
    throw 'The collected log must contain only the TouchSceneTrace line.'
  }
  'Fake-ADB collector self-test passed. This is simulated evidence only.'
} finally {
  $resolvedRoot = [System.IO.Path]::GetFullPath($testRoot)
  $resolvedTemp = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
  if (-not $resolvedRoot.StartsWith($resolvedTemp, [System.StringComparison]::OrdinalIgnoreCase) -or
      -not (Split-Path -Leaf $resolvedRoot).StartsWith('TouchSceneGateSelfTest-')) {
    throw 'Refusing to clean up a path outside the dedicated self-test directory.'
  }
  Remove-Item -LiteralPath $resolvedRoot -Recurse -Force
}
