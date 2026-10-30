param(
    [switch]$Install
)

$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot
$stage = Join-Path $env:TEMP 'unrestrictedbuild'
$classes = Join-Path $stage 'classes'
$jdk = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { 'C:\Program Files\Zulu\zulu-17\bin' }
$asmCache = if ($env:ASM_HOME) { $env:ASM_HOME } else { Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1\org.ow2.asm' }
$agentsDir = if ($env:LUNAR_AGENTS) { $env:LUNAR_AGENTS } else { 'C:\Users\laion\Desktop\Lunar\agents' }

$asm = (Join-Path $asmCache 'asm\9.2\81a03f76019c67362299c40e0ba13405f5467bff\asm-9.2.jar')
$asmTree = (Join-Path $asmCache 'asm-tree\9.2\d96c99a30f5e1a19b0e609dbb19a44d8518ac01e\asm-tree-9.2.jar')

foreach ($jar in @($asm, $asmTree)) {
    if (-not (Test-Path $jar)) { throw "missing dependency: $jar" }
}

if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $classes | Out-Null

$src = Join-Path $root 'src\main\java\me\zircta\unrestricted'

& "$jdk\javac.exe" --release 16 -cp "$asm;$asmTree" -d $classes `
    (Join-Path $src 'Agent.java') `
    (Join-Path $src 'UnrestrictedTransformer.java')
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach ($jar in @($asm, $asmTree)) {
    $z = [System.IO.Compression.ZipFile]::OpenRead($jar)
    foreach ($e in $z.Entries) {
        if ($e.FullName.EndsWith('/')) { continue }
        if ($e.FullName -eq 'module-info.class') { continue }
        $dest = Join-Path $classes $e.FullName
        $dir = Split-Path $dest -Parent
        if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $dest, $true)
    }
    $z.Dispose()
}

$manifest = Join-Path $stage 'MANIFEST.MF'
@"
Manifest-Version: 1.0
Agent-Class: me.zircta.unrestricted.Agent
Premain-Class: me.zircta.unrestricted.Agent
Can-Retransform-Classes: true
Can-Redefine-Classes: true

"@ | Set-Content $manifest -Encoding ascii

$out = Join-Path $stage 'unrestricted.jar'
& "$jdk\jar.exe" cfm $out $manifest -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }

Get-Item $out | Select-Object FullName, Length
& "$jdk\jar.exe" tf $out | Where-Object { $_ -match 'unrestricted|asm/ClassReader|MANIFEST' } | Select-Object -First 8

if ($Install) {
    $agents = Join-Path $agentsDir 'unrestricted.jar'
    Copy-Item $out $agents -Force
    Get-Item $agents | Select-Object FullName, Length, LastWriteTime
}

