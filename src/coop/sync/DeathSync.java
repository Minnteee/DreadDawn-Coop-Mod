package coop.sync;

import Game.MyPanel;
import coop.Coop;
import coop.Log;
import coop.net.Msg;
import coop.net.Net;
import coop.net.Proto;
import javafx.scene.image.Image;

/**
 * Death in co-op:
 * - if the partner is still alive, the dead player watches the partner (spectator camera);
 * - when both are dead, both see the normal death screen and the host reloads the last
 *   autosave like in single player; the client then receives that world again.
 */
public final class DeathSync {
    private DeathSync() {}

    /** local player is dead and spectates (the normal death screen was skipped) */
    private static volatile boolean spectating;
    /** the full death screen has been shown (both dead) */
    private static volatile boolean gameOver;
    private static boolean camSnap;

    public static boolean localDead() {
        return spectating || gameOver;
    }

    public static boolean spectating() {
        return spectating && !gameOver;
    }

    public static boolean gameOver() {
        return gameOver;
    }

    public static void reset() {
        spectating = false;
        gameOver = false;
        camSnap = false;
    }

    /** Hook at the start of MyPanel.death(word). true = the game's own death handling is skipped. */
    public static boolean death(int word) {
        if (Coop.playing() && spectating && !gameOver && MyPanel.WordView != null && word != MyPanel.WordView.DEATH) {
            // e.g. the mission failed while this player was already dead and watching
            gameOver = true;
            spectating = false;
            MyPanel.deathed = false;
            Net.send(new Msg(Proto.GAME_OVER).u8(word));
            Log.info("Game over beim Zuschauen (word=" + word + ")");
            return false;
        }
        if (!Coop.playing() || MyPanel.deathed || MyPanel.WordView == null) {
            return false;
        }
        boolean partnerAlive = Puppet.active() && !Puppet.isRemoteDead();
        if (word == MyPanel.WordView.DEATH && partnerAlive) {
            softDeath();
            Net.send(new Msg(Proto.PLAYER_DEAD));
            Coop.message("Du bist gestorben - du schaust jetzt " + Puppet.name + " zu.");
            Log.info("Spieler tot, Partner lebt: Zuschauermodus");
            return true;
        }
        // partner dead too, or a mission failure: game over for both, like in single player
        gameOver = true;
        Net.send(new Msg(Proto.GAME_OVER).u8(word));
        Log.info("Game over (word=" + word + ")");
        return false;
    }

    private static void softDeath() {
        spectating = true;
        camSnap = true;
        MyPanel.deathed = true;
        // without the death picture the game would reload the save right away
        MyPanel.Hint.hintimageing = false;
        MyPanel.Hint.hintimaged = false;
        MyPanel.Hint.hintimagefinished = true;
        if (MyPanel.carmode) {
            MyPanel.carmode = false;
            try {
                MyPanel.carswitchDARK();
            } catch (Throwable t) {
                // ignore
            }
        }
        MyPanel.Qed = true;
        MyPanel.macgunmode = false;
        MyPanel.lookout = false;
        MyPanel.ctskateing[300] = false;
        MyPanel.ctsleeping[300] = false;
        MyPanel.lastdeadx = MyPanel.doucount[0][300];
        MyPanel.lastdeady = MyPanel.doucount[1][300];
        MyPanel.deadthsound = true;
    }

    /** Shows the normal death screen (used when the partner dies after us). */
    private static void fullDeathScreen() {
        if (gameOver) {
            return;
        }
        gameOver = true;
        MyPanel.deathed = true;
        try {
            MyPanel.WordView.word(MyPanel.WordView.DEATH, 300);
            MyPanel.BGMkind = 2;
            MyPanel.BGMchanged = true;
            MyPanel.Hint.hintimageing = true;
            MyPanel.Hint.hintimaged = false;
            MyPanel.Hint.hintimagefinished = false;
            MyPanel.picturehint = new Image(MyPanel.pathalling + "UI\\DEAD.jpg");
            MyPanel.Hint.death_hint_num = MyPanel.getrandom.nextInt(MyPanel.Hint.death_hint.length);
        } catch (Throwable t) {
            Log.error("fullDeathScreen", t);
        }
        Log.info("Beide tot: Todesbildschirm");
    }

    /** Worker thread, every frame while playing together. */
    public static void tick() {
        if (spectating && !gameOver) {
            if (Puppet.isRemoteDead() || !Puppet.active()) {
                // the partner died as well
                fullDeathScreen();
            } else if (!Travel.busy()) {
                // keep the dead player's input blocked
                MyPanel.deathed = true;
            }
        }
    }

    /** FX thread, before the world is drawn: the camera follows the partner while we are dead. */
    public static void cameraFx() {
        if (!spectating || gameOver || !Puppet.active()) {
            return;
        }
        int k = Puppet.K;
        MyPanel.keyx = -MyPanel.doucount[0][k];
        MyPanel.keyy = -MyPanel.doucount[1][k];
        MyPanel.keyxplus = 0;
        MyPanel.keyyplus = 0;
        if (camSnap) {
            camSnap = false;
            MyPanel.keyxsmooth = MyPanel.keyx;
            MyPanel.keyysmooth = MyPanel.keyy;
        }
    }

    public static void handle(Msg m) {
        switch (m.type) {
            case Proto.PLAYER_DEAD:
                Coop.message(Puppet.name + " ist gestorben und schaut dir zu.");
                break;
            case Proto.GAME_OVER: {
                int word = m.u8();
                Coop.runSim(() -> {
                    if (gameOver) {
                        return;
                    }
                    if (spectating) {
                        fullDeathScreen();
                    } else if (Net.isClient() && word != 0) {
                        // mission failed on the host: the team lost
                        gameOver = true;
                        MyPanel.death(word);
                    }
                });
                break;
            }
            default:
                break;
        }
    }
}
