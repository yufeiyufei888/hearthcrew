param(
    [string]$JavaHome = (Join-Path $env:APPDATA '.minecraft\runtime\java-runtime-delta'),
    [string]$RunId,
    [int]$TimeoutSeconds = 180,
    [switch]$PlanOnly
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

function Assert-SafeRunId([string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch '^[A-Za-z0-9-]{1,48}$') {
        throw "RunId must match [A-Za-z0-9-]{1,48}."
    }
}

if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = 'run-{0}-{1}' -f (Get-Date -Format 'yyyyMMdd-HHmmss'), ([Guid]::NewGuid().ToString('N').Substring(0, 8))
}
Assert-SafeRunId $RunId
if ($TimeoutSeconds -lt 1) { throw 'TimeoutSeconds must be at least 1.' }

$runtimeDir = Join-Path $projectRoot ('.runtime\interop-{0}' -f $RunId)
$pairingPath = Join-Path $runtimeDir 'pairing.json'
$reportPath = Join-Path $runtimeDir 'report.json'
$nodeOutLog = Join-Path $runtimeDir 'node.stdout.log'
$nodeErrLog = Join-Path $runtimeDir 'node.stderr.log'
$gradleOutLog = Join-Path $runtimeDir 'gametest.stdout.log'
$gradleErrLog = Join-Path $runtimeDir 'gametest.stderr.log'
$prepareLog = Join-Path $runtimeDir 'prepare.log'
$runnerSummaryPath = Join-Path $runtimeDir 'runner-summary.json'
$gradlePath = Join-Path $projectRoot 'gradlew.bat'
$nodeScript = Join-Path $projectRoot 'bridge\dist\bridge\src\interop-probe.js'

function Quote-ProcessArgument([string]$Value) {
    if ($Value -notmatch '[\s"]') { return $Value }
    return '"' + $Value.Replace('"', '\"') + '"'
}

$nodeArgs = @(
    (Quote-ProcessArgument $nodeScript),
    '--run-id', $RunId,
    '--pairing', (Quote-ProcessArgument $pairingPath),
    '--report', (Quote-ProcessArgument $reportPath)
)
$gradleArgs = @(
    ':mod:runGameTestServer',
    '--console=plain',
    '--no-daemon',
    ('-PhearthcrewInterop=true'),
    ('-PhearthcrewInteropRunId={0}' -f $RunId),
    (Quote-ProcessArgument ('-PhearthcrewPairing={0}' -f $pairingPath)),
    (Quote-ProcessArgument ('-Dorg.gradle.java.installations.paths={0}' -f $JavaHome))
)

if ($PlanOnly) {
    Write-Output "runId=$RunId"
    Write-Output "runtimeDir=$runtimeDir"
    Write-Output 'prepare: npm.cmd run build (bridge)'
    Write-Output "prepare: gradlew.bat :mod:classes --console=plain -Dorg.gradle.java.installations.paths=<JavaHome>"
    Write-Output "node: node.exe <interop-probe.js> --run-id $RunId --pairing <runtimeDir>\pairing.json --report <runtimeDir>\report.json"
    Write-Output "gametest: gradlew.bat :mod:runGameTestServer --console=plain -PhearthcrewInterop=true -PhearthcrewInteropRunId=$RunId -PhearthcrewPairing=<runtimeDir>\pairing.json"
    exit 0
}

if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\javac.exe'))) {
    throw "Java 21 JDK not found: $JavaHome"
}
$npmCommand = (Get-Command npm.cmd -ErrorAction Stop).Source
$nodeCommand = (Get-Command node.exe -ErrorAction Stop).Source
if (-not (Test-Path -LiteralPath $gradlePath)) { throw "Missing Gradle wrapper: $gradlePath" }
if (Test-Path -LiteralPath $runtimeDir) { throw 'Runtime evidence directory already exists; use a new RunId.' }
New-Item -ItemType Directory -Path $runtimeDir | Out-Null

$priorJavaHome = $env:JAVA_HOME
$nodeProcess = $null
$gradleProcess = $null
$nodeExit = $null
$gradleExit = $null
$reportStatus = $null
$failure = $null
$locationPushed = $false

function Stop-OwnedProcess($Process) {
    if ($null -eq $Process) { return }
    $Process.Refresh()
    if (-not $Process.HasExited) {
        # The PID is the process this script started; /T only terminates its
        # descendants, so unrelated JVMs and Node processes are untouched.
        & taskkill.exe /PID $Process.Id /T /F *> $null
    }
}

try {
    $env:JAVA_HOME = $JavaHome
    Push-Location -LiteralPath $projectRoot
    $locationPushed = $true

    & $npmCommand --prefix (Join-Path $projectRoot 'bridge') run build *> $prepareLog
    $npmExit = $LASTEXITCODE
    if ($npmExit -ne 0) {
        Get-Content -LiteralPath $prepareLog
        throw "bridge npm build failed with exit code $npmExit"
    }

    & $gradlePath ':mod:classes' '--console=plain' ('-Dorg.gradle.java.installations.paths=' + $JavaHome) *>> $prepareLog
    $classesExit = $LASTEXITCODE
    if ($classesExit -ne 0) {
        Get-Content -LiteralPath $prepareLog
        throw "mod classes build failed with exit code $classesExit"
    }

    if (-not (Test-Path -LiteralPath $nodeScript)) { throw "Missing built interop probe: $nodeScript" }
    $nodeProcess = Start-Process -FilePath $nodeCommand -ArgumentList $nodeArgs -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput $nodeOutLog -RedirectStandardError $nodeErrLog -PassThru

    # ModLink reads the pairing file during server startup, so wait for the
    # probe's server to publish it before starting the GameTest JVM. The token
    # is only parsed for readiness and is never printed or passed as an arg.
    $pairingReady = $false
    $pairingDeadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $pairingDeadline) {
        if (Test-Path -LiteralPath $pairingPath) {
            try {
                $pairing = Get-Content -LiteralPath $pairingPath -Raw | ConvertFrom-Json
                $port = 0
                try { $port = [Convert]::ToInt32($pairing.port) } catch { $port = 0 }
                if ($port -gt 0 -and -not [string]::IsNullOrWhiteSpace([string]$pairing.token)) {
                    $pairingReady = $true
                    break
                }
            } catch { }
        }
        Start-Sleep -Milliseconds 100
    }
    if (-not $pairingReady) { throw 'interop probe did not publish a valid pairing file within 30 seconds' }

    $gradleProcess = Start-Process -FilePath $gradlePath -ArgumentList $gradleArgs -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput $gradleOutLog -RedirectStandardError $gradleErrLog -PassThru
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $nodeProcess.Refresh()
        $gradleProcess.Refresh()
        if ($nodeProcess.HasExited -and $gradleProcess.HasExited) { break }
        Start-Sleep -Milliseconds 250
    }
    $nodeProcess.Refresh()
    $gradleProcess.Refresh()
    if (-not $nodeProcess.HasExited -or -not $gradleProcess.HasExited) {
        throw "interop processes exceeded timeout ${TimeoutSeconds}s"
    }
    $nodeExit = $nodeProcess.ExitCode
    $gradleExit = $gradleProcess.ExitCode

    if (Test-Path -LiteralPath $reportPath) {
        try { $reportStatus = (Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json).status } catch { $reportStatus = 'INVALID_REPORT' }
    } else {
        $reportStatus = 'MISSING_REPORT'
    }
    if ($nodeExit -ne 0 -or $gradleExit -ne 0 -or $reportStatus -ne 'PASS') {
        throw "interop failed: nodeExit=$nodeExit gradleExit=$gradleExit reportStatus=$reportStatus"
    }
    Write-Output "PASS runId=$RunId nodeExit=$nodeExit gradleExit=$gradleExit reportStatus=$reportStatus"
    Write-Output "Evidence: $runtimeDir"
} catch {
    $failure = $_.Exception.Message
    [Console]::Error.WriteLine($failure)
    throw
} finally {
    Stop-OwnedProcess $gradleProcess
    Stop-OwnedProcess $nodeProcess
    if ($null -ne $nodeProcess) { $nodeProcess.Refresh(); if ($nodeProcess.HasExited) { $nodeExit = $nodeProcess.ExitCode } }
    if ($null -ne $gradleProcess) { $gradleProcess.Refresh(); if ($gradleProcess.HasExited) { $gradleExit = $gradleProcess.ExitCode } }
    $runnerStatus = if ($null -eq $failure -and $nodeExit -eq 0 -and $gradleExit -eq 0 -and $reportStatus -eq 'PASS') { 'PASS' } else { 'FAIL' }
    $summary = [ordered]@{
        runId = $RunId
        status = $runnerStatus
        nodeExit = $nodeExit
        gradleExit = $gradleExit
        reportStatus = $reportStatus
        failure = $failure
        pairingPath = $pairingPath
        reportPath = $reportPath
        nodeStdoutPath = $nodeOutLog
        nodeStderrPath = $nodeErrLog
        gradleStdoutPath = $gradleOutLog
        gradleStderrPath = $gradleErrLog
    }
    try { $summary | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $runnerSummaryPath -Encoding UTF8 } catch { }
    if ($locationPushed) { Pop-Location -ErrorAction SilentlyContinue }
    $env:JAVA_HOME = $priorJavaHome
}
