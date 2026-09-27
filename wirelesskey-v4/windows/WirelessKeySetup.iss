; WirelessKey v4.5 installer
#define MyAppName "WirelessKey"
#define MyAppVersion "4.5"
#define MyAppPublisher "WirelessKey"
#define MyAppExeName "WirelessKeyReceiver.exe"

[Setup]
AppId={{D0EA3F1C-8618-4C81-B661-3C15EDDD7E92}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\WirelessKey
DefaultGroupName=WirelessKey
DisableProgramGroupPage=yes
OutputDir=installer-output
OutputBaseFilename=WirelessKeySetup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
UninstallDisplayName=WirelessKey
UninstallDisplayIcon={app}\{#MyAppExeName}
SetupLogging=yes
CloseApplications=yes
RestartApplications=no

[Tasks]
Name: "startup"; Description: "Start WirelessKey automatically when I sign in"; GroupDescription: "Startup:"; Flags: unchecked
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Shortcuts:"; Flags: unchecked

[Files]
Source: "publish\WirelessKeyReceiver.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "README.txt"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\WirelessKey Receiver"; Filename: "{app}\{#MyAppExeName}"
Name: "{group}\Uninstall WirelessKey"; Filename: "{uninstallexe}"
Name: "{autodesktop}\WirelessKey"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "WirelessKeyReceiver"; ValueData: """{app}\{#MyAppExeName}"" --hidden"; Tasks: startup; Flags: uninsdeletevalue

[Run]
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""WirelessKey Receiver TCP"""; Flags: runhidden waituntilterminated; StatusMsg: "Refreshing WirelessKey firewall rules..."
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""WirelessKey Discovery UDP"""; Flags: runhidden waituntilterminated
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""WirelessKey Receiver TCP"" dir=in action=allow profile=private protocol=TCP localport=8765-8775 program=""{app}\{#MyAppExeName}"" enable=yes"; Flags: runhidden waituntilterminated; StatusMsg: "Allowing WirelessKey on private networks..."
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""WirelessKey Discovery UDP"" dir=in action=allow profile=private protocol=UDP localport=8766 program=""{app}\{#MyAppExeName}"" enable=yes"; Flags: runhidden waituntilterminated
Filename: "{app}\{#MyAppExeName}"; Description: "Launch WirelessKey Receiver"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""WirelessKey Receiver TCP"""; Flags: runhidden waituntilterminated
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""WirelessKey Discovery UDP"""; Flags: runhidden waituntilterminated

[Code]
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  ResultCode: Integer;
begin
  Exec(ExpandConstant('{sys}\taskkill.exe'),
       '/IM WirelessKeyReceiver.exe /F',
       '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Result := '';
end;