@echo off
rem Startet Dread Dawn mit dem Coop-Mod (Vollbild/Fenster wie in den Spiel-Einstellungen).
cd /d "%~dp0"
if not exist "coop\coop-mod.jar" (
  echo Der Ordner "coop" fehlt. Bitte den Mod in den Dread-Dawn-Ordner entpacken.
  pause
  exit /b 1
)
start "" "%~dp0Word\Etc\doot\effc\MyPanel\jreing\bin\javaw.exe" -jar "%~dp0coop\coop-mod.jar"
