param(
    [switch]$Install
)

$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot
$stage = Join-Path $env:TEMP 'cpsbuild'
$classes = Join-Path $stage 'classes'
$jdk = 'C:\Program Files\Zulu\zulu-17\bin'

$asm = 'C:\Users\laion\.gradle\caches\modules-2\files-2.1\org.ow2.asm\asm\9.2\81a03f76019c67362299c40e0ba13405f5467bff\asm-9.2.jar'
$asmTree = 'C:\Users\laion\.gradle\caches\modules-2\files-2.1\org.ow2.asm\asm-tree\9.2\d96c99a30f5e1a19b0e609dbb19a44d8518ac01e\asm-tree-9.2.jar'
$asmCommons = 'C:\Users\laion\.gradle\caches\modules-2\files-2.1\org.ow2.asm\asm-commons\9.2\f4d7f0fc9054386f2893b602454d48e07d4fbead\asm-commons-9.2.jar'

foreach ($jar in @($asm, $asmTree, $asmCommons)) {
    if (-not (Test-Path $jar)) { throw "missing dependency: $jar" }
}

if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $classes | Out-Null

$src = Join-Path $root 'src\main\java\me\zircta\accuratecps'

# The runtime is copied into Minecraft, so keep it at Java 8 level.
& "$jdk\javac.exe" --release 8 -d $classes (Join-Path $src 'CpsRuntime.java')
if ($LASTEXITCODE -ne 0) { throw 'javac CpsRuntime failed' }

# The agent itself uses Java 16 language features.
& "$jdk\javac.exe" --release 16 -cp "$asm;$asmTree;$asmCommons" -d $classes (Join-Path $src 'Agent.java')
if ($LASTEXITCODE -ne 0) { throw 'javac Agent failed' }

Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach ($jar in @($asm, $asmTree, $asmCommons)) {
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
Agent-Class: me.zircta.accuratecps.Agent
Premain-Class: me.zircta.accuratecps.Agent
Can-Retransform-Classes: true
Can-Redefine-Classes: true

"@ | Set-Content $manifest -Encoding ascii

$out = Join-Path $stage 'accuratecps.jar'
& "$jdk\jar.exe" cfm $out $manifest -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }

Get-Item $out | Select-Object FullName, Length
& "$jdk\jar.exe" tf $out | Where-Object { $_ -match 'accuratecps|asm/ClassReader|asm/commons|MANIFEST' } | Select-Object -First 8

if ($Install) {
    $agents = 'C:\Users\laion\Desktop\Lunar\agents\accuratecps.jar'
    Copy-Item $out $agents -Force
    Get-Item $agents | Select-Object FullName, Length, LastWriteTime
}
