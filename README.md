# Dread Dawn – Coop-Mod für 2 Spieler

Mit diesem Mod spielt ihr **Dread Dawn zu zweit in derselben Welt**: Einer hostet, ein Freund tritt bei. Verbunden wird über das Netzwerk, zum Beispiel mit **Radmin VPN** oder im LAN.

- **Version:** 1.1
- **Keine Originaldateien werden verändert.** Der Mod liest beim Start das installierte Spiel und baut daraus im Ordner `coop\cache` die Coop-Version. Nach einem Spiel-Update passiert das automatisch neu.
- **Kein extra Java nötig.** Die Startdateien benutzen das Java, das beim Spiel dabei ist.

## Installation

1. Unter **[Releases](https://github.com/Minnteee/DreadDawn-Coop-Mod/releases/latest)** die Datei `DreadDawn-Coop-Mod-1.1.zip` herunterladen.
2. Den Inhalt in den Dread-Dawn-Ordner entpacken, also dorthin, wo der Ordner `Word` liegt. Danach liegen dort:
   - `Dread Dawn COOP.bat` (normaler Start)
   - `Dread Dawn COOP (Fenster 800x600).bat` (kleines Fenster, z. B. zum Testen)
   - der Ordner `coop` mit `coop-mod.jar`, Anleitung (`LIESMICH.txt`), Coop-Liste und Lizenz
3. Beide Spieler brauchen dieselbe Spielversion und genau dieselbe `coop-mod.jar`. Sonst wird die Verbindung mit einer Meldung abgewiesen.

**Entfernen:** die zwei `.bat`-Dateien und den Ordner `coop` löschen (beim Freund zusätzlich `Save\coop`).

## Spielen

1. Beide starten Radmin VPN und sind im selben Netzwerk.
2. Beide starten `Dread Dawn COOP.bat`.
3. **Host:** Hauptmenü → **Coop hosten** → Namen eingeben → **Hosten**. Danach wie gewohnt *Continue*, *New Game* oder *Load* wählen. Das Fenster zeigt deine IP an (Radmin-Adressen beginnen mit `26.`). Fragt die Windows-Firewall nach „Java“: **zulassen**, am besten für private und öffentliche Netzwerke.
4. **Freund:** Hauptmenü → **Coop beitreten** → Namen und IP des Hosts eingeben → **Verbinden**. Er bekommt automatisch die Welt des Hosts und erscheint neben ihm.

Port: `7777` (TCP), änderbar im Dialog oder in `coop\coop.properties`.

Die Welt gehört dem Host und wird in **seinem** Spielstand gespeichert, auch der Charakter des Freundes (Inventar, Ausrüstung, Werte, Aussehen). Beim nächsten Beitritt ist alles wieder da. Die eigenen Spielstände des Freundes werden nicht angefasst.

## Was synchronisiert wird

Der Host führt das Spiel: Er berechnet Zombies, NPCs, Story und Welt. Der Gast sieht alles live und schickt seine eigenen Aktionen an den Host. Die ausführliche Liste mit allen 58 Punkten steht in [`COOP-LISTE.txt`](dist/coop/COOP-LISTE.txt).

| Bereich | Was beide gleich sehen und erleben |
|---|---|
| **Spieler** | Figur des anderen mit allen Bewegungen, Waffe, Schüssen, Schlägen, Mündungsfeuer, Sound und Taschenlampe · Name und Lebensbalken über dem Kopf · Kugel-Linien (auch von NPCs und Geschützen) · Sprechblasen · kein Friendly Fire |
| **Gast-Charakter** | Eigenes Inventar, Ausrüstung, Leben, Hunger, Durst, Level, Fertigkeiten und Aussehen · Startausrüstung wie im Einzelspieler · alles wird beim Host mitgespeichert |
| **Zombies & NPCs** | Gleiche Positionen bei beiden · Zombies jagen beide Spieler · volle Zombie-KI auch rund um den Gast, egal wie weit ihr auseinander seid · Treffer des Gasts zählen beim Host · brennende Zombies |
| **Handel & Begleiter** | Der Gast handelt selbst (Kaufen, Verkaufen, Reparieren) · Begleiter anheuern, entlassen und kommandieren |
| **Kampf & Gefahren** | Granaten, Molotows, Flammenwerfer, Säure, Explosionen, Fässer, Boomer-Gas, Fallen, Flammendüsen · Schaden zählt nur einmal |
| **RC-Autos & Skateboards** | Wer fährt, steuert selbst; der andere sieht es flüssig |
| **Story & Missionen** | Zwischensequenzen und Story-Dialoge bei beiden, weiter geht es erst, wenn beide gedrückt haben · Story-Ziele, Ereignisse und Missionen kann auch der Gast auslösen · Belohnungen für beide · gelber Ziel-Pfeil · Zombie-Gebiet, Abwurf und Lager-Belagerung |
| **Welt** | Tageszeit und Wetter · Schlafen nur, wenn beide schlafen · Kartenwechsel immer gemeinsam · Items am Boden, Kisten, Schränke, Leichen und Airdrops mit gleichem Inhalt · Schlösser, Schlüssel, Türen, Stromleitungen · Bauen, Abreißen, Bäume fällen, Ernten, Lagerfeuer, Hühner, Regentonnen |
| **Tod, Pause & Speichern** | Wer stirbt, schaut dem anderen zu · sind beide tot, lädt der Host den letzten Autosave und der Gast lädt mit · Pausemenü und Inventar halten die Welt nicht an · gespeichert wird beim Host · bei Verbindungsabbruch spielt der Host weiter |
| **Einstellungen** | Schwierigkeit und Zombie-Anzahl des Hosts gelten für beide |

**Bekannte Grenzen**

- Zombies erscheinen beim Gast mit etwa 50–100 ms Verzögerung (durch eine Vorhersage kaum sichtbar).
- Seil, Taser und Feuerwehrschlauch sieht nur, wer sie benutzt.
- Angeln macht jeder für sich.
- Minispiele (PC-Spiele, Fußball, Autorennen) und die Freundin-Szene sind im Coop gesperrt, weil sie auf eine eigene Karte wechseln.

## Wie der Mod funktioniert

| Teil | Ordner | Aufgabe |
|---|---|---|
| Launcher | `src/coop/launcher` | Wird von der `.bat` mit dem Java des Spiels gestartet, holt das Spiel aus `Dread dawn.exe`, patcht es beim ersten Start und startet es. |
| Patcher | `src/coop/patcher` | Verändert den Bytecode des Spiels mit [ASM](https://asm.ow2.io) und hängt an ausgewählten Stellen Aufrufe in den Mod ein. `Patches.java` listet alle Eingriffe. |
| Hooks | `src/coop/Hooks.java` | Die Einstiegspunkte, die das gepatchte Spiel aufruft. |
| Netzwerk | `src/coop/net` | Eigenes TCP-Protokoll (Port 7777) mit Versionsprüfung beim Verbinden. |
| Synchronisierung | `src/coop/sync` | Ein Modul pro Bereich, z. B. `PlayerSync`, `ZombieSync`, `HumanSync`, `StorySync`, `ItemSync`, `VehicleSync`, `WorldSync`. |
| Oberfläche | `src/coop/ui` | Coop-Menü und Anzeigen im Spiel. |
| Test-Werkzeuge | `src/coop/debug`, `tools/` | Fernsteuerung für automatische Tests mit zwei Spielen auf einem PC. Nur aktiv mit `-Dcoop.debugPort=…` und nur vom eigenen PC aus erreichbar. |

## Selbst bauen

Du brauchst Windows mit Git Bash, ein JDK 21 und ein installiertes Dread Dawn. Der Mod wird gegen das Spiel kompiliert; Dateien des Spiels sind nicht in diesem Repository.

```bash
JDK="/c/Program Files/Java/jdk-21" GAME="/d/Spiele/Dread Dawn" ./build.sh
```

Das Ergebnis liegt in `build/`: `coop-mod.jar` und das fertige Paket `DreadDawn-Coop-Mod.zip`. Mit `./build.sh install` wird der Mod zusätzlich in den Spielordner kopiert. Die beiden Pfade kannst du auch in eine Datei `local.env` neben `build.sh` schreiben.

## Lizenz

**Alle Rechte vorbehalten**, siehe [LICENSE](LICENSE).

- Erlaubt: den Mod herunterladen und spielen, ihn unverändert an Freunde weitergeben, den Quellcode ansehen.
- Nicht erlaubt ohne Erlaubnis: veränderte Versionen weitergeben oder veröffentlichen, den Code in andere Projekte übernehmen, den Mod verkaufen.

Dread Dawn gehört seinen Rechteinhabern; dieses Repository enthält keine Dateien des Spiels. Der Mod enthält die Bibliothek ASM (BSD-3-Clause, Lizenztext in [LICENSE](LICENSE)).

---

Dieser Mod wurde mit **Claude Opus 5.5** von Anthropic erstellt.
