# Build the phone app without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Usage: powershell -ExecutionPolicy Bypass -File build_apk.ps1 [-VersionCode 2 -VersionName 1.1]
# The signing key (android\release.keystore + signing.txt) is created on the first build. Keep it:
# the phone only accepts updates signed with the same key.
param(
    [int]$VersionCode = 1,
    [string]$VersionName = "1.0",
    [string]$Tools = (Join-Path $HOME "AndroidBuild")
)
$ErrorActionPreference = "Stop"
$env:JAVA_HOME = (Get-ChildItem "$Tools\jdk-*" -Directory | Select-Object -First 1).FullName
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$BT = "$Tools\sdk\build-tools\35.0.0"
$Jar = "$Tools\sdk\platforms\android-35\android.jar"
$Root = $PSScriptRoot
$A = Join-Path $Root "android"
$Out = Join-Path $A "build"
if (Test-Path $Out) { Get-ChildItem $Out | Remove-Item -Recurse -Force }
New-Item -ItemType Directory -Force "$Out\gen", "$Out\classes", "$Out\dex" | Out-Null

function Run($exe, [string[]]$argv) {
    & $exe @argv
    if ($LASTEXITCODE -ne 0) { throw "failed: $exe" }
}

Run "$BT\aapt2.exe" @("compile", "--dir", "$A\res", "-o", "$Out\res.zip")
Run "$BT\aapt2.exe" @("link", "-I", $Jar, "--manifest", "$A\AndroidManifest.xml", "--java", "$Out\gen",
    "--min-sdk-version", "29", "--target-sdk-version", "34",
    "--version-code", "$VersionCode", "--version-name", $VersionName,
    "-o", "$Out\unsigned.apk", "$Out\res.zip")
$src = Get-ChildItem "$A\src", "$Out\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
Run "javac" (@("-nowarn", "-Xlint:-options", "-encoding", "UTF-8", "--release", "8", "-classpath", $Jar, "-d", "$Out\classes") + $src)
$classes = Get-ChildItem "$Out\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
Run "$BT\d8.bat" (@("--release", "--min-api", "29", "--lib", $Jar, "--output", "$Out\dex") + $classes)
python -c "import zipfile,sys; z=zipfile.ZipFile(sys.argv[1],'a',zipfile.ZIP_DEFLATED); z.write(sys.argv[2],'classes.dex'); z.close()" "$Out\unsigned.apk" "$Out\dex\classes.dex"
if ($LASTEXITCODE -ne 0) { throw "zip failed" }
Run "$BT\zipalign.exe" @("-p", "-f", "4", "$Out\unsigned.apk", "$Out\aligned.apk")
$ks = Join-Path $A "release.keystore"
$pwFile = Join-Path $A "signing.txt"
if (-not (Test-Path $ks)) {
    $pw = -join ((48..57) + (65..90) + (97..122) | Get-Random -Count 24 | ForEach-Object { [char]$_ })
    Set-Content -Path $pwFile -Value $pw -Encoding ascii
    Run "keytool" @("-genkeypair", "-keystore", $ks, "-alias", "podmic", "-keyalg", "RSA", "-keysize", "2048",
        "-validity", "36500", "-storepass", $pw, "-keypass", $pw, "-dname", "CN=PodMic")
}
$pw = (Get-Content $pwFile -Raw).Trim()
$apk = Join-Path $Root "PodMic.apk"
Run "$BT\apksigner.bat" @("sign", "--ks", $ks, "--ks-pass", "pass:$pw", "--key-pass", "pass:$pw", "--out", $apk, "$Out\aligned.apk")
Set-Content -Path (Join-Path $Root "PodMic.version") -Value $VersionCode -Encoding ascii   # the PC app tells phones about it
Write-Host ("OK: {0} ({1:N0} KB)" -f $apk, ((Get-Item $apk).Length / 1KB))
