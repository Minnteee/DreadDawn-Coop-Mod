DREAD DAWN CO-OP MOD  (2 players, e.g. over Radmin VPN)
=======================================================
Version 1.2

INSTALLATION (the same for both players)
----------------------------------------
1. Copy the contents of this package into the Dread Dawn folder, i.e. where the folder
   "Word" is. Afterwards it contains:
     - Dread Dawn COOP.bat
     - Dread Dawn COOP (Fenster 800x600).bat   (small window, e.g. for testing)
     - coop\coop-mod.jar
2. Both players need the same game version and exactly the same coop-mod.jar (version 1.2).
   Other versions are rejected with a message when connecting.
3. The mod does NOT change any original file. On the first start it reads the game from
   "Word\Etc\doot\effc\MyPanel\Dread dawn.exe" and builds the co-op version from it
   (coop\cache). After a game update this happens again automatically.
   No extra Java is needed: the start files use the Java that comes with the game.

PLAYING
-------
1. Both start Radmin VPN and join the same Radmin network.
2. Both start "Dread Dawn COOP.bat".
3. HOST: main menu -> "Host co-op" -> enter a name -> "Host".
   Then choose Continue / New Game / Load as usual.
   The dialog shows your IP (Radmin addresses start with 26.).
   The first time, the Windows firewall asks about "Java" -> ALLOW
   (best for private AND public networks, Radmin often counts as public).
4. FRIEND: main menu -> "Join co-op" -> name + the host's IP -> "Connect".
   He receives the host's world automatically and appears next to him.
5. To stop: main menu -> "Co-op: disconnect" or "Co-op: stop hosting".

GOOD TO KNOW
------------
- The world belongs to the host and is saved in HIS save. The friend's character (bag,
  equipment, stats, look, companions) is saved in the host's save too and comes back the
  next time he joins. The friend's own single player saves are never touched (the co-op
  world is kept in his Save\coop).
- DOWN AND REVIVE: at 0 health you go down. Your partner has 15 seconds to come to you and
  press E. If nobody helps, you are dead and watch your partner (you can open the map).
  A dead player comes back at the next map change or when a cutscene starts.
  If both are down / dead: the normal death screen, the host presses Space and loads the
  last autosave, the friend loads with him.
- SAVING: the host saves as usual. The friend can save for the host with F5 or "Save" in
  his pause menu: the host saves into the slot he loaded or saved last (otherwise the
  autosave). A dead host can still save.
- The other player is a yellow dot on the minimap and on the map. When he is off screen,
  a yellow arrow with his name points to him.
- Cutscenes and story dialogs run for both. Continue (Space) or skip only when BOTH
  pressed - the bottom of the screen shows who is still missing.
- Companions belong to the player who hired or last commanded them; "Follow" means "follow
  me". Command mode (Q) and build mode work for both players.
- Sleeping: time only jumps when BOTH slept (within one minute).
- Keys count for both; a lock or a box one player opens is open for both.
- Mini-games (PC games, football, car race) and the girlfriend scene are blocked in co-op,
  because they switch to a map of their own.
- What is synchronised in detail: see coop\COOP-LIST.txt.
- Port: 7777 (TCP). Can be changed in the dialog or in coop\coop.properties.
- Settings in coop\coop.properties:  showOwnName=true  -> show your own name too.
- Problems? Log files are in coop\logs\.
- German: with the German language pack (DreadDawn-German-Pack) installed, the game and the
  co-op messages are German. Each player sees the language installed on his own PC.

REMOVING
--------
Delete the two .bat files and the folder "coop" (on the friend's PC also "Save\coop").
Nothing else was changed.

Made with Claude Opus 5.5 (Anthropic) for Minnteee - https://github.com/Minnteee
