# Package the PC app for the laptop: PyInstaller folder + ffmpeg + phone APK -> PodMic-laptop.zip
# Usage: powershell -ExecutionPolicy Bypass -File build_pc.ps1
# The version number is PC_VERSION inside pc\podmic.pyw (raise it before building so laptops see the update).
# Keep this file ASCII-only: Windows PowerShell reads scripts without a BOM in the system code page.
$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot
$ver = [int]([regex]::Match((Get-Content "$Root\pc\podmic.pyw" -Raw -Encoding UTF8), 'PC_VERSION = (\d+)').Groups[1].Value)
$dist = Join-Path $env:TEMP "podmic_dist"
$work = Join-Path $env:TEMP "podmic_build"
if (Test-Path $dist) { Remove-Item $dist -Recurse -Force }
$ErrorActionPreference = "Continue"
python -m PyInstaller --noconfirm --clean --windowed --name "PodMic" --distpath $dist --workpath $work --specpath $work `
    --exclude-module torch --exclude-module scipy --exclude-module matplotlib --exclude-module pandas --exclude-module cv2 "$Root\pc\podmic.pyw" 2>&1 | Out-Null
$ErrorActionPreference = "Stop"
$d = Join-Path $dist "PodMic"
if (-not (Test-Path "$d\PodMic.exe")) { throw "PyInstaller failed" }
Copy-Item "$Root\PodMic.apk", "$Root\PodMic.version" $d
New-Item -ItemType Directory -Force "$d\ffmpeg" | Out-Null
Copy-Item (Join-Path (Split-Path (Get-Command ffmpeg).Source) '*') "$d\ffmpeg" -Include ffmpeg.exe, *.dll
$zip = Join-Path $Root "PodMic-laptop.zip"
if (Test-Path $zip) { Remove-Item $zip }
Compress-Archive -Path $d -DestinationPath $zip
Set-Content -Path (Join-Path $Root "PodMic-laptop.version") -Value $ver -Encoding ascii
Write-Host ("OK: v{0} {1} ({2:N0} MB)" -f $ver, $zip, ((Get-Item $zip).Length / 1MB))
