param(
  [string]$Port,
  [string]$Fqbn = 'esp32:esp32:esp32c3',
  [switch]$EnableSensor,
  [int]$ReadyTimeoutSeconds = 15
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$sketchPath = Join-Path $projectRoot 'firmware\touchpuck_ble'
$cliCommand = Get-Command arduino-cli -ErrorAction SilentlyContinue
$cliPath = if ($cliCommand) { $cliCommand.Source } else { 'C:\Program Files\Arduino CLI\arduino-cli.exe' }

if (-not (Test-Path -LiteralPath $cliPath -PathType Leaf)) {
  throw 'arduino-cli was not found. Install Arduino CLI or add it to PATH.'
}

if (-not $Port) {
  $usbPorts = @(Get-PnpDevice -Class Ports -ErrorAction SilentlyContinue |
    Where-Object { $_.Status -eq 'OK' -and $_.InstanceId -like 'USB\*' } |
    ForEach-Object {
      if ($_.FriendlyName -match '\((COM\d+)\)') { $Matches[1] }
    } |
    Where-Object { $_ } |
    Select-Object -Unique)

  if ($usbPorts.Count -eq 0) {
    throw 'No active USB serial port found. Connect the ESP32-C3 with a data cable. Bluetooth COM ports are never auto-selected.'
  }
  if ($usbPorts.Count -gt 1) {
    throw "Multiple USB serial ports found: $($usbPorts -join ', '). Select the ESP32 with -Port COMx."
  }
  $Port = $usbPorts[0]
}

if ($Port -notmatch '^COM\d+$') { throw "Invalid serial port name: $Port" }
$connectedUsbPort = Get-PnpDevice -Class Ports -ErrorAction SilentlyContinue |
  Where-Object { $_.Status -eq 'OK' -and $_.InstanceId -like 'USB\*' -and $_.FriendlyName -match "\($([regex]::Escape($Port))\)" }
if (-not $connectedUsbPort) {
  throw "$Port is not an active USB serial port. Refusing to flash a Bluetooth or disconnected port."
}

$compileArgs = @('compile', '--fqbn', $Fqbn)
if ($EnableSensor) {
  $compileArgs += @('--build-property', 'compiler.cpp.extra_flags=-DENABLE_VIBRATION_SENSOR=1')
}
$compileArgs += $sketchPath

Write-Host "[1/3] Compiling TouchPuck firmware (sensor enabled: $($EnableSensor.IsPresent))"
& $cliPath @compileArgs
if ($LASTEXITCODE -ne 0) { throw "Compile failed with exit code $LASTEXITCODE" }

Write-Host "[2/3] Uploading to $Port"
& $cliPath upload --port $Port --fqbn $Fqbn $sketchPath
if ($LASTEXITCODE -ne 0) { throw "Upload failed with exit code $LASTEXITCODE" }

Write-Host '[3/3] Waiting for the serial ready marker. Press RESET once if the board does not auto-reset.'
$serial = [System.IO.Ports.SerialPort]::new($Port, 115200)
$serial.NewLine = "`n"
$serial.ReadTimeout = 500
$ready = $false
$deadline = [DateTime]::UtcNow.AddSeconds($ReadyTimeoutSeconds)
try {
  $serial.Open()
  while ([DateTime]::UtcNow -lt $deadline -and -not $ready) {
    try {
      $line = $serial.ReadLine().Trim()
      if ($line) { Write-Host "  $line" }
      if ($line -eq 'TOUCHPUCK_BLE_READY') { $ready = $true }
    } catch [System.TimeoutException] { }
  }
} finally {
  if ($serial.IsOpen) { $serial.Close() }
  $serial.Dispose()
}

if (-not $ready) {
  throw "Upload completed, but TOUCHPUCK_BLE_READY was not received within $ReadyTimeoutSeconds seconds. Check the cable, port lock, and reset state."
}

Write-Host 'TouchPuck is ready. Open the Demo in Android Chrome, connect TouchPuck-Haptic, and run the 100-point hardware acceptance test.'
