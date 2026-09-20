[CmdletBinding()]
param(
    [string]$RunId,
    [string]$JavaHome = (Join-Path $env:APPDATA '.minecraft\runtime\java-runtime-delta'),
    [string]$CodexCommand = 'codex',
    [ValidateSet('P1', 'P2')]
    [string]$Scenario = 'P1',
    [int]$TimeoutSeconds = 900,
    [ValidateSet('none', 'controller', 'app-server', 'late-tool')]
    [string]$Fault = 'none',
    [switch]$CompoundBuild,
    [switch]$Build,
    [switch]$PlanOnly
)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))

function Assert-SafeRunId([string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch '^[A-Za-z0-9-]{1,48}$') {
        throw 'RunId must match [A-Za-z0-9-]{1,48} and must be unique.'
    }
}

if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = 'model-{0}-{1}' -f (Get-Date -Format 'yyyyMMdd-HHmmss'), ([Guid]::NewGuid().ToString('N').Substring(0, 8))
}
Assert-SafeRunId $RunId
if ($TimeoutSeconds -lt 60) { throw 'TimeoutSeconds must be at least 60.' }
if ($Scenario -eq 'P2' -and $Fault -ne 'none') { throw 'P2 process fault injection is not implemented; use P1 fault modes explicitly.' }
if ($CompoundBuild -and $Scenario -ne 'P2') { throw 'CompoundBuild is supported only by the P2 scenario.' }

$runtimePrefix = if ($Scenario -eq 'P2') { 'team-client' } else { 'model-client' }
$savePrefix = if ($Scenario -eq 'P2') { 'P2Team' } else { 'P1Model' }
$runTask = if ($Scenario -eq 'P2') { ':mod:runTeamTest' } else { ':mod:runModelTest' }
$runProperty = if ($Scenario -eq 'P2') { 'hearthcrewTeamRunId' } else { 'hearthcrewModelRunId' }
$runVmMarker = if ($Scenario -eq 'P2') { 'teamTestRunVmArgs' } else { 'modelTestRunVmArgs' }
$runtimeDir = Join-Path $projectRoot ('.runtime\{0}-{1}' -f $runtimePrefix, $RunId)
$controllerState = Join-Path $runtimeDir 'controller'
$pairingPath = Join-Path $controllerState 'pairing.json'
$reportPath = Join-Path $runtimeDir 'report.json'
$probeStdout = Join-Path $runtimeDir 'probe.stdout.log'
$probeStderr = Join-Path $runtimeDir 'probe.stderr.log'
$gameStdout = Join-Path $runtimeDir 'game.stdout.log'
$gameStderr = Join-Path $runtimeDir 'game.stderr.log'
$summaryPath = Join-Path $runtimeDir 'runner-summary.json'
$probeScript = if ($Scenario -eq 'P2') {
    Join-Path $projectRoot 'bridge\dist\bridge\src\team-client-probe.js'
} elseif ($Fault -eq 'none') {
    Join-Path $projectRoot 'bridge\dist\bridge\src\model-client-probe.js'
} elseif ($Fault -eq 'late-tool') {
    Join-Path $projectRoot 'bridge\dist\bridge\src\late-tool-client-probe.js'
} else {
    Join-Path $projectRoot 'bridge\dist\bridge\src\p1-recovery-probe.js'
}
$probeEvidence = if ($CompoundBuild) { 'CONTROLLED_REAL_CLIENT_TEAM_BUILD_FIXTURE' } elseif ($Scenario -eq 'P2') { 'CONTROLLED_REAL_CLIENT_TEAM_FIXTURE' } elseif ($Fault -eq 'none') { 'CONTROLLED_REAL_CLIENT_MODEL_FIXTURE' } elseif ($Fault -eq 'late-tool') { 'CONTROLLED_REAL_CLIENT_LATE_TOOL' } else { 'CONTROLLED_REAL_CLIENT_FAULT' }
$gradlePath = Join-Path $projectRoot 'gradlew.bat'
$fixtureSave = Join-Path $projectRoot ('mod\run\client\saves\{0}-{1}' -f $savePrefix, $RunId)
$expectedFixtureSave = [IO.Path]::GetFullPath((Join-Path $projectRoot ('mod\run\client\saves\{0}-{1}' -f $savePrefix, $RunId)))
$localAppData = $env:LOCALAPPDATA
if (-not $localAppData) { $localAppData = Join-Path $HOME 'AppData\Local' }
$codexRuntime = Join-Path $localAppData 'HearthCrew\codex-runtime'
$workspaceRoot = Join-Path $localAppData 'HearthCrew\workspace'
if ([IO.Path]::GetFullPath($fixtureSave) -ne $expectedFixtureSave) { throw "Fixture save must be exactly $expectedFixtureSave" }

function Quote-ProcessArgument([string]$Value) {
    if ($Value -notmatch '[\s"]') { return $Value }
    return '"' + $Value.Replace('"', '\"') + '"'
}

if ($PlanOnly) {
    Write-Output "runId=$RunId"
    Write-Output "scenario=$Scenario"
    Write-Output "compoundBuild=$CompoundBuild"
    Write-Output "fault=$Fault"
    Write-Output "runtimeDir=$runtimeDir"
    Write-Output "fixtureSave=$fixtureSave"
    Write-Output "controllerState=$controllerState"
    Write-Output ("probeScript={0}" -f $probeScript)
    if ($Scenario -eq 'P2') {
        Write-Output 'probe: node.exe <team-client-probe.js> --run-id <RunId> --run-root <runtimeDir> --controller-state <runtimeDir>\controller --fixture-save <fixtureSave>'
    } elseif ($Fault -eq 'none') {
        Write-Output 'probe: node.exe <model-client-probe.js> --run-id <RunId> --run-root <runtimeDir> --controller-state <runtimeDir>\controller --fixture-save <fixtureSave>'
    } elseif ($Fault -eq 'late-tool') {
        Write-Output 'probe: node.exe <late-tool-client-probe.js> --run-id <RunId> --run-root <runtimeDir> --controller-state <runtimeDir>\controller --fixture-save <fixtureSave>'
    } else {
        Write-Output 'probe: node.exe <p1-recovery-probe.js> --fault <none|controller|app-server> --run-id <RunId> --run-root <runtimeDir> --controller-state <runtimeDir>\controller --fixture-save <fixtureSave>'
    }
    Write-Output ("game: gradlew.bat --no-daemon {0} --console=plain -P{1}=<RunId> -PhearthcrewPairing=<runtimeDir>\controller\pairing.json" -f $runTask, $runProperty)
    if ($CompoundBuild) { Write-Output 'extra arguments: probe --compound-build; game -PhearthcrewTeamBuild=true' }
    if ($Build) { Write-Output 'prepare: npm.cmd run build; gradlew.bat --no-daemon :mod:classes --console=plain' }
    exit 0
}

if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\javac.exe') -PathType Leaf)) { throw "Java 21 JDK not found: $JavaHome" }
$nodeCommand = (Get-Command node.exe -ErrorAction Stop).Source
$npmCommand = (Get-Command npm.cmd -ErrorAction SilentlyContinue)
if (-not $npmCommand) { $npmCommand = Get-Command npm -ErrorAction SilentlyContinue }
if ($Build -and -not $npmCommand) { throw 'npm was not found on PATH; cannot build the TypeScript probe.' }
if (-not (Test-Path -LiteralPath $gradlePath -PathType Leaf)) { throw "Missing Gradle wrapper: $gradlePath" }
if (-not $Build -and -not (Test-Path -LiteralPath $probeScript -PathType Leaf)) { throw "Compiled model probe for fault '$Fault' is missing: $probeScript. Run npm.cmd ci; npm.cmd run build, or use -Build." }
if (Test-Path -LiteralPath $runtimeDir -PathType Container) { throw "Runtime directory already exists; choose a new unique RunId: $runtimeDir" }
if (Test-Path -LiteralPath $fixtureSave -PathType Container) { throw "Fixture save directory already exists; choose a new unique RunId: $fixtureSave" }
New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null
New-Item -ItemType Directory -Force -Path $controllerState | Out-Null

$priorJavaHome = $env:JAVA_HOME
$probeProcess = $null
$gameProcess = $null
$failure = $null
$probeExit = $null
$gameExit = $null
$reportStatus = $null
$locationPushed = $false
$gameClosedGracefully = $false

function Stop-OwnedProcess($Process) {
    if ($null -eq $Process) { return }
    try { $Process.Refresh() } catch { return }
    if (-not $Process.HasExited) {
        # This PID was returned by Start-Process in this script. /T is limited
        # to that process tree and never searches by a broad process name.
        & taskkill.exe /PID $Process.Id /T /F *> $null
    }
}

function Get-OwnedDescendantIds([int]$RootId) {
    $all = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue)
    $found = New-Object 'System.Collections.Generic.List[int]'
    $frontier = New-Object 'System.Collections.Generic.List[int]'
    [void]$frontier.Add($RootId)
    while ($frontier.Count -gt 0) {
        $parent = $frontier[0]; $frontier.RemoveAt(0)
        foreach ($candidate in $all | Where-Object { $_.ParentProcessId -eq $parent }) {
            $child = [int]$candidate.ProcessId
            if (-not $found.Contains($child)) { [void]$found.Add($child); [void]$frontier.Add($child) }
        }
    }
    return @($found)
}

function Close-OwnedGameProcess($Process) {
    if ($null -eq $Process) { return $true }
    try { $Process.Refresh() } catch { return $false }
    if ($Process.HasExited) { return $true }
    $ids = @(Get-OwnedDescendantIds $Process.Id)
    [array]::Reverse($ids)
    foreach ($id in $ids) {
        try {
            $ownedJvm = Get-CimInstance Win32_Process -Filter "ProcessId=$id" -ErrorAction Stop
            if ($ownedJvm.Name -notin @('java.exe', 'javaw.exe') -or $ownedJvm.CommandLine -notmatch $runVmMarker -or
                $ownedJvm.CommandLine -notmatch [regex]::Escape('hearthcrew')) { continue }
            $candidate = Get-Process -Id $id -ErrorAction Stop
            if (-not $candidate.HasExited) { [void]$candidate.CloseMainWindow() }
        } catch { }
    }
    $deadline = (Get-Date).AddSeconds(30)
    do {
        try { $Process.Refresh() } catch { return $false }
        if ($Process.HasExited) { return $true }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    return $false
}

try {
    $env:JAVA_HOME = $JavaHome
    Push-Location -LiteralPath $projectRoot
    $locationPushed = $true
    if ($Build) {
        Push-Location -LiteralPath (Join-Path $projectRoot 'bridge')
        try {
            & $npmCommand.Source run build *> (Join-Path $runtimeDir 'build.log')
            if ($LASTEXITCODE -ne 0) { throw "bridge build failed with exit code $LASTEXITCODE" }
        } finally { Pop-Location }
        & $gradlePath '--no-daemon' ':mod:classes' '--console=plain' (Quote-ProcessArgument ('-Dorg.gradle.java.installations.paths=' + $JavaHome)) *>> (Join-Path $runtimeDir 'build.log')
        if ($LASTEXITCODE -ne 0) { throw "mod classes build failed with exit code $LASTEXITCODE" }
    }
    if (-not (Test-Path -LiteralPath $probeScript -PathType Leaf)) { throw "Compiled model probe for fault '$Fault' is missing after build: $probeScript" }
    $probeArguments = @(
        (Quote-ProcessArgument $probeScript), '--project-root', (Quote-ProcessArgument $projectRoot),
        '--run-id', $RunId, '--run-root', (Quote-ProcessArgument $runtimeDir), '--controller-state', (Quote-ProcessArgument $controllerState), '--report', (Quote-ProcessArgument $reportPath),
        '--fixture-save', (Quote-ProcessArgument $fixtureSave), '--codex-command', (Quote-ProcessArgument $CodexCommand),
        '--codex-runtime', (Quote-ProcessArgument $codexRuntime), '--workspace', (Quote-ProcessArgument $workspaceRoot),
        '--timeout-seconds', $TimeoutSeconds
    )
    if ($Fault -ne 'none') { $probeArguments += @('--fault', $Fault) }
    if ($CompoundBuild) { $probeArguments += '--compound-build' }
    $probeProcess = Start-Process -FilePath $nodeCommand -ArgumentList $probeArguments -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput $probeStdout -RedirectStandardError $probeStderr -PassThru

    $pairingReady = $false
    $pairingDeadline = (Get-Date).AddSeconds(45)
    while ((Get-Date) -lt $pairingDeadline) {
        $probeProcess.Refresh()
        if ($probeProcess.HasExited) { throw "model probe exited before pairing was ready; inspect $probeStderr" }
        if (Test-Path -LiteralPath $pairingPath -PathType Leaf) {
            try {
                $pairing = Get-Content -LiteralPath $pairingPath -Raw | ConvertFrom-Json
                $port = 0
                try { $port = [Convert]::ToInt32($pairing.port) } catch { $port = 0 }
                if ($port -gt 0 -and -not [string]::IsNullOrWhiteSpace([string]$pairing.token)) { $pairingReady = $true; break }
            } catch { }
        }
        Start-Sleep -Milliseconds 100
    }
    if (-not $pairingReady) { throw 'model probe did not publish a valid pairing file within 45 seconds' }

    $gameArguments = @(
        '--no-daemon', $runTask, '--console=plain', (Quote-ProcessArgument ('-P{0}={1}' -f $runProperty, $RunId)),
        (Quote-ProcessArgument ('-PhearthcrewPairing={0}' -f $pairingPath)), (Quote-ProcessArgument ('-Dorg.gradle.java.installations.paths={0}' -f $JavaHome))
    )
    if ($CompoundBuild) { $gameArguments += '-PhearthcrewTeamBuild=true' }
    $gameProcess = Start-Process -FilePath $gradlePath -ArgumentList $gameArguments -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput $gameStdout -RedirectStandardError $gameStderr -PassThru

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if ($null -ne $probeProcess) { $probeProcess.Refresh() }; if ($null -ne $gameProcess) { $gameProcess.Refresh() }
        if (Test-Path -LiteralPath $reportPath -PathType Leaf) {
            try { $reportStatus = (Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json).status } catch { $reportStatus = 'INVALID_REPORT' }
            if ($reportStatus -eq 'PASS' -or ($probeProcess.HasExited -and $reportStatus -eq 'FAIL')) { break }
        }
        if ($probeProcess.HasExited -and $gameProcess.HasExited) { break }
        Start-Sleep -Milliseconds 500
    }
    if ($null -ne $probeProcess) { $probeProcess.Refresh() }; if ($null -ne $gameProcess) { $gameProcess.Refresh() }
    # The fixture deliberately leaves the client open. A PASS requests a
    # graceful close from the actual game JVM and waits for the save to finish.
    # Forced tree termination is reserved for timeout/failure cleanup.
    if ($reportStatus -eq 'PASS' -or ($probeProcess.HasExited -and $reportStatus -eq 'FAIL')) {
        $gameClosedGracefully = Close-OwnedGameProcess $gameProcess
        if (-not $gameClosedGracefully) { throw 'owned model client did not close gracefully within 30 seconds' }
        $waitForProbe = (Get-Date).AddSeconds(10)
        while (-not $probeProcess.HasExited -and (Get-Date) -lt $waitForProbe) { Start-Sleep -Milliseconds 100; $probeProcess.Refresh() }
        if (-not $probeProcess.HasExited) { Stop-OwnedProcess $probeProcess }
    }
    if ($null -ne $probeProcess) { $probeProcess.Refresh() }; if ($null -ne $gameProcess) { $gameProcess.Refresh() }
    if ($null -eq $probeProcess -or -not $probeProcess.HasExited) { throw "model client probe exceeded timeout ${TimeoutSeconds}s" }
    $probeExit = $probeProcess.ExitCode
    $gameExit = if ($null -ne $gameProcess -and $gameProcess.HasExited) { $gameProcess.ExitCode } else { $null }
    if (Test-Path -LiteralPath $reportPath -PathType Leaf) {
        try { $reportStatus = (Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json).status } catch { $reportStatus = 'INVALID_REPORT' }
    } else { $reportStatus = 'MISSING_REPORT' }
    if ($probeExit -ne 0 -or $reportStatus -ne 'PASS' -or $gameExit -ne 0) { throw "real model client test failed: probeExit=$probeExit reportStatus=$reportStatus gameExit=$gameExit" }
    [Console]::Error.WriteLine("PASS runId=$RunId reportStatus=$reportStatus")
} catch {
    $failure = $_.Exception.Message
    [Console]::Error.WriteLine("FAIL runId=$RunId reason=$failure")
    throw
} finally {
    if (-not $gameClosedGracefully) { Stop-OwnedProcess $gameProcess }
    Stop-OwnedProcess $probeProcess
    if ($null -ne $probeProcess) { try { $probeProcess.Refresh(); if ($probeProcess.HasExited) { $probeExit = $probeProcess.ExitCode } } catch { } }
    if ($null -ne $gameProcess) { try { $gameProcess.Refresh(); if ($gameProcess.HasExited) { $gameExit = $gameProcess.ExitCode } } catch { } }
    $runnerStatus = if ($null -eq $failure -and $gameClosedGracefully -and $probeExit -eq 0 -and $gameExit -eq 0 -and $reportStatus -eq 'PASS') { 'PASS' } else { 'FAIL' }
    $summary = [ordered]@{
        runId = $RunId
        scenario = $Scenario
        compoundBuild = [bool]$CompoundBuild
        fault = $Fault
        evidence = $probeEvidence
        status = $runnerStatus
        probeExit = $probeExit
        gameExit = $gameExit
        reportStatus = $reportStatus
        failure = $failure
        reportPath = $reportPath
        probeStdout = $probeStdout
        probeStderr = $probeStderr
        gameStdout = $gameStdout
        gameStderr = $gameStderr
    }
    try { $summary | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $summaryPath -Encoding UTF8 } catch { }
    if ($locationPushed) { Pop-Location -ErrorAction SilentlyContinue }
    $env:JAVA_HOME = $priorJavaHome
}
