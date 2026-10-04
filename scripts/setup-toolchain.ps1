param()
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$toolsRoot = Join-Path $root '.tools'
$downloads = Join-Path $toolsRoot 'downloads'
New-Item -ItemType Directory -Force $downloads | Out-Null

function Get-VerifiedArchive($Name, $Url, $Sha256) {
    $archive = Join-Path $downloads $Name
    if (!(Test-Path -LiteralPath $archive)) {
        Write-Host "Downloading $Name ..."
        Invoke-WebRequest -Uri $Url -OutFile $archive
    }
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $Sha256) {
        throw "Checksum mismatch for $archive. Remove that archive and retry."
    }
    return $archive
}

function Expand-WithoutTopFolder($Archive, $Destination) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $base = [IO.Path]::GetFullPath($Destination) + [IO.Path]::DirectorySeparatorChar
    New-Item -ItemType Directory -Force $Destination | Out-Null
    $zip = [IO.Compression.ZipFile]::OpenRead($Archive)
    try {
        foreach ($entry in $zip.Entries) {
            $parts = $entry.FullName -split '/', 2
            if ($parts.Length -lt 2 -or !$parts[1]) { continue }
            $target = [IO.Path]::GetFullPath((Join-Path $Destination $parts[1]))
            if (!$target.StartsWith($base, [StringComparison]::OrdinalIgnoreCase)) {
                throw "Archive path is outside the tool directory: $($entry.FullName)"
            }
            if ($entry.FullName.EndsWith('/')) {
                New-Item -ItemType Directory -Force $target | Out-Null
            } else {
                New-Item -ItemType Directory -Force ([IO.Path]::GetDirectoryName($target)) | Out-Null
                [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
            }
        }
    } finally { $zip.Dispose() }
}

$jdk = Join-Path $toolsRoot 'jdk'
if (!(Test-Path -LiteralPath (Join-Path $jdk 'bin/javac.exe'))) {
    $archive = Get-VerifiedArchive 'jdk21.zip' 'https://aka.ms/download-jdk/microsoft-jdk-21.0.12.1-windows-x64.zip' '192441A9D27DA813BADA974BB88B4CF64D37A9589ED37F204374D411CA5CE07F'
    Expand-WithoutTopFolder $archive $jdk
}
$buildTools = Join-Path $toolsRoot 'android-sdk/build-tools/36.0.0'
if (!(Test-Path -LiteralPath (Join-Path $buildTools 'aapt2.exe'))) {
    $archive = Get-VerifiedArchive 'build-tools36.zip' 'https://dl.google.com/android/repository/build-tools_r36_windows.zip' 'AA1095CB14D83E483818A748A2C06FAAEB8E601561B06A356A119A1B2CA280D3'
    Expand-WithoutTopFolder $archive $buildTools
}
$platform = Join-Path $toolsRoot 'android-sdk/platforms/android-36'
if (!(Test-Path -LiteralPath (Join-Path $platform 'android.jar'))) {
    $archive = Get-VerifiedArchive 'platform36.zip' 'https://dl.google.com/android/repository/platform-36_r02.zip' '37607369A28C5B640B3A7998868D45898EBCB777565A0E85F9ACF36F29631D2E'
    Expand-WithoutTopFolder $archive $platform
}
Write-Host 'Portable JDK 21 + Android SDK 36 ready in .tools (no global installation).'
