@echo off
rem Startet Dread Dawn mit dem Coop-Mod in einem 800x600-Fenster (z.B. zum Testen mit zwei Spielen auf einem PC).
rem Die eigenen Fenster-Einstellungen in option.txt bleiben dabei unveraendert.
cd /d "%~dp0"
if not exist "coop\coop-mod.jar" (
  echo Der Ordner "coop" fehlt. Bitte den Mod in den Dread-Dawn-Ordner entpacken.
  pause
  exit /b 1
)
start "" "%~dp0Word\Etc\doot\effc\MyPanel\jreing\bin\javaw.exe" -jar "%~dp0coop\coop-mod.jar" -Dcoop.window=800x600 -Dcoop.logtag=fenster
