# Installs the GStreamer MSVC runtime and development packages (administrator rights needed).
# Qt comes from the Qt installer or install-qt-action; the other libraries come from vcpkg (receiver/vcpkg.json).
#   receiver\ci\windows\install-deps.ps1 [-Destination C:\gstreamer] [-DownloadDir downloads]
param(
    [string]$Destination = 'C:\gstreamer',
    [string]$DownloadDir = (Join-Path $env:TEMP 'casthop-gstreamer')
)
. (Join-Path $PSScriptRoot 'common.ps1')

$version = '1.26.11'
$packages = @{
    "gstreamer-1.0-msvc-x86_64-$version.msi"       = '31cbc21fa0950b5c1e79c80959b2799805cb05a7a35953a13a9f790776137605'
    "gstreamer-1.0-devel-msvc-x86_64-$version.msi" = 'af0a0720692052aaf5fb0cf2847ef34a87016ef0f914328d516f049e6c86f587'
}
$baseUrl = "https://gstreamer.freedesktop.org/data/pkg/windows/$version/msvc"

New-Item -ItemType Directory -Force -Path $DownloadDir | Out-Null
foreach ($name in $packages.Keys) {
    $file = Join-Path $DownloadDir $name
    if (-not (Test-Path $file) -or (Get-FileHash $file -Algorithm SHA256).Hash -ne $packages[$name]) {
        Write-Host "Downloading $name"
        Invoke-Native curl.exe @('--fail', '--location', '--silent', '--show-error', '--retry', '5', '--retry-all-errors', '--retry-delay', '10', '--output', $file, "$baseUrl/$name")
    }
    if ((Get-FileHash $file -Algorithm SHA256).Hash -ne $packages[$name]) {
        throw "Checksum mismatch: $name"
    }
    Write-Host "Installing $name"
    # ADDLOCAL=ALL: the default feature set leaves plugins out.
    $log = "$file.log"
    $process = Start-Process msiexec.exe -Wait -PassThru -ArgumentList @(
        '/i', "`"$file`"", '/qn', '/norestart', 'ADDLOCAL=ALL', "INSTALLDIR=`"$Destination`"", '/l*', "`"$log`"")
    if ($process.ExitCode -ne 0) {
        Get-Content $log -Tail 40 | Write-Host
        throw "msiexec failed for $name with exit code $($process.ExitCode)"
    }
}

# The packages add 1.0\msvc_x86_64 below the destination. The rest of the search covers a package that ignores it.
$root = Get-ChildItem -Path $Destination -Recurse -Filter 'gst-inspect-1.0.exe' -ErrorAction SilentlyContinue |
    Select-Object -First 1 | ForEach-Object { Split-Path (Split-Path $_.FullName) }
if (-not $root) {
    $root = Get-GStreamerRoot
}
Write-Host "GStreamer root: $root"
$env:GSTREAMER_1_0_ROOT_MSVC_X86_64 = $root
if ($env:GITHUB_ENV) {
    "GSTREAMER_1_0_ROOT_MSVC_X86_64=$root" | Out-File -FilePath $env:GITHUB_ENV -Append -Encoding utf8
    "$root\bin" | Out-File -FilePath $env:GITHUB_PATH -Append -Encoding utf8
}
