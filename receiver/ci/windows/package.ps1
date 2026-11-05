# Collects the program with its Qt and GStreamer runtime, then builds casthop-setup-x86_64.exe.
#   receiver\ci\windows\package.ps1 [-BuildDir receiver\build-windows] [-OutDir out]
#
# Staged layout, which keeps the structure GStreamer expects around its DLLs:
#   bin\      casthop.exe, every DLL, Qt plugins and QML modules
#   lib\gstreamer-1.0\      GStreamer plugins, only the ones CastHop uses
#   libexec\gstreamer-1.0\  gst-plugin-scanner.exe
#   licenses\ licence texts
param(
    [string]$BuildDir = '',
    [string]$OutDir = ''
)
. (Join-Path $PSScriptRoot 'common.ps1')

$repo = Get-RepoRoot
if (-not $BuildDir) {
    $BuildDir = Join-Path $repo 'receiver\build-windows'
}
if (-not $OutDir) {
    $OutDir = Join-Path $repo 'out'
}
Enter-MsvcShell

$qt = Get-QtRoot
$gst = Get-GStreamerRoot
$stage = Join-Path $OutDir 'stage'
$bin = Join-Path $stage 'bin'
$vcpkgBin = Join-Path $BuildDir 'vcpkg_installed\x64-windows\bin'

if (Test-Path $stage) {
    Remove-Item $stage -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $stage | Out-Null
Invoke-Native cmake @('--install', $BuildDir, '--prefix', $stage)

# Qt. The GL video item is only imported when qml6glsink loads, so its module may be missing here; that is fine.
$env:PATH = "$qt\bin;$env:PATH"
Invoke-Native "$qt\bin\windeployqt.exe" @(
    '--release', '--qmldir', (Join-Path $repo 'receiver\src\qml'), '--no-translations', '--no-compiler-runtime',
    '--no-opengl-sw', '--force-openssl', (Join-Path $bin 'casthop.exe'))

# GStreamer plugins. qml6 stays out on purpose: it is tied to the Qt it was built with, and the CPU path is the one
# CastHop supports on Windows. It is still used when a compatible plugin is present (see docs/TESTING.md).
$requiredPlugins = @(
    'gstcoreelements', 'gstapp', 'gstvideoparsersbad', 'gstlibav', 'gstd3d11', 'gstvideoconvertscale',
    'gstaudioconvert', 'gstaudioresample', 'gstautodetect', 'gstopus')
# autoaudiosink picks the best of these; at least one has to exist.
$audioPlugins = @('gstwasapi2', 'gstwasapi', 'gstdirectsound')

$pluginDir = Join-Path $stage 'lib\gstreamer-1.0'
$scannerDir = Join-Path $stage 'libexec\gstreamer-1.0'
New-Item -ItemType Directory -Force -Path $pluginDir, $scannerDir | Out-Null
$roots = New-Object System.Collections.Generic.List[string]
foreach ($plugin in $requiredPlugins + $audioPlugins) {
    $source = Join-Path $gst "lib\gstreamer-1.0\$plugin.dll"
    if (Test-Path $source) {
        Copy-Item $source $pluginDir
        $roots.Add((Join-Path $pluginDir "$plugin.dll"))
    } elseif ($requiredPlugins -contains $plugin) {
        throw "Required GStreamer plugin is missing: $plugin"
    }
}
if (-not ($audioPlugins | Where-Object { Test-Path (Join-Path $pluginDir "$_.dll") })) {
    throw 'No GStreamer audio output plugin was found'
}
Copy-Item (Join-Path $gst 'libexec\gstreamer-1.0\gst-plugin-scanner.exe') $scannerDir
$roots.Add((Join-Path $scannerDir 'gst-plugin-scanner.exe'))
$roots.Add((Join-Path $bin 'casthop.exe'))

# Qt finds OpenSSL at run time, so the import tables do not show it.
foreach ($dll in 'libcrypto-3-x64.dll', 'libssl-3-x64.dll') {
    $source = Join-Path $vcpkgBin $dll
    if (-not (Test-Path $source)) {
        throw "OpenSSL DLL is missing: $source"
    }
    Copy-Item $source $bin -Force
    $roots.Add((Join-Path $bin $dll))
}

# Follow the import tables, so that the package holds exactly the DLLs that are reachable.
$searchDirs = @($vcpkgBin, (Join-Path $gst 'bin'))
$seen = @{}
$queue = New-Object System.Collections.Generic.Queue[string]
foreach ($root in $roots) {
    $queue.Enqueue($root)
}
while ($queue.Count -gt 0) {
    $file = $queue.Dequeue()
    if ($seen.ContainsKey($file)) {
        continue
    }
    $seen[$file] = $true
    $imports = & dumpbin.exe /nologo /dependents $file | ForEach-Object {
        if ($_ -match '^\s+(\S+\.dll)\s*$') { $Matches[1] }
    }
    foreach ($import in $imports) {
        $target = Join-Path $bin $import
        if (Test-Path $target) {
            $queue.Enqueue($target)
            continue
        }
        foreach ($dir in $searchDirs) {
            $source = Join-Path $dir $import
            if (Test-Path $source) {
                Copy-Item $source $bin
                $queue.Enqueue($target)
                break
            }
        }
    }
}

# The Visual C++ runtime, so that nothing has to be installed first.
$crt = Get-ChildItem -Path (Join-Path $env:VCToolsRedistDir 'x64') -Directory -Filter 'Microsoft.VC*.CRT' | Select-Object -First 1
if (-not $crt) {
    throw "Visual C++ runtime was not found under $env:VCToolsRedistDir"
}
Copy-Item (Join-Path $crt.FullName '*.dll') $bin

$licenses = Join-Path $stage 'licenses'
New-Item -ItemType Directory -Force -Path $licenses | Out-Null
foreach ($file in 'LICENSE', 'NOTICE', 'docs\THIRD_PARTY.md') {
    Copy-Item (Join-Path $repo $file) $licenses
}

foreach ($file in 'bin\casthop.exe', 'bin\Qt6Quick.dll', 'bin\platforms\qwindows.dll', 'bin\tls\qopensslbackend.dll', 'bin\qml\QtQuick\Controls') {
    if (-not (Test-Path (Join-Path $stage $file))) {
        throw "Package is incomplete: $file"
    }
}

# The runner image has Inno Setup; a developer machine may have it elsewhere.
$iscc = Get-Command iscc.exe -ErrorAction SilentlyContinue | ForEach-Object Source
if (-not $iscc) {
    $iscc = Get-ChildItem "${env:ProgramFiles(x86)}\Inno Setup*\ISCC.exe", "$env:ProgramFiles\Inno Setup*\ISCC.exe" -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $iscc) {
    throw 'Inno Setup was not found (choco install innosetup)'
}
Invoke-Native $iscc @(
    "/DAppVersion=$(Get-ProjectVersion)", "/DStageDir=$stage", "/DOutputDir=$OutDir",
    "/DIconFile=$(Join-Path $repo 'receiver\data\windows\casthop.ico')",
    (Join-Path $PSScriptRoot 'casthop.iss'))
Write-Host "Installer: $(Join-Path $OutDir 'casthop-setup-x86_64.exe')"
