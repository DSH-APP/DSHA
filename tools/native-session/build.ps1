param([string]$Sdk = $env:ANDROID_HOME, [string]$Ndk = $env:ANDROID_NDK_HOME)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if (!$Ndk) {
    if (!$Sdk) { throw '需要 ANDROID_NDK_HOME、ANDROID_HOME 或 -Ndk/-Sdk' }
    $Ndk=Join-Path $Sdk 'ndk/26.3.11579264'
}
$output=Join-Path $repo 'app/src/main/jniLibs/arm64-v8a/libdsha-session.so'
$dshaPython=$env:DSHA_PYTHON
if (!$dshaPython) { $dshaPython='python3' }
& $dshaPython (Join-Path $PSScriptRoot 'build.py') --ndk $Ndk --output $output
if($LASTEXITCODE -ne 0){throw '独立会话启动器重编失败'}
