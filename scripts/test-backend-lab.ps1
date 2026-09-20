param(
    [Parameter(Mandatory=$true)][ValidatePattern('^[A-Za-z0-9-]{1,64}$')][string]$RunId,
    [ValidateSet('base','contracts','limits-candidate','limits-door','limits-output', 'lifecycle', 'excavation', 'journal', 'safety', 'preparation', 'preparation-empty', 'containers', 'orders', 'host', 'navigation', 'processing', 'compare-legacy', 'compare-numen')][string]$Suite='base',
    [string]$JavaHome = (Join-Path $env:APPDATA '.minecraft\runtime\java-runtime-delta'),
    [string]$DependencyBundle
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$evidence = Join-Path $projectRoot '.artifacts\backend-v03'
$runDir = Join-Path $projectRoot ".runtime\backend-v03\runs\$RunId"
$log = Join-Path $evidence "$RunId.log"
if ((Test-Path -LiteralPath $runDir) -or (Test-Path -LiteralPath $log)) { throw 'Run id already used; preserve prior evidence' }
& (Join-Path $PSScriptRoot 'prepare-backend-lab.ps1')
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
$priorJavaHome = $env:JAVA_HOME
$priorGradleHome = $env:GRADLE_USER_HOME
$started = [DateTime]::UtcNow
$exitCode = -1
try {
    $env:JAVA_HOME = $JavaHome
    $env:GRADLE_USER_HOME = [IO.Path]::GetFullPath((Join-Path $projectRoot '..\.tool-cache\gradle-user-home'))
    Push-Location $projectRoot
    try {
        $backendHostArgs = @(); if($Suite -eq 'host' -or $Suite.StartsWith('compare-')) { $backendHostArgs += '-PbackendHost=true' }
        if($DependencyBundle){$backendHostArgs += "-PbackendDependencyBundle=$([IO.Path]::GetFullPath($DependencyBundle))"}
        & (Join-Path $projectRoot '..\.tool-cache\gradle-9.2.0\bin\gradle.bat') '-PbackendLab' @backendHostArgs "-PbackendRunId=$RunId" "-PbackendSuite=$Suite" ':backend-lab:runGameTestServer' '--console=plain' '--no-daemon' *> $log
        $exitCode = $LASTEXITCODE
    } finally { Pop-Location }
} finally {
    $env:JAVA_HOME = $priorJavaHome
    $env:GRADLE_USER_HOME = $priorGradleHome
    [ordered]@{
        runId=$RunId; suite=$Suite; evidenceType='ISOLATED_HEADLESS_BACKEND_GATE';
        startedUtc=$started.ToString('o'); finishedUtc=[DateTime]::UtcNow.ToString('o');
        processExitCode=$exitCode; log=$log; runDirectory=$runDir;
        dependencyBundle=$DependencyBundle;
        clientStarted=$false; realModelsCalled=$false; installed=$false
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $evidence "$RunId-run.json") -Encoding UTF8
}
Get-Content -LiteralPath $log -Tail 55
if ($exitCode -ne 0) { throw "Backend gate did not pass; see $log" }
if (-not (Select-String -LiteralPath $log -Pattern 'All [1-9][0-9]* required tests passed' -Quiet)) { throw 'No positive required-test completion evidence' }
