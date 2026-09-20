param([string]$Manifest = (Join-Path (Split-Path -Parent $PSScriptRoot) 'evidence\acceptance.json'))
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$manifestData = Get-Content -LiteralPath $Manifest -Raw | ConvertFrom-Json
$required = @('P0_BODY', 'P1_CODEX_LOOP', 'P2_TEAMWORK', 'P3_END_RETURN', 'GUARD_UNDER_1_SECOND', 'SCHEDULER_NO_15_SECOND_GAP',
    'THREE_ROLES_30_MINUTES', 'SOAK_120_MINUTES', 'PROCESS_KILL_RECOVERY', 'FAILURE_MATRIX', 'NEW_SURVIVAL_CAMPAIGN',
    'SECOND_SEED_RECHECK', 'CONTRIBUTION_EVIDENCE', 'CHINESE_UI_REAL_CLIENT', 'QOL_COMPATIBILITY', 'CLEAN_INSTALL', 'BUILD_HASH_MANIFEST')
$missing = @()
foreach ($gateId in $required) {
    $matching = @($manifestData.gates | Where-Object { $_.id -eq $gateId })
    if ($matching.Count -ne 1 -or $matching[0].status -ne 'PASS' -or -not $matching[0].report) { $missing += $gateId; continue }
    $reportPath = [IO.Path]::GetFullPath((Join-Path $projectRoot $matching[0].report))
    if (-not $reportPath.StartsWith($projectRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Report is outside the project: $gateId"
    }
    if (-not (Test-Path -LiteralPath $reportPath -PathType Leaf)) { $missing += $gateId }
}
if ($missing.Count -gt 0) { throw ('Release is blocked by missing acceptance evidence: ' + ($missing -join ', ')) }
if ($manifestData.releaseReady -ne $true -or $manifestData.targetRelease -ne 'v1.0.0') { throw 'Release manifest does not authorize v1.0.0.' }
$actualCommit = (& git -C $projectRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $manifestData.sourceCommit -ne $actualCommit) { throw 'Acceptance commit does not match HEAD.' }
$dirty = & git -C $projectRoot status --porcelain
if ($LASTEXITCODE -ne 0 -or $dirty) { throw 'Release requires the reviewed clean source tree.' }
Write-Output 'All recorded release gates are present. This script does not publish or replace review of the underlying evidence.'
