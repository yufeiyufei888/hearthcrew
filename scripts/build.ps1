param([string[]]$Tasks = @('build'), [string]$JavaHome)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $JavaHome) {
    $JavaHome = Join-Path $env:APPDATA '.minecraft\runtime\java-runtime-delta'
}
if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\javac.exe'))) {
    throw 'Java 21 JDK not found. Pass -JavaHome with a Java 21 JDK directory.'
}
$priorJava = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $JavaHome
    Push-Location -LiteralPath $projectRoot
    & (Join-Path $projectRoot 'gradlew.bat') @Tasks '--console=plain' ('-Dorg.gradle.java.installations.paths=' + $JavaHome)
    if ($LASTEXITCODE -ne 0) { throw ('Gradle exited ' + $LASTEXITCODE) }
} finally {
    Pop-Location
    $env:JAVA_HOME = $priorJava
}
