$ErrorActionPreference = 'Stop'
$workspace = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../../..')).Path
Set-Location -LiteralPath $workspace
$jdk = 'F:/DSHA/_toolchains/jdk-17/bin'
$cache = 'C:/Users/18768/.gradle/caches/modules-2/files-2.1'
$jars = foreach ($spec in @(
    @('junit/junit/4.13.2', 'junit-4.13.2.jar'),
    @('org.hamcrest/hamcrest-core/1.3', 'hamcrest-core-1.3.jar'),
    @('com.google.code.gson/gson/2.13.1', 'gson-2.13.1.jar')
)) {
    $matches = @(Get-ChildItem -LiteralPath (Join-Path $cache $spec[0]) -Recurse -Filter $spec[1])
    if ($matches.Count -ne 1) { throw ('Exact cached dependency unavailable: ' + $spec[1]) }
    $matches[0].FullName
}
$classpath = $jars -join ';'
$output = Join-Path $workspace 'app/build/build156-plugins-junit'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$sources = @(
    'app/src/test/java/com/deepseekharness/app/util/ManagedPatchChainTest.java',
    'app/src/test/java/com/deepseekharness/app/util/ManagedPatchRegistryIntegrationTest.java',
    'app/src/test/java/com/deepseekharness/app/backup/PluginRestoreGraphTest.java',
    'app/src/test/java/com/deepseekharness/app/util/GuestPathsTest.java'
)
& (Join-Path $jdk 'javac.exe') --release 17 -encoding UTF-8 -sourcepath 'app/src/main/java;app/src/test/java;app/build/generated/uiLanguage' -cp $classpath -d $output @sources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& (Join-Path $jdk 'java.exe') -cp ($output + ';' + $classpath) org.junit.runner.JUnitCore com.deepseekharness.app.util.ManagedPatchChainTest com.deepseekharness.app.util.ManagedPatchRegistryIntegrationTest com.deepseekharness.app.backup.PluginRestoreGraphTest com.deepseekharness.app.util.GuestPathsTest
exit $LASTEXITCODE
