param(
    [switch]$Install
)

$ErrorActionPreference = 'Stop'

$root = $PSScriptRoot
$stage = Join-Path $env:TEMP 'altmanagerbuild'
$classes = Join-Path $stage 'classes'
$stubsOut = Join-Path $stage 'stubs-classes'
$jdk = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { 'C:\Program Files\Zulu\zulu-17\bin' }
$asmCache = if ($env:ASM_HOME) { $env:ASM_HOME } else { Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1\org.ow2.asm' }
$agentsDir = if ($env:LUNAR_AGENTS) { $env:LUNAR_AGENTS } else { 'C:\Users\laion\Desktop\Lunar\agents' }

$asm = (Join-Path $asmCache 'asm\9.2\81a03f76019c67362299c40e0ba13405f5467bff\asm-9.2.jar')
$asmTree = (Join-Path $asmCache 'asm-tree\9.2\d96c99a30f5e1a19b0e609dbb19a44d8518ac01e\asm-tree-9.2.jar')
$asmCommons = (Join-Path $asmCache 'asm-commons\9.2\f4d7f0fc9054386f2893b602454d48e07d4fbead\asm-commons-9.2.jar')

foreach ($jar in @($asm, $asmTree, $asmCommons)) {
    if (-not (Test-Path $jar)) { throw "missing dependency: $jar" }
}

if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $classes | Out-Null
New-Item -ItemType Directory -Force -Path $stubsOut | Out-Null

$stubSources = Get-ChildItem (Join-Path $root 'stubs') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$guiSource = Join-Path $root 'src\main\java\net\minecraft\client\gui\GuiAltManager.java'
$runtimeSource = Join-Path $root 'src\main\java\me\zircta\altmanager\AltRuntime.java'
$agentSource = Join-Path $root 'src\main\java\me\zircta\altmanager\Agent.java'

# 1. Compile-only game stubs. They give the GUI realistic signatures but are
#    never packaged: the real signatures come from Lunar at runtime.
& "$jdk\javac.exe" --release 8 -d $stubsOut $stubSources
if ($LASTEXITCODE -ne 0) { throw 'javac stubs failed' }

# 2. The screen stays at Java 8 bytecode so it loads in any Lunar JVM.
& "$jdk\javac.exe" --release 8 -cp $stubsOut -d $classes $guiSource
if ($LASTEXITCODE -ne 0) { throw 'javac screen failed' }

# 3. The injected runtime defines the screen through MethodHandles.Lookup
#    (Java 9+), which the agent jar already requires anyway.
& "$jdk\javac.exe" --release 16 -cp "$stubsOut;$classes" -d $classes $runtimeSource
if ($LASTEXITCODE -ne 0) { throw 'javac runtime failed' }

# 4. The agent itself needs the same API for nothing but consistency with the
#    instrumentation plumbing.
& "$jdk\javac.exe" --release 16 -cp "$stubsOut;$classes;$asm;$asmTree;$asmCommons" -d $classes $agentSource
if ($LASTEXITCODE -ne 0) { throw 'javac Agent failed' }

# 5. Bundle ASM so the jar has no runtime dependencies.
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
Premain-Class: me.zircta.altmanager.Agent
Agent-Class: me.zircta.altmanager.Agent
Can-Retransform-Classes: true
Can-Redefine-Classes: true

"@ | Set-Content $manifest -Encoding ascii

$out = Join-Path $stage 'altmanager.jar'
& "$jdk\jar.exe" cfm $out $manifest -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }

# 5. The stubs must never ship: the game provides the real classes.
$entries = & "$jdk\jar.exe" tf $out
$leaked = $entries | Where-Object {
    $_ -eq 'net/minecraft/client/Minecraft.class' -or
    $_ -eq 'net/minecraft/client/gui/GuiScreen.class' -or
    $_ -eq 'net/minecraft/client/gui/GuiButton.class' -or
    $_ -eq 'net/minecraft/client/gui/Gui.class' -or
    $_ -eq 'net/minecraft/client/gui/FontRenderer.class' -or
    $_ -like 'org/lwjgl/*'
}
if ($leaked) { throw "stub classes leaked into the jar: $leaked" }

Get-Item $out | Select-Object FullName, Length
$entries | Where-Object {
    $_ -match '^(me/zircta/altmanager/|net/minecraft/client/gui/GuiAltManager|org/objectweb/asm/ClassReader|MANIFEST)'
} | Select-Object -First 8

if ($Install) {
    $agents = Join-Path $agentsDir 'altmanager.jar'
    Copy-Item $out $agents -Force
    Get-Item $agents | Select-Object FullName, Length, LastWriteTime
}

