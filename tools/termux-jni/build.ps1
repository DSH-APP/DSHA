param([string]$Ndk = $env:ANDROID_NDK_HOME, [ValidateSet(23,26)][int]$MinApi = 23)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# 校验固定的上游 PTY，生成创建身份握手补丁并编译；不调用 Gradle，不下载工具。
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$ndkVersion = '26.3.11579264'
if (-not $Ndk) {
    $candidates = @(
        "F:/DSHA/_toolchains/android-sdk/ndk/$ndkVersion",
        "$env:LOCALAPPDATA/Android/Sdk/ndk/$ndkVersion"
    )
    if ($env:ANDROID_SDK_ROOT) { $candidates += "$env:ANDROID_SDK_ROOT/ndk/$ndkVersion" }
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath "$candidate/source.properties") { $Ndk = $candidate; break }
    }
}
if (-not $Ndk) { throw '未找到 NDK r26d；请用 -Ndk 指定已有 NDK，不会自动下载。' }
$toolBin = Join-Path $Ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin'
foreach ($tool in @('clang.exe', 'llvm-strip.exe', 'llvm-readelf.exe', 'llvm-nm.exe')) {
    if (-not (Test-Path -LiteralPath "$toolBin/$tool")) { throw "缺少工具：$toolBin/$tool" }
}

# Git 在 Windows 上可能转换换行，因此对统一为 LF 的原文校验。
$sourceFile = Join-Path $PSScriptRoot 'termux.c'
$sourceText = [IO.File]::ReadAllText($sourceFile).Replace("`r`n", "`n")
$sha = [Security.Cryptography.SHA256]::Create()
try { $sourceHash = [BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($sourceText))).Replace('-', '').ToLowerInvariant() }
finally { $sha.Dispose() }
if ($sourceHash -ne '729112f2e66cdddb7e7311ddf8a89ad54037a54bddbf4d5291f2e1fff5b97373') {
    throw 'termux.c 与固定的 v0.118.0 原版源码不一致，停止构建。'
}

$buildDir = Join-Path $repoRoot 'app/build/termux-jni'
# 每次使用独立的链接输出，避免并行打包或扫描程序锁住上一次的中间文件。
$output = Join-Path $buildDir ("libtermux-" + [Guid]::NewGuid().ToString('N') + '.so')
$destination = Join-Path $repoRoot 'app/src/main/jniLibs/arm64-v8a/libtermux.so'
New-Item -ItemType Directory -Path $buildDir -Force | Out-Null
# 只在构建目录生成补丁版；固定锚点必须各出现一次，避免上游变化后静默漏补。
$patches = @(
    @('#include <dirent.h>', "#include <dirent.h>`n#include `"dsha-pty.h`""),
    @('    pid_t pid = fork();', "    int identity_pair[2];`n    if (dsha_pty_prepare(identity_pair) != 0) {`n        close(ptm);`n        return throw_runtime_exception(env, `"Cannot prepare PTY identity handshake`");`n    }`n    pid_t pid = fork();"),
    @('        return throw_runtime_exception(env, "Fork failed");', "        close(identity_pair[0]); close(identity_pair[1]); close(ptm);`n        return throw_runtime_exception(env, `"Fork failed`");"),
    @('        *pProcessId = (int) pid;', "        if (!dsha_pty_parent(env, identity_pair, pid)) {`n            close(ptm);`n            if ((*env)->ExceptionCheck(env)) return -1;`n            return throw_runtime_exception(env, `"Cannot confirm PTY identity before exec`");`n        }`n        *pProcessId = (int) pid;"),
    @('        setsid();', "        if (setsid() < 0) _exit(125);`n        dsha_pty_child(identity_pair);"),
    @('    int* pProcId = (int*) (*env)->GetPrimitiveArrayCritical(env, processIdArray, NULL);', "    if ((*env)->ExceptionCheck(env)) return -1;`n    int* pProcId = (int*) (*env)->GetPrimitiveArrayCritical(env, processIdArray, NULL);")
)
foreach ($patch in $patches) {
    if ([regex]::Matches($sourceText, [regex]::Escape($patch[0])).Count -ne 1) { throw 'PTY 身份握手补丁锚点不唯一。' }
    $sourceText = $sourceText.Replace($patch[0], $patch[1])
}
$patchedSource = Join-Path $buildDir 'termux-patched.c'
[IO.File]::WriteAllText($patchedSource, $sourceText, [Text.UTF8Encoding]::new($false))
$clangArgs = @(
    "--target=aarch64-linux-android$MinApi", '-std=gnu11', '-shared', '-fPIC',
    '-O2', '-g0', '-fvisibility=hidden', '-fstack-protector-strong', '-D_FORTIFY_SOURCE=2',
    '-ffunction-sections', '-fdata-sections', '-Wall', '-Wextra', '-Werror=format-security',
    '-Wl,--no-undefined', '-Wl,--fatal-warnings', '-Wl,--gc-sections', '-Wl,--build-id=sha1',
    '-Wl,-z,relro', '-Wl,-z,now', '-Wl,-soname,libtermux.so',
    # 保留 16 KB LOAD 对齐，但 RELRO 按 4 KB 收尾，避免旧 linker 对未映射空洞 mprotect 报 ENOMEM。
    '-Wl,-z,max-page-size=16384', '-Wl,-z,common-page-size=4096',
    '-I', $PSScriptRoot, $patchedSource, (Join-Path $PSScriptRoot 'dsha-process.c'),
    (Join-Path $PSScriptRoot 'dsha-pty.c'), '-o', $output
)
& "$toolBin/clang.exe" @clangArgs
if ($LASTEXITCODE -ne 0) { throw 'JNI 编译失败。' }
& "$toolBin/llvm-strip.exe" --strip-unneeded $output
if ($LASTEXITCODE -ne 0) { throw 'JNI strip 失败。' }

# 在交付到 jniLibs 前检查真实 LOAD 段，不修改 ELF 头。
$headers = & "$toolBin/llvm-readelf.exe" --program-headers --wide $output
if ($LASTEXITCODE -ne 0) { throw 'ELF 读取失败。' }
$loads = @($headers | Where-Object { $_ -match '^\s*LOAD\s' })
if ($loads.Count -eq 0) { throw 'ELF 没有 LOAD 段。' }
foreach ($load in $loads) {
    $fields = $load.Trim() -split '\s+'
    $offset = [Convert]::ToUInt64($fields[1].Substring(2), 16)
    $address = [Convert]::ToUInt64($fields[2].Substring(2), 16)
    $alignment = [Convert]::ToUInt64($fields[-1].Substring(2), 16)
    if ($alignment -lt 16384 -or ($offset % 16384) -ne ($address % 16384)) {
        throw "ELF 不满足 16KB 对齐：$load"
    }
}
# Android 6 的 4 KB linker 与新系统的 16 KB 映射都必须覆盖整个 RELRO 范围。
$relro = @($headers | Where-Object { $_ -match '^\s*GNU_RELRO\s' })
if ($relro.Count -ne 1) { throw 'ELF 必须有一个 RELRO 段。' }
$relroFields = $relro[0].Trim() -split '\s+'
$relroStart = [Convert]::ToUInt64($relroFields[2].Substring(2), 16)
$relroEnd = $relroStart + [Convert]::ToUInt64($relroFields[5].Substring(2), 16)
foreach ($page in @(4096, 16384)) {
    $begin = [Math]::Floor($relroStart / $page) * $page
    $end = [Math]::Ceiling($relroEnd / $page) * $page
    for ($address = $begin; $address -lt $end; $address += $page) {
        $mapped = $false
        foreach ($load in $loads) {
            $fields = $load.Trim() -split '\s+'
            $start = [Convert]::ToUInt64($fields[2].Substring(2), 16)
            $stop = $start + [Convert]::ToUInt64($fields[5].Substring(2), 16)
            if ($address -ge [Math]::Floor($start / $page) * $page -and $address -lt [Math]::Ceiling($stop / $page) * $page) { $mapped = $true; break }
        }
        if (-not $mapped) { throw "RELRO 在 $page 字节页映射中存在空洞。" }
    }
}
$symbols = & "$toolBin/llvm-nm.exe" --dynamic --defined-only --format=posix $output
if ($LASTEXITCODE -ne 0) { throw 'JNI 符号读取失败。' }
$actual = @($symbols | ForEach-Object { ($_ -split '\s+')[0] } | Sort-Object)
$expected = @(@('close', 'createSubprocess', 'setPtyUTF8Mode', 'setPtyWindowSize', 'waitFor') |
    ForEach-Object { "Java_com_termux_terminal_JNI_$_" }) + 'Java_com_deepseekharness_app_runtime_NativeProcess_sessionId'
$expected = @($expected | Sort-Object)
if (@(Compare-Object $expected $actual).Count -ne 0) { throw 'JNI 导出集合不符合 v0.118.0。' }
New-Item -ItemType Directory -Path (Split-Path -Parent $destination) -Force | Out-Null
$sameOutput = (Test-Path -LiteralPath $destination) -and
    ((Get-FileHash -Algorithm SHA256 -LiteralPath $output).Hash -eq
     (Get-FileHash -Algorithm SHA256 -LiteralPath $destination).Hash)
if (-not $sameOutput) { Copy-Item -LiteralPath $output -Destination $destination -Force }
Remove-Item -LiteralPath $output
Get-Content -LiteralPath (Join-Path $Ndk 'source.properties') | Select-String 'Pkg.Revision'
$loads
$actual
Get-FileHash -Algorithm SHA256 -LiteralPath $destination
