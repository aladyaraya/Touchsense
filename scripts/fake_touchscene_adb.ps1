# Test double only. Never use this script as evidence of a physical Android Gate.
if ($args.Count -ge 1 -and $args[0] -eq 'devices') {
  'List of devices attached'
  'MOCK-USB-001          device product:test model:FakePhone device:fake'
  exit 0
}

if ($args.Count -ge 5 -and $args[0] -eq '-s' -and $args[1] -eq 'MOCK-USB-001') {
  if ($args[2] -eq 'shell' -and $args[3] -eq 'getprop') {
    switch ($args[4]) {
      'ro.kernel.qemu' { '0' }
      'ro.product.model' { 'FakePhone' }
      'ro.product.manufacturer' { 'TouchSceneTest' }
      'ro.build.version.release' { '15' }
      'ro.build.version.sdk' { '35' }
      'ro.product.cpu.abi' { 'arm64-v8a' }
      default { exit 2 }
    }
    exit 0
  }
  if ($args[2] -eq 'logcat') {
    '1000.000 123 123 I TouchSceneTrace: {"event":"map"}'
    '1000.001 123 123 I OtherTag: unrelated'
    exit 0
  }
  if ($args[2] -eq 'shell' -and $args[3] -eq 'dumpsys') {
    "mock $($args[4]) status"
    exit 0
  }
  if ($args[2] -eq 'shell' -and $args[3] -eq 'ls') {
    'touchscene-test.jsonl'
    exit 0
  }
  if ($args[2] -eq 'pull') {
    Set-Content -LiteralPath (Join-Path $args[4] 'touchscene-test.jsonl') -Value '{"event":"map"}' -Encoding UTF8
    '1 file pulled'
    exit 0
  }
}

exit 2
