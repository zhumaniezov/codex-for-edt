param(
    [string]$EdtHome = "$env:LOCALAPPDATA\1C\1cedtstart\installations\1C_EDT 2026.1\1cedt",
    [string]$JavaHome = 'C:\Program Files\Axiom\AxiomJDK-Pro-17-Full',
    [string]$PluginJar,
    [switch]$PrepareOnly,
    [switch]$Smoke,
    [switch]$Live,
    [string]$CodexExecutable
)
# Запускает установленную EDT с отдельной конфигурацией и рабочей областью.
# Исходная установка EDT при этом не изменяется.
$ErrorActionPreference = 'Stop'
if ($Live -and !$Smoke) { throw 'Параметр -Live используется вместе с -Smoke.' }
$projectRoot = Split-Path -Parent $PSScriptRoot
if (!$PluginJar) {
    $jar = Get-ChildItem -Path "$projectRoot\bundles\io.github.zhumaniezov.codex.edt\target\*.jar" -ErrorAction SilentlyContinue |
        Where-Object Name -NotMatch 'sources|javadoc' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($jar) { $PluginJar = $jar.FullName }
}
if (!$PluginJar -or !(Test-Path -LiteralPath $PluginJar)) {
    throw 'Build with scripts/build.ps1 first, or pass -PluginJar with the locally compiled bundle.'
}
if (!(Test-Path -LiteralPath "$EdtHome\configuration\config.ini")) { throw "EDT not found: $EdtHome" }
$runtimeName = if ($Smoke) { '.runtime\edt-smoke-' + (Get-Date -Format 'yyyyMMddHHmmss') } else { '.runtime\development' }
$runRoot = Join-Path $projectRoot $runtimeName
$configuration = Join-Path $runRoot 'configuration'
$utf8 = [System.Text.UTF8Encoding]::new($false)
New-Item -ItemType Directory -Force -Path $configuration,"$configuration\org.eclipse.equinox.simpleconfigurator","$runRoot\user-home","$runRoot\workspace","$runRoot\tmp" | Out-Null
$lines = @(Get-Content -LiteralPath "$EdtHome\configuration\org.eclipse.equinox.simpleconfigurator\bundles.info" |
    Where-Object { $_ -and !$_.StartsWith('#') -and !$_.StartsWith('io.github.zhumaniezov.codex.edt,') })
$active = @{}
foreach ($line in $lines) {
    $parts = $line.Split(',')
    $active[$parts[0]] = $parts[2]
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($PluginJar)
try {
    $reader = [IO.StreamReader]::new($zip.GetEntry('META-INF/MANIFEST.MF').Open())
    try { $manifest = $reader.ReadToEnd() } finally { $reader.Dispose() }
} finally { $zip.Dispose() }
$version = [regex]::Match($manifest, '(?m)^Bundle-Version: ([^\r\n]+)').Groups[1].Value
if (!$version) { throw 'Bundle-Version is missing' }
$lines += "io.github.zhumaniezov.codex.edt,$version,$(([Uri]([IO.Path]::GetFullPath($PluginJar))).AbsoluteUri),4,false"
if ($Smoke) {
    $testJar = Join-Path $projectRoot 'tests\io.github.zhumaniezov.codex.edt.tests\target\io.github.zhumaniezov.codex.edt.tests-0.2.0-SNAPSHOT.jar'
    if (!(Test-Path -LiteralPath $testJar)) { throw 'Build the test bundle before -Smoke' }
    $testZip = [IO.Compression.ZipFile]::OpenRead($testJar)
    try {
        $testReader = [IO.StreamReader]::new($testZip.GetEntry('META-INF/MANIFEST.MF').Open())
        try { $testManifest = $testReader.ReadToEnd() } finally { $testReader.Dispose() }
    } finally { $testZip.Dispose() }
    $testVersion = [regex]::Match($testManifest, '(?m)^Bundle-Version: ([^\r\n]+)').Groups[1].Value
    $lines += "io.github.zhumaniezov.codex.edt.tests,$testVersion,$(([Uri]$testJar).AbsoluteUri),4,false"
}
[IO.File]::WriteAllText("$configuration\org.eclipse.equinox.simpleconfigurator\bundles.info", "#version=1`n" + ($lines -join "`n") + "`n", $utf8)
# Сохраняем обязательные свойства EDT, заменяя пути изменяемых данных установки.
$configLines = @(Get-Content -LiteralPath "$EdtHome\configuration\config.ini" | Where-Object {
    $_ -notmatch '^(#|eclipse\.p2\.|osgi\.instance\.area|osgi\.splashPath|osgi\.bundles=|osgi\.framework\.extensions=|osgi\.configuration\.)'
})
$configLines += @(
    "osgi.bundles=reference:$($active['org.eclipse.equinox.simpleconfigurator'])@1:start"
    'osgi.configuration.cascaded=false'
    "eclipse.p2.data.area=$(([Uri](Join-Path $runRoot 'p2')).AbsoluteUri)"
    'eclipse.p2.profile=CodexDevelopment'
)
$extensions = @('org.eclipse.equinox.weaving.hook','org.eclipse.fx.osgi','org.eclipse.osgi.compatibility.state','org.eclipse.osgi.nl_ru') |
    Where-Object { $active.ContainsKey($_) } | ForEach-Object { 'reference:' + $active[$_] }
$configLines += 'osgi.framework.extensions=' + ($extensions -join ',')
[IO.File]::WriteAllText("$configuration\config.ini", ($configLines -join "`n") + "`n", $utf8)
$ini = @(Get-Content -LiteralPath "$EdtHome\1cedt.ini")
$vmStart = [Array]::IndexOf($ini, '-vmargs') + 1
$vm = @($ini[$vmStart..($ini.Length - 1)] | Where-Object {
    $_ -and $_ -notmatch '^-D(e1c\.dt\.monitoring\.host|osgi\.debug|user\.home)='
})
$vm += @("-Duser.home=$runRoot\user-home", "-Djava.io.tmpdir=$runRoot\tmp", "-Djna.tmpdir=$runRoot\tmp", '-De1c.dt.monitoring.host=')
if ($Smoke) { $vm += "-Dcodex.edt.smoke.result=$runRoot\result.txt" }
if ($Live) { $vm += '-Dcodex.edt.live=true' }
if ($CodexExecutable) { $vm += "-Dcodex.edt.executable=$CodexExecutable" }
$launch = @($vm) + @('-jar', ([Uri]$active['org.eclipse.equinox.launcher']).LocalPath, '-install', $EdtHome,
    '-configuration', $configuration, '-data', "$runRoot\workspace", '-product', 'com._1c.g5.v8.dt.product.application.rcp',
    '-application', 'org.eclipse.ui.ide.workbench', '-pluginCustomization', "$PSScriptRoot\development-preferences.ini", '-consoleLog', '-nosplash')
# Java @argfile устраняет проблемы кавычек оболочки и длины командной строки.
$argPath = Join-Path $runRoot 'launch.args'
$escaped = $launch | ForEach-Object { '"' + $_.Replace('\','/').Replace('"','\"') + '"' }
[IO.File]::WriteAllText($argPath, ($escaped -join "`n") + "`n", $utf8)
Write-Host "Workspace: $runRoot\workspace"
Write-Host "Launch arguments: $argPath"
if (!$PrepareOnly) {
    if ($Smoke) {
        & "$JavaHome\bin\java.exe" "@$argPath" *> "$runRoot\edt.log"
        if ($LASTEXITCODE -ne 0 -or !(Test-Path -LiteralPath "$runRoot\result.txt")) { throw "EDT smoke failed; see $runRoot\edt.log" }
        $result = Get-Content -LiteralPath "$runRoot\result.txt" -Raw
        if (!$result.StartsWith('PASS ')) { throw $result }
        Write-Host $result
        return
    }
    # Запуск интерактивного экземпляра разработки по команде пользователя.
    Start-Process -FilePath "$JavaHome\bin\javaw.exe" -ArgumentList ('"@' + $argPath + '"') -WindowStyle Hidden
}
