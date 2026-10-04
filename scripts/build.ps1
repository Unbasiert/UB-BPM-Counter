param([switch]$SkipSetup)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (!$SkipSetup) { & (Join-Path $PSScriptRoot 'setup-toolchain.ps1') }
$jdk = Join-Path $root '.tools/jdk/bin'
$bt = Join-Path $root '.tools/android-sdk/build-tools/36.0.0'
$androidJar = Join-Path $root '.tools/android-sdk/platforms/android-36/android.jar'
$build = Join-Path $root 'build'
$dist = Join-Path $root 'dist'
$signing = Join-Path $root '.signing'
$apk = Join-Path $dist 'UB-BPM-Counter-v1.2.4.apk'
if (!(Test-Path -LiteralPath "$root/app/src/main/java/de/mk2/bpm/MainActivity.java")) {
    throw 'MainActivity.java is missing. Refusing to produce an APK without its launch activity.'
}

function Invoke-Tool($Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Executable failed with exit code $LASTEXITCODE" }
}

# Only build/ is disposable. Resolve and check its absolute path before deletion.
if (Test-Path -LiteralPath $build) {
    $resolved = (Resolve-Path -LiteralPath $build).Path
    if ($resolved -ne (Join-Path $root 'build')) { throw 'Unexpected build directory' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
@($build, $dist, $signing, "$build/classes", "$build/gen", "$build/dex") | ForEach-Object {
    New-Item -ItemType Directory -Force $_ | Out-Null
}

Write-Host 'Compiling Android resources ...'
Invoke-Tool "$bt/aapt2.exe" @('compile', '--dir', "$root/app/src/main/res", '-o', "$build/resources.zip")
Invoke-Tool "$bt/aapt2.exe" @('link', '-o', "$build/resources.apk", '-I', $androidJar,
    '--manifest', "$root/app/src/main/AndroidManifest.xml", '--java', "$build/gen", "$build/resources.zip")

Write-Host 'Compiling Java and DEX ...'
$sources = @(Get-ChildItem -LiteralPath "$root/app/src/main/java", "$build/gen" -Filter '*.java' -Recurse | ForEach-Object FullName)
Invoke-Tool "$jdk/javac.exe" (@('-encoding', 'UTF-8', '--release', '8', '-classpath', $androidJar, '-d', "$build/classes") + $sources)
if (!(Test-Path -LiteralPath "$build/classes/de/mk2/bpm/MainActivity.class")) {
    throw 'Java compilation did not produce MainActivity.class.'
}
Invoke-Tool "$jdk/jar.exe" @('--create', '--file', "$build/classes.jar", '-C', "$build/classes", '.')
Invoke-Tool "$jdk/java.exe" @('-cp', "$bt/lib/d8.jar", 'com.android.tools.r8.D8', '--release', '--min-api', '23', '--lib', $androidJar, '--output', "$build/dex", "$build/classes.jar")

Copy-Item -LiteralPath "$build/resources.apk" -Destination "$build/unsigned.apk"
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$zip = [IO.Compression.ZipFile]::Open("$build/unsigned.apk", [IO.Compression.ZipArchiveMode]::Update)
try {
    $entry = [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$build/dex/classes.dex", 'classes.dex', [IO.Compression.CompressionLevel]::Optimal)
    $entry.LastWriteTime = [DateTimeOffset]::Parse('2026-09-30T00:00:00Z')
} finally { $zip.Dispose() }
Invoke-Tool "$bt/zipalign.exe" @('-f', '-p', '4', "$build/unsigned.apk", "$build/aligned.apk")

$keyStore = Join-Path $signing 'mk2-release.p12'
$passwordFile = Join-Path $signing 'keystore-password.txt'
if (!(Test-Path -LiteralPath $keyStore)) {
    if (!(Test-Path -LiteralPath $passwordFile)) {
        $bytes = New-Object byte[] 32
        $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
        [IO.File]::WriteAllText($passwordFile, [Convert]::ToBase64String($bytes))
    }
    Invoke-Tool "$jdk/keytool.exe" @('-genkeypair', '-noprompt', '-keystore', $keyStore, '-storetype', 'PKCS12', '-storepass:file', $passwordFile,
        '-alias', 'mk2-release', '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000', '-dname', 'CN=MK2 BPM local release,O=MK2 BPM,C=DE')
}
if (!(Test-Path -LiteralPath $passwordFile)) { throw 'Missing .signing/keystore-password.txt; restore it from your backup.' }

Write-Host 'Signing and verifying the APK ...'
Invoke-Tool "$jdk/java.exe" @('-jar', "$bt/lib/apksigner.jar", 'sign', '--ks', $keyStore, '--ks-key-alias', 'mk2-release', '--ks-pass', "file:$passwordFile",
    '--v1-signing-enabled', 'true', '--v2-signing-enabled', 'true', '--v3-signing-enabled', 'true', '--v4-signing-enabled', 'false', '--out', $apk, "$build/aligned.apk")
Invoke-Tool "$jdk/java.exe" @('-jar', "$bt/lib/apksigner.jar", 'verify', '--verbose', '--print-certs', $apk)
Invoke-Tool "$bt/zipalign.exe" @('-c', '-p', '4', $apk)
$hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText("$apk.sha256", "$hash  UB-BPM-Counter-v1.2.4.apk`n")
Write-Host "Built $apk ($((Get-Item -LiteralPath $apk).Length) bytes)"
