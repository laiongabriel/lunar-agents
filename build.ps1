param(
    [switch]$Install
)

$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot

function Invoke-AgentScript {
    param([string]$Name, [string]$Script)

    Write-Host "`n=== $Name ===" -ForegroundColor Cyan
    $args = @('-ExecutionPolicy', 'Bypass', '-File', $Script)
    if ($Install) {
        $args += '-Install'
    }
    & powershell @args
    if ($LASTEXITCODE -ne 0) {
        throw "$Name build failed"
    }
}

Invoke-AgentScript 'AccurateCPS' (Join-Path $root 'agents\accuratecps\build.ps1')
Invoke-AgentScript 'NoHitDelay' (Join-Path $root 'agents\nohitdelay\build.ps1')
Invoke-AgentScript 'Unrestricted' (Join-Path $root 'agents\unrestricted\build.ps1')
Invoke-AgentScript 'AltManager' (Join-Path $root 'agents\altmanager\build.ps1')
Invoke-AgentScript 'FastPlace' (Join-Path $root 'agents\fastplace\build.ps1')
