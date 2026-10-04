param()
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$jdk = Join-Path $root '.tools/jdk/bin'
if (!(Test-Path -LiteralPath "$jdk/javac.exe")) {
    throw 'Run scripts/build.ps1 or scripts/setup-toolchain.ps1 first.'
}
$classes = Join-Path $root 'tests/build'
New-Item -ItemType Directory -Force $classes | Out-Null
$source = Join-Path $root 'app/src/main/java/de/mk2/bpm'
$files = @('TempoEstimator', 'AudioTempoDetector', 'LiveBeatTracker', 'BeatPulseClock') | ForEach-Object { "$source/$_.java" }
$tests = @('TempoEstimatorTest', 'AudioTempoDetectorTest', 'BeatPulseClockTest')
$files += $tests | ForEach-Object { "$root/tests/$_.java" }
& "$jdk/javac.exe" -encoding UTF-8 --release 8 -d $classes @files
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }
foreach ($test in $tests) {
    & "$jdk/java.exe" -cp $classes "de.mk2.bpm.$test"
    if ($LASTEXITCODE -ne 0) { throw "$test failed." }
}
