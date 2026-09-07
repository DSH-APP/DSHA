param(
    [string]$Sdk = 'F:/DSHA/_toolchains/android-sdk',
    [string]$Cache = 'F:/DSHA/_toolchains/gradle-user-home/caches',
    [string]$Jdk = 'F:/DSHA/_toolchains/jdk-17',
    [ValidateSet('standard', 'low')][string]$Flavor = 'standard'
)
# 只做本地 javac 编译，不调用 Gradle、不打包 APK，也不执行任何设备命令。
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $root
$runId = [Guid]::NewGuid().ToString('N')
$output = Join-Path $root "build/update-flow-selftest/$runId"
New-Item -ItemType Directory -Path $output -Force | Out-Null
$android = Join-Path $Sdk 'platforms/android-37.0/android.jar'
$compiled = Join-Path $root "app/build/intermediates/javac/${Flavor}Debug/compile$($Flavor.Substring(0,1).ToUpper()+$Flavor.Substring(1))DebugJavaWithJavac/classes"
$resources = Join-Path $root "app/build/intermediates/compile_r_class_jar/${Flavor}Debug/generate$($Flavor.Substring(0,1).ToUpper()+$Flavor.Substring(1))DebugRFile/R.jar"
if (!(Test-Path -LiteralPath $compiled) -or !(Test-Path -LiteralPath $resources)) { throw '需要已有的对应 Debug 编译产物作依赖；本脚本不会触发构建' }
$jars = @(Get-ChildItem -LiteralPath "$Cache/9.3.1/transforms" -Recurse -Filter classes.jar | Select-Object -ExpandProperty FullName)
$jars += @(Get-ChildItem -LiteralPath "$Cache/modules-2/files-2.1" -Recurse -Filter '*.jar' |
    Where-Object FullName -Match 'lifecycle-common|annotation-jvm|annotation-[0-9]|kotlin-stdlib|core-common' | Select-Object -ExpandProperty FullName)
$classpath = (@($android, $compiled, $resources) + $jars) -join ';'
$main = 'app/src/main/java/com/deepseekharness/app'
$audit = 'app/src/debug/java/com/deepseekharness/app/core/UpdateFlowAuditInstrumentation.java'
$testRoot = 'app/src/androidTest/java/com/deepseekharness/app'
function Compile-Flow([string]$name, [string[]]$sources) {
    $argsFile = Join-Path $output "$name.args"
    $arguments = @('-encoding','UTF-8','-source','17','-target','17','-proc:none','-implicit:none',
        '-classpath', ('"'+$classpath.Replace('\','/')+'"'), '-d', ('"'+$output.Replace('\','/')+'/'+$name+'"')) + $sources
    [IO.File]::WriteAllLines($argsFile, $arguments, [Text.UTF8Encoding]::new($false))
    & "$Jdk/bin/javac.exe" "@$argsFile"
    if ($LASTEXITCODE -ne 0) { throw "独立 javac 失败：$name" }
}
Compile-Flow 'compile' @("$main/core/UpdateEngine.java", "$main/core/UpdateRepository.java", "$main/util/UpdatePolicy.java",
    "$main/ui/UpdateActivity.java", "$main/ui/UpdateUi.java", "$main/ui/MainActivity.java", $audit,
    "$testRoot/core/UpdateFlowInstrumentation.java", "$testRoot/core/UpdateProcessInstrumentation.java",
    "$testRoot/core/OptimizationInstrumentation.java", "$testRoot/Rc13Instrumentation.java")
Write-Output 'PASS: 更新核心、两个 Activity、debug self-instrumentation 与 androidTest 夹具独立编译通过'
Write-Output "输出：$output"
