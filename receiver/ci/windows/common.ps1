# Shared helpers for the Windows build scripts. Dot-source this file.
$ErrorActionPreference = 'Stop'

# PowerShell does not stop on a failing native command, so every one goes through this.
function Invoke-Native {
    param([Parameter(Mandatory)][string]$File, [string[]]$Arguments = @())
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$File failed with exit code $LASTEXITCODE"
    }
}

# Loads the MSVC x64 environment (cl, link, dumpbin, ninja, VCToolsRedistDir) into this session.
function Enter-MsvcShell {
    if ($env:VCToolsRedistDir -and (Get-Command cl.exe -ErrorAction SilentlyContinue)) {
        return
    }
    $vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
    $vs = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
    if (-not $vs) {
        throw 'Visual Studio with the C++ x64 tools was not found'
    }
    Import-Module (Join-Path $vs 'Common7\Tools\Microsoft.VisualStudio.DevShell.dll')
    Enter-VsDevShell -VsInstallPath $vs -SkipAutomaticLocation -DevCmdArguments '-arch=x64 -host_arch=x64' | Out-Null
}

function Get-RepoRoot {
    return (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
}

# The GStreamer development and runtime root, from the installer's variable or the default folders.
function Get-GStreamerRoot {
    $candidates = @(
        $env:GSTREAMER_1_0_ROOT_MSVC_X86_64,
        [Environment]::GetEnvironmentVariable('GSTREAMER_1_0_ROOT_MSVC_X86_64', 'Machine'),
        'C:\gstreamer\1.0\msvc_x86_64',
        'C:\Program Files\gstreamer\1.0\msvc_x86_64'
    )
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path (Join-Path $candidate 'bin\gst-inspect-1.0.exe'))) {
            return $candidate.TrimEnd('\')
        }
    }
    throw 'GStreamer was not found: run install-deps.ps1 or set GSTREAMER_1_0_ROOT_MSVC_X86_64'
}

function Get-QtRoot {
    if ($env:QT_ROOT_DIR -and (Test-Path (Join-Path $env:QT_ROOT_DIR 'bin\windeployqt.exe'))) {
        return $env:QT_ROOT_DIR
    }
    $tool = Get-Command windeployqt.exe -ErrorAction SilentlyContinue
    if ($tool) {
        return (Split-Path (Split-Path $tool.Source))
    }
    throw 'Qt was not found: set QT_ROOT_DIR to the msvc2022_64 folder'
}

# Version from project() in receiver/CMakeLists.txt, so that it is only written in one place.
function Get-ProjectVersion {
    $cmake = Get-Content (Join-Path (Get-RepoRoot) 'receiver\CMakeLists.txt') -Raw
    if ($cmake -notmatch '(?m)^\s*VERSION\s+(\d+\.\d+\.\d+)') {
        throw 'VERSION not found in receiver/CMakeLists.txt'
    }
    return $Matches[1]
}

# A PATH with nothing that could hide a missing file in the package.
function Set-CleanEnvironment {
    $env:PATH = "$env:SystemRoot\System32;$env:SystemRoot;$env:SystemRoot\System32\WindowsPowerShell\v1.0"
    foreach ($name in 'QT_PLUGIN_PATH', 'QML2_IMPORT_PATH', 'QML_IMPORT_PATH', 'GST_PLUGIN_PATH', 'GST_PLUGIN_SYSTEM_PATH') {
        Remove-Item "Env:$name" -ErrorAction SilentlyContinue
    }
}
