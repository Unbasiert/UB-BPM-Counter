param(
    [string]$Serial = 'emulator-5556',
    [string]$Apk = 'dist/UB-BPM-Counter-v1.2.4.apk',
    [switch]$BrandingOnly
)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$jdk = Join-Path $root '.tools/jdk/bin'
$bt = Join-Path $root '.tools/android-sdk/build-tools/36.0.0'
$androidJar = Join-Path $root '.tools/android-sdk/platforms/android-36/android.jar'
$qaBuild = Join-Path $root '.tools/qa-runtime/test-build'
$adb = Join-Path $root '.tools/qa-runtime/platform-tools/adb.exe'
if (![IO.Path]::IsPathRooted($Apk)) { $Apk = Join-Path $root $Apk }
if (!(Test-Path -LiteralPath $Apk)) { throw "Release APK does not exist: $Apk" }
function Invoke-QaTool($Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Executable failed with exit code $LASTEXITCODE" }
}
@($qaBuild, "$qaBuild/classes", "$qaBuild/dex", "$root/tests/artifacts") | ForEach-Object {
    New-Item -ItemType Directory -Force $_ | Out-Null
}
Invoke-QaTool "$bt/aapt2.exe" @('link', '-o', "$qaBuild/resources.apk", '-I', $androidJar,
    '--manifest', "$root/tests/instrumentation/AndroidManifest.xml")
Invoke-QaTool "$jdk/javac.exe" @('-encoding', 'UTF-8', '--release', '8', '-classpath', $androidJar,
    '-d', "$qaBuild/classes", "$root/tests/instrumentation/InstrumentSmoke.java")
Invoke-QaTool "$jdk/jar.exe" @('--create', '--file', "$qaBuild/classes.jar", '-C', "$qaBuild/classes", '.')
Invoke-QaTool "$jdk/java.exe" @('-cp', "$bt/lib/d8.jar", 'com.android.tools.r8.D8', '--release', '--min-api', '23', '--lib', $androidJar,
    '--output', "$qaBuild/dex", "$qaBuild/classes.jar")
Copy-Item -LiteralPath "$qaBuild/resources.apk" -Destination "$qaBuild/unsigned.apk" -Force
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$zip = [IO.Compression.ZipFile]::Open("$qaBuild/unsigned.apk", [IO.Compression.ZipArchiveMode]::Update)
try {
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$qaBuild/dex/classes.dex", 'classes.dex', [IO.Compression.CompressionLevel]::Optimal) | Out-Null
} finally { $zip.Dispose() }
Invoke-QaTool "$bt/zipalign.exe" @('-f', '-p', '4', "$qaBuild/unsigned.apk", "$qaBuild/aligned.apk")
Invoke-QaTool "$jdk/java.exe" @('-jar', "$bt/lib/apksigner.jar", 'sign', '--ks', "$root/.signing/mk2-release.p12", '--ks-key-alias', 'mk2-release',
    '--ks-pass', "file:$root/.signing/keystore-password.txt", '--v4-signing-enabled', 'false', '--out', "$qaBuild/qa.apk", "$qaBuild/aligned.apk")
Invoke-QaTool $adb @('-s', $Serial, 'install', '-r', $Apk)
Invoke-QaTool $adb @('-s', $Serial, 'install', '-r', "$qaBuild/qa.apk")
# Start with a real runtime permission request; tests deny once, then allow in the Android dialog.
Invoke-QaTool $adb @('-s', $Serial, 'shell', 'pm', 'revoke', 'de.mk2.bpm', 'android.permission.RECORD_AUDIO')
Invoke-QaTool $adb @('-s', $Serial, 'shell', 'pm', 'clear-permission-flags', 'de.mk2.bpm', 'android.permission.RECORD_AUDIO', 'user-set', 'user-fixed')
Invoke-QaTool $adb @('-s', $Serial, 'shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
Invoke-QaTool $adb @('-s', $Serial, 'shell', 'wm', 'dismiss-keyguard')
Invoke-QaTool $adb @('-s', $Serial, 'shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0')
Invoke-QaTool $adb @('-s', $Serial, 'shell', 'settings', 'put', 'system', 'user_rotation', '0')
$instrumentArgs = @('-s', $Serial, 'shell', 'am', 'instrument', '-w')
if ($BrandingOnly) { $instrumentArgs += @('-e', 'branding', 'true') }
$instrumentArgs += 'de.mk2.bpm.qa/de.mk2.bpm.qa.InstrumentSmoke'
$output = & $adb @instrumentArgs 2>&1
$output | Tee-Object -FilePath "$root/tests/artifacts/android-instrumentation.txt"
& $adb -s $Serial shell test -d /sdcard/Android/data/de.mk2.bpm/files/qa
if ($LASTEXITCODE -eq 0) {
    Invoke-QaTool $adb @('-s', $Serial, 'pull', '/sdcard/Android/data/de.mk2.bpm/files/qa/.', "$root/tests/artifacts")
}
if (($output -join "`n") -notmatch 'PASS:') { throw 'Android instrumentation did not pass; see tests/artifacts/android-instrumentation.txt' }
