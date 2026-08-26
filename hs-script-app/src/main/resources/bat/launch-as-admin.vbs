Option Explicit

Dim fileSystem, shell, scriptDirectory, helperPath, arguments
Set fileSystem = CreateObject("Scripting.FileSystemObject")
Set shell = CreateObject("Shell.Application")

scriptDirectory = fileSystem.GetParentFolderName(WScript.ScriptFullName)
helperPath = fileSystem.BuildPath(scriptDirectory, "launch-newest-as-admin.ps1")

If Not fileSystem.FileExists(helperPath) Then
    WScript.Echo "The newest-build launcher was not found: " & helperPath
    WScript.Quit 1
End If

' Elevate the helper itself. It replaces only an older hs-script JVM before
' starting the newest deployed JAR; Hearthstone.exe is never touched.
arguments = "-NoProfile -ExecutionPolicy Bypass -File """ & helperPath & """"
shell.ShellExecute "powershell.exe", arguments, scriptDirectory, "runas", 1
