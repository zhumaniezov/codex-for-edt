param(
    [string]$MavenHome,
    [string]$JavaHome = 'C:\Program Files\Axiom\AxiomJDK-Pro-17-Full'
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (!$MavenHome) {
    $localMaven = Get-ChildItem -Path "$projectRoot\.tools\apache-maven-*\bin\mvn.cmd","$projectRoot\apache-maven-*-bin\apache-maven-*\bin\mvn.cmd" -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | Select-Object -First 1
    if ($localMaven) { $MavenHome = Split-Path -Parent (Split-Path -Parent $localMaven.FullName) }
}
$mavenCommand = if ($MavenHome) { Join-Path $MavenHome 'bin\mvn.cmd' } else {
    (Get-Command mvn.cmd -ErrorAction SilentlyContinue).Source
}
if (!$mavenCommand -or !(Test-Path -LiteralPath $mavenCommand)) {
    throw 'Apache Maven was not found. See docs/build.md. No software has been installed.'
}
if (!(Test-Path -LiteralPath "$JavaHome\bin\javac.exe")) { throw "JDK not found: $JavaHome" }
$previousJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $JavaHome
    Push-Location $projectRoot
    New-Item -ItemType Directory -Force -Path "$projectRoot\.runtime\logs" | Out-Null
    & $mavenCommand --batch-mode --show-version --no-transfer-progress `
        --settings "$projectRoot\.mvn\settings.xml" --global-settings "$projectRoot\.mvn\settings.xml" `
        "-Dmaven.repo.local=$projectRoot\.m2\repository" '-Dtycho.localArtifacts=ignore' clean verify `
        2>&1 | Tee-Object -FilePath "$projectRoot\.runtime\logs\maven-build.log"
    if ($LASTEXITCODE -ne 0) { throw "Maven/Tycho failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
    $env:JAVA_HOME = $previousJavaHome
}
