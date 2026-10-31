# Dread Dawn – Co-op Mod for 2 Players

This mod lets you play **Dread Dawn together in the same world**: one player hosts, a friend joins. You connect over the network, for example with **Radmin VPN** or in a LAN.

- **Version:** 1.2
- **No original file is changed.** On start the mod reads the installed game and builds the co-op version from it in `coop\cache`. After a game update this happens again automatically.
- **No extra Java needed.** The start files use the Java that comes with the game.
- **Optional German language pack** that translates the whole game (see below).

## Installation

1. Download `DreadDawn-Coop-Mod-1.2.zip` from **[Releases](https://github.com/Minnteee/DreadDawn-Coop-Mod/releases/latest)**.
2. Unpack it into the Dread Dawn folder, i.e. where the folder `Word` is. Afterwards it contains:
   - `Dread Dawn COOP.bat` (normal start)
   - `Dread Dawn COOP (Fenster 800x600).bat` (small window, e.g. for testing)
   - the folder `coop` with `coop-mod.jar`, instructions (`README.txt`), the co-op list and the license
3. Both players need the same game version and exactly the same `coop-mod.jar`. Otherwise the connection is rejected with a message.

**Removing:** delete the two `.bat` files and the `coop` folder (on the friend's PC also `Save\coop`).

## Playing

1. Both start Radmin VPN and join the same network.
2. Both start `Dread Dawn COOP.bat`.
3. **Host:** main menu → **Host co-op** → enter a name → **Host**. Then choose *Continue*, *New Game* or *Load* as usual. The dialog shows your IP (Radmin addresses start with `26.`). If the Windows firewall asks about "Java": **allow** it, best for private and public networks.
4. **Friend:** main menu → **Join co-op** → enter a name and the host's IP → **Connect**. He receives the host's world automatically and appears next to him.

Port: `7777` (TCP), can be changed in the dialog or in `coop\coop.properties`.

The world belongs to the host and is saved in **his** save, together with the friend's character (bag, equipment, stats, look, companions). Next time the friend joins, everything is back. The friend's own saves are never touched.

## What is synchronised

The host runs the game: he simulates zombies, NPCs, story and world. The guest sees everything live and sends his own actions to the host. The full list with all points is in [`COOP-LIST.txt`](dist/coop/COOP-LIST.txt).

| Area | What both see and experience |
|---|---|
| **Players** | The other figure with all movements, weapon, shots, hits, muzzle flash, sound and flashlight · name and health bar above the head · bullet tracers (also of NPCs and turrets) · speech bubbles · no friendly fire |
| **Finding each other** | The partner is a yellow dot on the minimap and the map · off screen a yellow arrow with his name and distance points to him |
| **Down & revive** | At 0 health you go down: 15 s countdown at the body, the partner revives you with **E** · without help you are dead, watch your partner (map allowed) and come back at the next map change or cutscene |
| **Guest's character** | Own bag, equipment, health, hunger, thirst, level, skills and look · starting kit like in single player · all saved in the host's save |
| **Zombies & NPCs** | Same positions for both · zombies hunt both players · full zombie AI around the guest, however far apart you are · the guest's hits count on the host · burning zombies |
| **Trading & companions** | The guest trades himself (buy, sell, repair) · hire, dismiss and command companions · command mode (Q) and build mode for both |
| **Combat & hazards** | Grenades, molotovs, flamethrower, acid, explosions, barrels, boomer gas, traps, flame jets · damage counts once · the partner never sets off traps |
| **RC cars & skateboards** | The driver steers himself, the other sees it smoothly |
| **Story & missions** | Cutscenes and story dialogs for both, continue only when both pressed · the story waits for the guest after map changes, so nobody misses a scene · the guest can trigger story goals, events and missions · rewards for both · yellow guidance arrow, zombie area, air drop, camp siege |
| **World** | Time of day and weather · sleeping only when both sleep · map changes always together · items on the ground, boxes, cupboards, bodies and air drops with the same contents · locks, keys, doors, power lines · building, tearing down, trees, harvesting, bonfires, chickens, rain barrels |
| **Saving** | The host saves as usual · the guest can save for the host (F5 or "Save" in the pause menu) into the host's current slot · a dead host can save too |
| **Pause & disconnect** | Pause menu and inventory do not stop the world · if the connection drops, the host plays on |
| **Settings** | The host's difficulty and number of zombies count for both |

**Known limits**

- Zombies appear on the guest with about 50–100 ms delay (hardly visible thanks to prediction).
- Rope, taser and fire hose are only seen by the player using them.
- Fishing is done by each player for himself.
- Mini-games (PC games, football, car race) and the girlfriend scene are blocked in co-op, because they switch to a map of their own.

## New in 1.2

- **Down & revive** with a 15 s countdown and E, death and respawn at map changes / cutscenes for host and guest alike.
- **Smooth partner figure** over the internet: replay with the sender's timing, adaptive delay, correct leg animation, no more jittering.
- **Partner markers:** yellow dot on the minimap and the map, arrow with name at the screen edge; a dead player can open the map.
- **Guest saves for the host;** the host's current save slot is remembered; saves made while dead are safe.
- **No more lost items:** container changes are merged instead of overwritten, the guest's character is sent at once when his bag changes, a dead traveller keeps his bag, companions travel along.
- **No more missed cutscenes:** the story waits for the guest after map changes.
- **Command mode (Q) and build mode** work for the guest; the partner no longer sets off traps; a dead body can't be looted.
- The mod speaks **English** (German with the language pack).

## German language pack (Deutsch-Mod)

`DreadDawn-German-Pack-1.2.zip` (in the releases) makes the whole game German: menus, items, crafting, hints, story dialogs, cutscenes and the co-op messages. Unpack it into the Dread Dawn folder; then start `Dread Dawn DEUTSCH.bat` for single player, or `Dread Dawn COOP.bat` as usual – with the pack installed the co-op game is German too. Each player sees the language installed on his own PC. It works by reading German copies of the game's English text files (`deutsch\Word\...`); the original files stay untouched, and texts that exist only as pictures stay English. The German texts are in [`german/`](german/).

## How the mod works

| Part | Folder | Job |
|---|---|---|
| Launcher | `src/coop/launcher` | Started by the `.bat` with the game's Java, takes the game out of `Dread dawn.exe`, patches it on the first start and starts it. |
| Patcher | `src/coop/patcher` | Changes the game's bytecode with [ASM](https://asm.ow2.io) and hooks calls into the mod at chosen places. `Patches.java` lists every change. |
| Hooks | `src/coop/Hooks.java` | The entry points the patched game calls. |
| Network | `src/coop/net` | Own TCP protocol (port 7777) with a version check; `Clock` maps the partner's send times onto the own clock. |
| Synchronisation | `src/coop/sync` | One module per area, e.g. `PlayerSync`, `Puppet`, `ZombieSync`, `HumanSync`, `StorySync`, `ItemSync`, `DeathSync` (down/revive), `SaveSync`, `QMode`, `VehicleSync`, `WorldSync`. |
| Interface | `src/coop/ui` | Co-op menu, overlay (names, revive countdown, edge arrow), `MapMarkers` (minimap and map dots). |
| Languages | `src/coop/T.java`, `src/coop/Lang.java` | Player-visible texts in English with a German table; the German pack redirects the game's text files. |
| Test tools | `src/coop/debug`, `tools/` | Remote control for automated tests with two games on one PC (only active with `-Dcoop.debugPort=…`, only reachable from the own PC); `-Dcoop.simLag=40,60` simulates a slow internet line. |

## Building

You need Windows with Git Bash, a JDK 21 and an installed Dread Dawn. The mod is compiled against the game; no game files are in this repository.

```bash
JDK="/c/Program Files/Java/jdk-21" GAME="/d/Games/Dread Dawn" ./build.sh
```

The result is in `build/`: `coop-mod.jar`, the co-op package `DreadDawn-Coop-Mod.zip`, `deutsch-mod.jar` and the German package `DreadDawn-Deutsch-Mod.zip`. `./build.sh install` also copies everything into the game folder. You can put the two paths into a file `local.env` next to `build.sh` instead.

## License

**MIT** – see [LICENSE](LICENSE) and [NOTICE.txt](NOTICE.txt). You may use, change and extend the mod and its source code, also in your own projects. The only condition: keep the copyright notice, i.e. **credit Minnteee with a link to [github.com/Minnteee](https://github.com/Minnteee)** when you use this source code.

Dread Dawn belongs to its rights holders; this repository contains no files of the game. The German texts are a fan translation of the game's texts. The mod contains the library ASM (BSD-3-Clause, license text in [NOTICE.txt](NOTICE.txt)).

---

Made by [Minnteee](https://github.com/Minnteee) with **Claude Opus 5.5** by Anthropic.
