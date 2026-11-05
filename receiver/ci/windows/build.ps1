# Builds the receiver with MSVC and runs the unit tests.
#   receiver\ci\windows\build.ps1 [-BuildDir receiver\build-windows] [-SkipTests]
# Needs Qt (QT_ROOT_DIR), GStreamer (install-deps.ps1) and vcpkg (VCPKG_INSTALLATION_ROOT or VCPKG_ROOT).
param(
    [string]$BuildDir = '',
    [switch]$SkipTests
)
. (Join-Path $PSScriptRoot 'common.ps1')

$repo = Get-RepoRoot
if (-not $BuildDir) {
    $BuildDir = Join-Path $repo 'receiver\build-windows'
}
Enter-MsvcShell

$qt = Get-QtRoot
$gst = Get-GStreamerRoot
$vcpkg = if ($env:VCPKG_INSTALLATION_ROOT) { $env:VCPKG_INSTALLATION_ROOT } else { $env:VCPKG_ROOT }
if (-not $vcpkg) {
    throw 'vcpkg was not found: set VCPKG_ROOT'
}

# The tests and gtest discovery run from the build tree, so the DLLs have to be on PATH. Qt loads libssl at run time,
# so the copy step of vcpkg, which follows import tables, does not put it beside the program.
$env:PATH = "$qt\bin;$gst\bin;$BuildDir\vcpkg_installed\x64-windows\bin;$env:PATH"

Invoke-Native cmake @(
    '-S', (Join-Path $repo 'receiver'), '-B', $BuildDir, '-G', 'Ninja',
    '-DCMAKE_BUILD_TYPE=RelWithDebInfo',
    "-DCMAKE_TOOLCHAIN_FILE=$vcpkg\scripts\buildsystems\vcpkg.cmake",
    '-DVCPKG_TARGET_TRIPLET=x64-windows',
    "-DCMAKE_PREFIX_PATH=$qt",
    "-DGSTREAMER_ROOT=$gst")
Invoke-Native cmake @('--build', $BuildDir)

if (-not $SkipTests) {
    # The first start scans every plugin, which would use up the time limit of the smoke test.
    Invoke-Native "$gst\bin\gst-inspect-1.0.exe" @('avdec_h264')
    $env:QT_FORCE_STDERR_LOGGING = '1'
    $env:QT_QPA_PLATFORM = 'offscreen'
    # The offscreen platform looks for fonts in Qt's own folder, which has none. A warning there fails the smoke test.
    $env:QT_QPA_FONTDIR = "$env:WINDIR\Fonts"
    Invoke-Native ctest @('--test-dir', $BuildDir, '--output-on-failure', '--timeout', '120')
}
