param([string]$Sdk = 'F:/DSHA/_toolchains/android-sdk')
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$bin=Join-Path $Sdk 'ndk/26.3.11579264/toolchains/llvm/prebuilt/windows-x86_64/bin'
$output=Join-Path $repo 'app/src/main/jniLibs/arm64-v8a/libdsha-session.so'
$flags=@('--target=aarch64-linux-android23','-std=c11','-D_GNU_SOURCE','-O2','-fPIE','-pie','-fstack-protector-strong','-D_FORTIFY_SOURCE=2','-Wl,-z,relro','-Wl,-z,now','-Wl,-z,max-page-size=16384','-Wl,-z,common-page-size=4096',(Join-Path $PSScriptRoot 'session-launcher.c'),'-o',$output)
& "$bin/clang.exe" @flags
if($LASTEXITCODE -ne 0){throw '独立会话启动器编译失败'}
& "$bin/llvm-strip.exe" --strip-unneeded $output
if($LASTEXITCODE -ne 0){throw '独立会话启动器 strip 失败'}
& "$bin/llvm-readelf.exe" --program-headers --wide $output
if($LASTEXITCODE -ne 0){throw '独立会话启动器 ELF 检查失败'}
