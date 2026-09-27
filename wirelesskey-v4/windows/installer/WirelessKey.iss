#define MyAppName "WirelessKey Receiver"
#define MyAppVersion "4.5"
#define MyAppPublisher "WirelessKey"
#define MyAppExeName "WirelessKeyReceiver.exe"

[Setup]
AppId={{A9F1C4C5-7B45-4D67-9A24-9D9B129A4450}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\WirelessKey
DefaultGroupName=WirelessKey
DisableProgramGroupPage=yes
OutputDir=output
OutputBaseFilename=WirelessKeySetup-4.5
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
UninstallDisplayIcon={app}\{#MyAppExeName}
SetupIconFile=..\WirelessKey.ico
CloseApplications=yes
RestartApplications=no
ChangesAssociations=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "startup"; Description: "Start WirelessKey automatically when I sign in"; GroupDescription: "Startup"; Flags: unchecked
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Shortcuts"; Flags: unchecked

[Files]
Source: "..\publish\WirelessKeyReceiver.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "..\publish\README.txt"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\WirelessKey Receiver"; Filename: "{app}\{#MyAppExeName}"
Name: "{group}\Uninstall WirelessKey"; Filename: "{uninstallexe}"
Name: "{autodesktop}\WirelessKey Receiver"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "WirelessKeyReceiver"; ValueData: """{app}\{#MyAppExeName}"" --hidden"; Tasks: startup; Flags: uninsdeletevalue

[Run]
Filename: "{cmd}"; Parameters: "/C netsh advfirewall firewall delete rule name=""WirelessKey Receiver TCP"" >nul 2>&1"; Flags: runhidden waituntilterminated
Filename: "{cmd}"; Parameters: "/C netsh advfirewall firewall add rule name=""WirelessKey Receiver TCP"" dir=in action=allow protocol=TCP localport=8765-8775 program=""{app}\{#MyAppExeName}"" profile=private enable=yes"; Flags: runhidden waituntilterminated
Filename: "{cmd}"; Parameters: "/C netsh advfirewall firewall delete rule name=""WirelessKey Discovery UDP"" >nul 2>&1"; Flags: runhidden waituntilterminated
Filename: "{cmd}"; Parameters: "/C netsh advfirewall firewall add rule name=""WirelessKey Discovery UDP"" dir=in action=allow protocol=UDP localport=8766 program=""{app}\{#MyAppExeName}"" profile=private enable=yes"; Flags: runhidden waituntilterminated
Filename: "{app}\{#MyAppExeName}"; Description: "Launch WirelessKey Receiver"; Flags: nowait postinstall skipifsilent

[UninstallRun]
Filename: "{cmd}"; Parameters: "/C netsh advfirewall firewall delete rule name=""WirelessKey Receiver TCP"" >nul 2>&1"; Flags: runhidden waituntilterminated
Filename: "{cmd}"; Parameters: "/C netsh advfirewall firewall delete rule name=""WirelessKey Discovery UDP"" >nul 2>&1"; Flags: runhidden waituntilterminated

[Code]
function InitializeSetup(): Boolean;
begin
  Result := True;
end;
