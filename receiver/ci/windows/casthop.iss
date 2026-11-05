; CastHop Windows installer. Built by package.ps1, which passes AppVersion, StageDir, OutputDir and IconFile.
; This file starts with a byte order mark: Inno Setup reads it as UTF-8 only with one.

#define AppName "CastHop"
#define ExeFile "casthop.exe"
#define TcpRule "CastHop TCP 47800"
#define MdnsRule "CastHop mDNS UDP 5353"

[Setup]
AppId={{7C1F6E0A-3B52-4E7B-9D0E-5A41C2F8B6D3}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher=ozkanceng
AppPublisherURL=https://github.com/ozkanceng/casthop
VersionInfoVersion={#AppVersion}
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
OutputDir={#OutputDir}
OutputBaseFilename=casthop-setup-x86_64
SetupIconFile={#IconFile}
UninstallDisplayIcon={app}\bin\{#ExeFile}
UninstallDisplayName={#AppName}
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
; Windows 10 1809: the oldest version Qt 6.8 supports.
MinVersion=10.0.17763
; The firewall rules need administrator rights.
PrivilegesRequired=admin
UsedUserAreasWarning=no
CloseApplications=yes
RestartApplications=no

[Languages]
Name: "en"; MessagesFile: "compiler:Default.isl"
Name: "tr"; MessagesFile: "compiler:Languages\Turkish.isl"

[CustomMessages]
en.RemoveUserData=Also remove CastHop settings, the security certificate and paired devices?
tr.RemoveUserData=CastHop ayarları, güvenlik sertifikası ve eşleştirilmiş cihazlar da silinsin mi?

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[Files]
Source: "{#StageDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\bin\{#ExeFile}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\bin\{#ExeFile}"; Tasks: desktopicon

[Registry]
; The Settings switch writes this value. It only has to go away with the program.
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: none; ValueName: "{#AppName}"; Flags: dontcreatekey uninsdeletevalue

[Run]
; Deleting first keeps a reinstall from adding a second copy of each rule; a missing rule only sets an ignored exit code.
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""{#TcpRule}"""; Flags: runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""{#MdnsRule}"""; Flags: runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""{#TcpRule}"" dir=in action=allow protocol=TCP localport=47800 profile=any program=""{app}\bin\{#ExeFile}"""; Flags: runhidden
; mDNS is answered by the DNS Client service, not by casthop.exe, so this rule has no program.
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""{#MdnsRule}"" dir=in action=allow protocol=UDP localport=5353 profile=any"; Flags: runhidden
Filename: "{app}\bin\{#ExeFile}"; Description: "{cm:LaunchProgram,{#AppName}}"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "{sys}\taskkill.exe"; Parameters: "/F /IM {#ExeFile}"; Flags: runhidden; RunOnceId: "StopCastHop"
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""{#TcpRule}"""; Flags: runhidden; RunOnceId: "DeleteTcpRule"
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""{#MdnsRule}"""; Flags: runhidden; RunOnceId: "DeleteMdnsRule"

[Code]
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep <> usPostUninstall then
    Exit;
  // Rebuilt on the next start, so it never needs a question.
  DelTree(ExpandConstant('{localappdata}\{#AppName}'), True, True, True);
  // A silent uninstall keeps the pairings: IDNO is the default answer.
  if SuppressibleMsgBox(CustomMessage('RemoveUserData'), mbConfirmation, MB_YESNO or MB_DEFBUTTON2, IDNO) = IDYES then
  begin
    DelTree(ExpandConstant('{userappdata}\Yansit'), True, True, True);
    DelTree(ExpandConstant('{localappdata}\cache\yansit'), True, True, True);
    RegDeleteKeyIncludingSubkeys(HKEY_CURRENT_USER, 'Software\Yansit');
  end;
end;
