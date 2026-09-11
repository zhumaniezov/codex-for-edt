param(
    [string]$EdtHome = "$env:LOCALAPPDATA\1C\1cedtstart\installations\1C_EDT 2026.1\1cedt",
    [string]$JavaHome = 'C:\Program Files\Axiom\AxiomJDK-Pro-17-Full',
    [switch]$SkipSmoke
)
# Компилирует на активных библиотеках установленной EDT и запускает штатный p2 Publisher.
# Дополнительная проверка; полная сборка выполняется через Maven/Tycho.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$bundleId = 'io.github.zhumaniezov.codex.edt'
$testId = "$bundleId.tests"
$featureId = "$bundleId.feature"
$version = '0.5.0.v' + (Get-Date -Format 'yyyyMMddHHmmss')
$runRoot = Join-Path $projectRoot ".runtime\local-$version"
$configuration = Join-Path $runRoot 'configuration'
$repository = Join-Path $runRoot 'repository'
$utf8 = [System.Text.UTF8Encoding]::new($false)
$infoPath = Join-Path $EdtHome 'configuration\org.eclipse.equinox.simpleconfigurator\bundles.info'
if (!(Test-Path -LiteralPath $infoPath)) { throw "EDT bundles.info not found: $infoPath" }
if (!(Test-Path -LiteralPath "$JavaHome\bin\javac.exe")) { throw "JDK not found: $JavaHome" }
New-Item -ItemType Directory -Force -Path $runRoot,$configuration,$repository,"$runRoot\user-home","$runRoot\tmp" | Out-Null

function Write-Utf8([string]$Path, [string]$Content) {
    [System.IO.Directory]::CreateDirectory((Split-Path -Parent $Path)) | Out-Null
    [System.IO.File]::WriteAllText($Path, $Content, $utf8)
}

$bundleLines = @(Get-Content -LiteralPath $infoPath | Where-Object { $_ -and !$_.StartsWith('#') })
$installed = @{}
foreach ($line in $bundleLines) {
    $parts = $line.Split(',')
    $uri = [Uri]$parts[2]
    $path = if ($uri.IsAbsoluteUri) { $uri.LocalPath } else { Join-Path $EdtHome $parts[2] }
    if (!(Test-Path -LiteralPath $path)) { throw "Missing active EDT bundle: $path" }
    $installed[$parts[0]] = $path
}
$commonmark = Join-Path $projectRoot 'repositories\io.github.zhumaniezov.codex.edt.repository\target\repository\plugins\org.commonmark_0.30.0.jar'
if (!(Test-Path -LiteralPath $commonmark)) { throw 'Сначала выполните scripts/build.ps1: требуется библиотека CommonMark.' }
$installed['org.commonmark'] = $commonmark
$bundleLines = @($bundleLines | Where-Object { !$_.StartsWith('org.commonmark,') })
$bundleLines += "org.commonmark,0.30.0,$(([Uri]$commonmark).AbsoluteUri),4,false"
$classpath = ($installed.Values | Sort-Object) -join ';'
New-Item -ItemType Directory -Force -Path "$runRoot\source\plugins" | Out-Null
Copy-Item -LiteralPath $commonmark -Destination "$runRoot\source\plugins\org.commonmark_0.30.0.jar"
$builtJars = @{}
foreach ($item in @(@{ Id=$bundleId; Folder='bundles' }, @{ Id=$testId; Folder='tests' })) {
    $id = $item.Id
    $sourceRoot = Join-Path $projectRoot "$($item.Folder)\$id"
    $classes = Join-Path $runRoot "classes\$id"
    New-Item -ItemType Directory -Force -Path $classes | Out-Null
    $sourceFiles = @(Get-ChildItem -LiteralPath "$sourceRoot\src" -Recurse -Filter '*.java' | ForEach-Object FullName)
    $arguments = @('--release','17','-encoding','UTF-8','-Xlint:all','-classpath',('"' + $classpath.Replace('\','/') + '"'),'-d',('"' + $classes.Replace('\','/') + '"'))
    $arguments += $sourceFiles | ForEach-Object { '"' + $_.Replace('\','/') + '"' }
    $argFile = Join-Path $runRoot "$id.javac.args"
    Write-Utf8 $argFile ($arguments -join "`n")
    & "$JavaHome\bin\javac.exe" "@$argFile" 2>&1 | Tee-Object -FilePath "$runRoot\$id.compile.log"
    if ($LASTEXITCODE -ne 0) { throw "Compilation failed: $id" }
    # Ресурсы локализации поставляются рядом с классами, как при сборке Tycho.
    Get-ChildItem -LiteralPath "$sourceRoot\src" -Recurse -File | Where-Object Extension -NE '.java' | ForEach-Object {
        $relative = $_.FullName.Substring(("$sourceRoot\src\").Length)
        $destination = Join-Path $classes $relative
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
        Copy-Item -LiteralPath $_.FullName -Destination $destination
    }
    $manifest = (Get-Content -LiteralPath "$sourceRoot\META-INF\MANIFEST.MF" -Raw).Replace('0.5.0.qualifier', $version)
    $manifestPath = Join-Path $runRoot "$id.MF"
    Write-Utf8 $manifestPath $manifest
    $jarPath = Join-Path $runRoot "source\plugins\$id`_$version.jar"
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $jarPath) | Out-Null
    foreach ($resource in @('icons', 'plugin.properties', 'plugin_ru.properties')) {
        if (Test-Path -LiteralPath "$sourceRoot\$resource") { Copy-Item -LiteralPath "$sourceRoot\$resource" -Destination $classes -Recurse -Force }
    }
    & "$JavaHome\bin\jar.exe" --create --file $jarPath --manifest $manifestPath -C $classes . -C $sourceRoot plugin.xml
    if ($LASTEXITCODE -ne 0) { throw "Bundle packaging failed: $id" }
    $builtJars[$id] = $jarPath
    $classpath += ';' + $jarPath
}
# Тестовый bundle размещается вне каталога поставки.
$testJar = Join-Path $runRoot "$testId`_$version.jar"
$testSource = [IO.Path]::GetFullPath($builtJars[$testId])
$testDestination = [IO.Path]::GetFullPath($testJar)
if (!$testSource.StartsWith($runRoot + '\', [StringComparison]::OrdinalIgnoreCase) -or
    !$testDestination.StartsWith($runRoot + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe move target' }
Move-Item -LiteralPath $testSource -Destination $testDestination
$builtJars[$testId] = $testJar

$featureSource = Join-Path $runRoot 'feature'
$featureXml = (Get-Content -LiteralPath "$projectRoot\features\$featureId\feature.xml" -Raw).
    Replace('0.5.0.qualifier', $version).Replace('version="0.0.0"', "version=`"$version`"")
Write-Utf8 "$featureSource\feature.xml" $featureXml
New-Item -ItemType Directory -Force -Path "$runRoot\source\features" | Out-Null
& "$JavaHome\bin\jar.exe" --create --file "$runRoot\source\features\$featureId`_$version.jar" -C $featureSource feature.xml
if ($LASTEXITCODE -ne 0) { throw 'Feature packaging failed' }

# Для Publisher и тестового приложения достаточно Eclipse workbench без автозапуска БМ.
# Компиляция выше использует библиотеки точной установленной версии EDT.
# Полный продукт EDT запускается отдельной проверкой.
$runtimeLines = @($bundleLines | Where-Object { $_ -notmatch '^(com\._1c\.|com\.e1c\.|com\.1c\.|com\.company1c\.|org\.eclipse\.oomph\.|org\.eclipse\.egit\.)' })
foreach ($id in $builtJars.Keys) { $runtimeLines += "$id,$version,$(([Uri]$builtJars[$id]).AbsoluteUri),4,false" }
Write-Utf8 "$configuration\org.eclipse.equinox.simpleconfigurator\bundles.info" ("#version=1`n" + ($runtimeLines -join "`n") + "`n")
$configText = @(
    'osgi.bundles=reference:' + ([Uri]$installed['org.eclipse.equinox.simpleconfigurator']).AbsoluteUri + '@1:start'
    'osgi.bundles.defaultStartLevel=4'
    'osgi.framework=' + ([Uri]$installed['org.eclipse.osgi']).AbsoluteUri
    'org.eclipse.equinox.simpleconfigurator.configUrl=file:org.eclipse.equinox.simpleconfigurator/bundles.info'
    'osgi.configuration.cascaded=false'
    'eclipse.p2.data.area=' + ([Uri](Join-Path $runRoot 'p2')).AbsoluteUri
    'eclipse.p2.profile=CodexLocalVerification'
)
Write-Utf8 "$configuration\config.ini" (($configText -join "`n") + "`n")
$javaArgs = @("-Duser.home=$runRoot\user-home", "-Djava.io.tmpdir=$runRoot\tmp", "-Djna.tmpdir=$runRoot\tmp", '-De1c.dt.monitoring.host=', '-Xmx2g', '--add-opens=java.base/java.lang=ALL-UNNAMED',
    '-jar', $installed['org.eclipse.equinox.launcher'], '-install', $EdtHome,
    '-configuration', $configuration, '-data', "$runRoot\workspace", '-pluginCustomization', "$PSScriptRoot\development-preferences.ini", '-consoleLog', '-nosplash')
$repositoryUri = ([Uri]$repository).AbsoluteUri

& "$JavaHome\bin\java.exe" @javaArgs -application org.eclipse.equinox.p2.publisher.FeaturesAndBundlesPublisher `
    -metadataRepository $repositoryUri -artifactRepository $repositoryUri -source "$runRoot\source" -publishArtifacts `
    2>&1 | Tee-Object -FilePath "$runRoot\publisher.log"
if ($LASTEXITCODE -ne 0) { throw 'p2 FeaturesAndBundlesPublisher failed' }
& "$JavaHome\bin\java.exe" @javaArgs -application org.eclipse.equinox.p2.publisher.CategoryPublisher `
    -metadataRepository $repositoryUri -categoryDefinition ([Uri]"$projectRoot\repositories\$bundleId.repository\category.xml").AbsoluteUri `
    2>&1 | Tee-Object -FilePath "$runRoot\category.log"
if ($LASTEXITCODE -ne 0) { throw 'p2 CategoryPublisher failed' }

foreach ($name in @('content.xml','artifacts.xml')) {
    [xml]$document = Get-Content -LiteralPath "$repository\$name" -Raw
    if (!$document.DocumentElement) { throw "Invalid p2 metadata: $name" }
}
if (Select-String -LiteralPath "$repository\content.xml" -Pattern ([regex]::Escape($testId)) -Quiet) { throw 'Test bundle leaked into p2' }
$archive = "$runRoot\codex-edt-$version-local.zip"
Compress-Archive -Path "$repository\*" -DestinationPath $archive
Write-Host "Local p2 archive: $archive"

if (!$SkipSmoke) {
    & "$JavaHome\bin\java.exe" @javaArgs -application "$testId.smoke" `
        2>&1 | Tee-Object -FilePath "$runRoot\smoke.log"
    if ($LASTEXITCODE -ne 0) { throw 'Eclipse UI smoke test failed. See smoke.log.' }
}
Write-Utf8 "$projectRoot\.runtime\last-local-check.txt" $runRoot
Write-Host "Local verification complete: $runRoot"
