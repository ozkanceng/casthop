# Installs casthop-setup-x86_64.exe, checks what it set up, runs the conformance test against the installed program,
# then uninstalls and checks that nothing is left. Administrator rights needed.
#   receiver\ci\windows\test-package.ps1 -Setup out\casthop-setup-x86_64.exe -FakeSender fake-sender\bin\fake-sender.bat
param(
    [Parameter(Mandatory)][string]$Setup,
    [Parameter(Mandatory)][string]$FakeSender,
    [string]$WorkDir = (Join-Path $env:TEMP 'casthop-conformance')
)
. (Join-Path $PSScriptRoot 'common.ps1')

$repo = Get-RepoRoot
$setupPath = (Resolve-Path $Setup).Path
$senderPath = (Resolve-Path $FakeSender).Path
$python = (Get-Command python).Source
$ffmpeg = (Get-Command ffmpeg).Source
$app = Join-Path $env:ProgramFiles 'CastHop'
$exe = Join-Path $app 'bin\casthop.exe'
$startMenu = Join-Path $env:ProgramData 'Microsoft\Windows\Start Menu\Programs\CastHop.lnk'
$ruleNames = 'CastHop TCP 47800', 'CastHop mDNS UDP 5353'
New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null

function Assert-That {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) {
        throw "FAILED: $Message"
    }
    Write-Host "ok: $Message"
}

function Test-FirewallRule {
    param([string]$Name)
    return $null -ne (Get-NetFirewallRule -DisplayName $Name -ErrorAction SilentlyContinue)
}

Write-Host '== Install'
$install = Start-Process $setupPath -Wait -PassThru -ArgumentList @(
    '/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', "/LOG=`"$WorkDir\install.log`"")
Assert-That ($install.ExitCode -eq 0) "installer exit code ($($install.ExitCode))"
Assert-That (Test-Path $exe) 'program installed'
Assert-That (Test-Path $startMenu) 'Start menu entry'
foreach ($name in $ruleNames) {
    Assert-That (Test-FirewallRule $name) "firewall rule: $name"
}

# From here on nothing outside the installed folder can supply a DLL, a plugin or a QML module.
Set-CleanEnvironment
$env:QT_FORCE_STDERR_LOGGING = '1'

Write-Host '== Interface starts from the installed folder'
$env:QT_QUICK_BACKEND = 'software'
$env:QT_FATAL_WARNINGS = '1'
$gui = Start-Process $exe -Wait -PassThru -RedirectStandardError "$WorkDir\gui.log" -ArgumentList @(
    '--windowed', '--exit-after', '4000', '--port', '47897')
Get-Content "$WorkDir\gui.log" -ErrorAction SilentlyContinue | Write-Host
Assert-That ($gui.ExitCode -eq 0) "interface exit code ($($gui.ExitCode))"
Remove-Item Env:QT_QUICK_BACKEND, Env:QT_FATAL_WARNINGS

Write-Host '== Conformance against the installed program'
$receiver = "`"$exe`" --headless --port {port} --data-dir {data} --pairing-file {pairing} --stats-file {stats} --qr-host 127.0.0.1"
$env:YANSIT_ALLOW_SW_HEVC = '1'
Invoke-Native $python @(
    '-I', (Join-Path $repo 'tools\conformance\run.py'), '--fake-sender', $senderPath, '--receiver', $receiver,
    '--ffmpeg', $ffmpeg, '--work', (Join-Path $WorkDir 'run'))

Write-Host '== Uninstall'
$uninstaller = Join-Path $app 'unins000.exe'
Assert-That (Test-Path $uninstaller) 'uninstaller present'
Start-Process $uninstaller -Wait -ArgumentList @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART')
# The uninstaller moves itself to a temporary file and returns before it has finished.
$deadline = (Get-Date).AddSeconds(60)
while ((Test-Path $app) -and (Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 1
}
Assert-That (-not (Test-Path $app)) 'program folder removed'
Assert-That (-not (Test-Path $startMenu)) 'Start menu entry removed'
foreach ($name in $ruleNames) {
    Assert-That (-not (Test-FirewallRule $name)) "firewall rule removed: $name"
}
$key = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\{7C1F6E0A-3B52-4E7B-9D0E-5A41C2F8B6D3}_is1'
Assert-That (-not (Test-Path -LiteralPath $key)) 'uninstall entry removed'
Write-Host 'Package test passed'
