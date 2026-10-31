@echo off
rem Startet Dread Dawn mit dem Deutsch-Paket (Einzelspieler).
rem Fuer den Coop "Dread Dawn COOP.bat" starten - mit installiertem Deutsch-Paket ist der Coop ebenfalls deutsch.
cd /d "%~dp0"
if not exist "deutsch\deutsch-mod.jar" (
  echo Der Ordner "deutsch" fehlt. Bitte den Deutsch-Mod in den Dread-Dawn-Ordner entpacken.
  pause
  exit /b 1
)
start "" "%~dp0Word\Etc\doot\effc\MyPanel\jreing\bin\javaw.exe" -jar "%~dp0deutsch\deutsch-mod.jar"
