param(
    [switch]$Install
)

$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot
$agentsDir = 'C:\Users\laion\Desktop\Lunar\agents'

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

Write-Host "`n=== FastPlace ===" -ForegroundColor Cyan
Push-Location (Join-Path $root 'agents\fastplace')
try {
    & .\gradlew.bat build
    if ($LASTEXITCODE -ne 0) {
        throw 'FastPlace build failed'
    }

    $jar = Get-ChildItem 'build\libs\fastplace-*-all.jar' | Select-Object -First 1
    if (-not $jar) {
        throw 'FastPlace jar not found in build\libs'
    }
    Write-Host $jar.FullName

    if ($Install) {
        $target = Join-Path $agentsDir 'fastplace.jar'
        Copy-Item $jar.FullName $target -Force
        Get-Item $target | Select-Object FullName, Length, LastWriteTime
    }
}
finally {
    Pop-Location
}
