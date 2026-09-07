param([string]$ToolchainRoot = 'F:\DSHA\_toolchains')
$ErrorActionPreference = 'Stop'
$bridgeRoot = Split-Path -Parent $PSScriptRoot
$bridgeOutput = Join-Path $bridgeRoot 'build/bridge-verification/logic-classes'
New-Item -ItemType Directory -Path $bridgeOutput -Force | Out-Null
$bridgeCache = Join-Path $ToolchainRoot 'gradle-user-home/caches/modules-2/files-2.1'
$junit = @(Get-ChildItem -LiteralPath (Join-Path $bridgeCache 'junit/junit/4.13.2') -Recurse -Filter '*.jar')[0].FullName
$hamcrest = @(Get-ChildItem -LiteralPath (Join-Path $bridgeCache 'org.hamcrest/hamcrest-core/1.3') -Recurse -Filter '*.jar')[0].FullName
$bridgeNames = @('BoundedProcessRunner', 'BridgeLifecycle', 'BridgeQuestions', 'ForegroundReference', 'BoundedUiCall')
$bridgeSources = foreach ($bridgeName in $bridgeNames) {
    Join-Path $bridgeRoot "app/src/main/java/com/deepseekharness/app/util/$bridgeName.java"
    Join-Path $bridgeRoot "app/src/test/java/com/deepseekharness/app/util/${bridgeName}Test.java"
}
# 只调用本地 JDK 与缓存 JUnit；不运行 Gradle、不下载依赖、不连接设备。
& (Join-Path $ToolchainRoot 'jdk-17/bin/javac.exe') -encoding UTF-8 -source 17 -target 17 -classpath "$junit;$hamcrest" -d $bridgeOutput @bridgeSources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$bridgeTests = $bridgeNames | ForEach-Object { "com.deepseekharness.app.util.${_}Test" }
& (Join-Path $ToolchainRoot 'jdk-17/bin/java.exe') -classpath "$bridgeOutput;$junit;$hamcrest" org.junit.runner.JUnitCore @bridgeTests
exit $LASTEXITCODE
