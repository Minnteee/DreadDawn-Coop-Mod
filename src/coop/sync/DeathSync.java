package coop.sync;

import Game.MyPanel;
import coop.Coop;
import coop.Log;
import coop.T;
import coop.net.Msg;
import coop.net.Net;
import coop.net.Proto;
import javafx.scene.image.Image;

/**
 * Death in co-op:
 * - HP 0 while the partner still stands: DOWN. The player lies on the ground for 15 s, a countdown is shown
 *   at his body, and the partner revives him by pressing E next to him. Without help he dies.
 * - DEAD: he watches the partner (spectator camera, he may open the map) and comes back at the next map
 *   change or when a cutscene starts.
 * - nobody left standing (both down or dead, or a mission failed): the normal death screen for both; the
 *   host reloads the last autosave like in single player and the client receives that world again.
 */
public final class DeathSync {
    private DeathSync() {}

    public static final long DOWN_MS = 15_000;
    /** how close the partner has to be to revive (world units between the two figures) */
    public static final double REVIVE_RANGE = 140;

    /** PLAYER_DEAD kinds */
    static final int K_DEAD = 0;
    static final int K_DOWN = 1;
    static final int K_BACK = 2;
    /** REVIVE kinds */
    static final int R_REQUEST = 0;
    static final int R_DONE = 1;

    private static volatile boolean down;
    private static volatile long downUntil;
    private static volatile double downX;
    private static volatile double downY;
    /** local player is dead and spectates (the normal death screen was skipped) */
    private static volatile boolean spectating;
    /** the full death screen has been shown (nobody standing) */
    private static volatile boolean gameOver;
    private static boolean camSnap;
    private static volatile int respawnPending; // 0 none, 1 beside the partner, 2 where the scene puts us
    private static long lastReviveAsk;

    private static long now() {
        return System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ state

    /** down and waiting to be revived */
    public static boolean localDown() {
        return down && !gameOver;
    }

    /** down, dead or game over: the own figure is out of play */
    public static boolean localDead() {
        return down || spectating || gameOver;
    }

    public static boolean spectating() {
        return spectating && !gameOver;
    }

    public static boolean gameOver() {
        return gameOver;
    }

    public static long downLeftMs() {
        return down ? Math.max(0, downUntil - now()) : 0;
    }

    public static void reset() {
        down = false;
        spectating = false;
        gameOver = false;
        camSnap = false;
        respawnPending = 0;
    }

    /** the other player is down and can be revived */
    public static boolean partnerDown() {
        PlayerSync.State s = Puppet.latestState();
        return s != null && (s.flags & PlayerSync.F_DOWN) != 0;
    }

    /** the other player is dead for good (not just down) */
    public static boolean partnerGone() {
        return Puppet.isRemoteDead() && !partnerDown();
    }

    /** ms the downed partner has left (from his last state) */
    public static long partnerDownLeftMs() {
        PlayerSync.State s = Puppet.latestState();
        if (s == null || (s.flags & PlayerSync.F_DOWN) == 0) {
            return 0;
        }
        long age = Math.max(0, coop.net.Clock.now() - s.time);
        return Math.max(0, s.downLeft * 100L - age);
    }

    public static boolean partnerInReviveRange() {
        int k = Puppet.K;
        return MyPanel.getdistance2(MyPanel.doucount[0][300], MyPanel.doucount[1][300], MyPanel.doucount[0][k],
                MyPanel.doucount[1][k]) < REVIVE_RANGE * REVIVE_RANGE;
    }

    // ------------------------------------------------------------------ the game's death

    /** Hook at the start of MyPanel.death(word). true = the game's own death handling is skipped. */
    public static boolean death(int word) {
        if (MyPanel.WordView == null) {
            return false;
        }
        boolean outOfPlay = down || spectating;
        if (Coop.playing() && outOfPlay && !gameOver && word != MyPanel.WordView.DEATH) {
            // e.g. the mission failed while this player was down or dead: the normal screen for both
            gameOver = true;
            down = false;
            spectating = false;
            MyPanel.deathed = false;
            Net.send(new Msg(Proto.GAME_OVER).u8(word));
            Log.info("game over while out of play (word=" + word + ")");
            return false;
        }
        if (!Coop.playing()) {
            return false;
        }
        if (outOfPlay && !gameOver) {
            return true; // already down / dead: further damage changes nothing
        }
        if (MyPanel.deathed) {
            return false;
        }
        boolean partnerStands = Puppet.remoteKnown() && !Puppet.isRemoteDead();
        if (word == MyPanel.WordView.DEATH && partnerStands) {
            goDown();
            return true;
        }
        // nobody left standing, or a mission failure: game over for both, like in single player
        gameOver = true;
        Net.send(new Msg(Proto.GAME_OVER).u8(word));
        Log.info("game over (word=" + word + ")");
        return false;
    }

    private static void goDown() {
        if (Net.isClient()) {
            Profile.sendNow(); // the newest bag goes to the host before anything else happens
        }
        downX = MyPanel.doucount[0][300];
        downY = MyPanel.doucount[1][300];
        downUntil = now() + DOWN_MS;
        down = true;
        spectating = false;
        blockPlayer();
        MyPanel.ctheal[300] = 0;
        Net.send(new Msg(Proto.PLAYER_DEAD).u8(K_DOWN));
        Coop.message(T.s("You are down! {0} can revive you with E.", Puppet.name));
        Log.info("player down, " + DOWN_MS / 1000 + " s to be revived");
    }

    private static void goDead() {
        down = false;
        spectating = true;
        camSnap = true;
        MyPanel.deathed = true;
        Net.send(new Msg(Proto.PLAYER_DEAD).u8(K_DEAD));
        Coop.message(T.s("You are dead and watch {0}. You come back at the next map change or cutscene.", Puppet.name));
        Log.info("player dead (not revived): spectating");
    }

    /** what the game's death() does to the player, without the death screen and the reload */
    private static void blockPlayer() {
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

    /** Shows the normal death screen (nobody left standing). */
    private static void fullDeathScreen() {
        if (gameOver) {
            return;
        }
        gameOver = true;
        down = false;
        spectating = false;
        MyPanel.deathed = true;
        MyPanel.ctheal[300] = 0;
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
        Log.info("nobody left standing: death screen");
    }

    // ------------------------------------------------------------------ every frame

    /** Worker thread, every frame while playing together (after Move). */
    public static void tick() {
        if (gameOver) {
            return;
        }
        int r = respawnPending;
        if (r != 0) {
            respawnPending = 0;
            if (down || spectating) {
                respawn(r == 2);
                return;
            }
        }
        if (down) {
            MyPanel.deathed = true;
            // a story heal or a save must not half-revive the body (jumping but not walking)
            if (MyPanel.ctheal[300] > 0) {
                MyPanel.ctheal[300] = 0;
            }
            MyPanel.doucount[0][300] = downX;
            MyPanel.doucount[1][300] = downY;
            if (MyPanel.Bone != null) {
                MyPanel.Bone.ctcut[300] = -1;
            }
            if (!Puppet.remoteKnown() || Puppet.isRemoteDead()) {
                fullDeathScreen(); // the partner fell too: nobody can help any more
            } else if (now() >= downUntil) {
                goDead();
            }
        } else if (spectating) {
            if (!Puppet.remoteKnown() || Puppet.isRemoteDead()) {
                fullDeathScreen(); // the partner is down or dead as well
                return;
            }
            if (!Travel.busy()) {
                MyPanel.deathed = true;
            }
            if (MyPanel.ctheal[300] > 0) {
                MyPanel.ctheal[300] = 0;
            }
        }
    }

    /** FX thread, before the world is drawn: the camera follows the partner while we are dead. */
    public static void cameraFx() {
        if (!spectating || gameOver || !Puppet.active()) {
            return;
        }
        if (MyPanel.Animation != null && MyPanel.Animation.play) {
            return; // the cutscene owns the camera
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

    // ------------------------------------------------------------------ revive and respawn

    /** MyPanel.opendialog (E): next to the downed partner, E revives him. true = E is used up. */
    public static boolean tryRevive() {
        if (!MyPanel.opendialoged || !Coop.playing() || localDead() || !partnerDown() || !partnerInReviveRange()) {
            return false;
        }
        MyPanel.opendialoged = false;
        MyPanel.crafted = false;
        if (Coop.panel != null) {
            Coop.panel.takecar = false;
            Coop.panel.takeskateboard = false;
        }
        long t = now();
        if (t - lastReviveAsk > 400) {
            lastReviveAsk = t;
            Net.send(new Msg(Proto.REVIVE).u8(R_REQUEST));
            Log.info("revive requested");
        }
        return true;
    }

    /** a dead / downed player comes back: at a map change (before the trip) or when a cutscene starts */
    public static void requestRespawn(boolean sceneMovesUs) {
        if (down || spectating) {
            respawnPending = sceneMovesUs ? 2 : 1;
        }
    }

    /** host, start of traveling(): stand up before the trip (the game empties a dead traveller's bag) */
    public static void respawnBeforeTravel() {
        if ((down || spectating) && !gameOver) {
            standUp(MyPanel.doucount[0][300], MyPanel.doucount[1][300], respawnHp(), false);
            announceBack();
            Log.info("back in the game for the map change");
        }
    }

    private static void respawn(boolean inPlace) {
        double x = MyPanel.doucount[0][300];
        double y = MyPanel.doucount[1][300];
        if (!inPlace && Puppet.active()) {
            double[] p = besidePartner();
            x = p[0];
            y = p[1];
        }
        standUp(x, y, respawnHp(), true);
        announceBack();
        Log.info("back in the game (" + (inPlace ? "cutscene" : "beside the partner") + ")");
    }

    private static void announceBack() {
        Net.send(new Msg(Proto.PLAYER_DEAD).u8(K_BACK));
        Coop.message(T.s("You are back in the game."));
    }

    private static double[] besidePartner() {
        int k = Puppet.K;
        double px = MyPanel.doucount[0][k];
        double py = MyPanel.doucount[1][k];
        int[] out = new int[2];
        double[][] tries = {{60, 0}, {-60, 0}, {0, 60}, {0, -60}, {45, 45}, {-45, -45}};
        try {
            for (double[] d : tries) {
                if (MyPanel.position.findposition((int) (px + d[0]), (int) (py + d[1]), out)) {
                    return new double[] {px + d[0], py + d[1]};
                }
            }
        } catch (Throwable t) {
            // fall back to the partner's spot
        }
        return new double[] {px, py};
    }

    static double reviveHp() {
        return Math.min(MyPanel.heal, Math.max(20, MyPanel.heal * 0.3));
    }

    static double respawnHp() {
        return Math.min(MyPanel.heal, Math.max(30, MyPanel.heal * 0.5));
    }

    /** the local player stands up alive at x, y */
    private static void standUp(double x, double y, double hp, boolean moveCamera) {
        down = false;
        spectating = false;
        gameOver = false;
        int p = 300;
        MyPanel.deathed = false;
        MyPanel.ctheal[p] = hp;
        MyPanel.healplayer = hp;
        MyPanel.doucount[0][p] = x;
        MyPanel.doucount[1][p] = y;
        MyPanel.gocounter[0][p] = x;
        MyPanel.gocounter[1][p] = y;
        MyPanel.ctspeed[0][p] = 0;
        MyPanel.ctspeed[1][p] = 0;
        try {
            int[] out = new int[2];
            if (MyPanel.position.findposition((int) x, (int) y, out)) {
                MyPanel.count[0][p] = out[0];
                MyPanel.count[1][p] = out[1];
            }
        } catch (Throwable t) {
            // keep the old cell
        }
        MyPanel.cteamatekind[p] = 1;
        MyPanel.ctcontrolkind[p] = 0;
        if (MyPanel.Bone != null) {
            MyPanel.Bone.ctcut[p] = -1;
            MyPanel.Bone.ctdeath[p] = true;
            MyPanel.Bone.ctstand[p] = true; // only the getting-up part of the fall animation
        }
        MyPanel.ctinjury[p] = 0;
        MyPanel.ctfireang[p] = 0;
        MyPanel.ct_turn_z_time[p] = 0;
        MyPanel.jumpcountall[p] = 0;
        MyPanel.jumpcountspeed[p] = 0;
        MyPanel.jumpcountrest[p] = 0;
        MyPanel.ctsleeping[p] = false;
        MyPanel.ctskateing[p] = false;
        MyPanel.ctrun[p] = false;
        MyPanel.playerslide = false;
        MyPanel.playerflykick = false;
        MyPanel.playerfarm = false;
        MyPanel.boxopenid = -1;
        MyPanel.ctfall[p] = true;
        MyPanel.ctfallkind[p] = 0;
        MyPanel.ctfalltime[p] = MyPanel.fallstandtime;
        MyPanel.Qed = MyPanel.controlmode;
        if (!MyPanel.controlmode) {
            MyPanel.addplayer = 0;
        }
        MyPanel.Hint.hintimageing = false;
        MyPanel.Hint.hintimaged = false;
        MyPanel.Hint.hintimagefinished = false;
        if (MyPanel.WordView != null) {
            MyPanel.WordView.time = 0;
            MyPanel.WordView.wordview = null;
        }
        MyPanel.lastdeadx = -1;
        MyPanel.lastdeady = -1;
        MyPanel.deadthsound = false;
        MyPanel.SPACE = false;
        MyPanel.opendialoged = false;
        if (Coop.panel != null) {
            Coop.panel.E = false;
        }
        if (moveCamera) {
            MyPanel.keyx = -x;
            MyPanel.keyy = -y;
            MyPanel.keyxed = -x;
            MyPanel.keyyed = -y;
            MyPanel.keyxsmooth = -x;
            MyPanel.keyysmooth = -y;
        }
        Coop.runFx(() -> {
            try {
                if (MyPanel.Voice != null && MyPanel.Voice.themeP != null) {
                    MyPanel.Voice.themeP.stop(); // the death theme
                }
            } catch (Throwable t) {
                // ignore
            }
        });
        if (Net.isClient()) {
            Profile.sendSoon();
        }
    }

    // ------------------------------------------------------------------ saves made while out of play

    private static boolean saveFixed;
    private static double savedHeal;
    private static double savedHealPlayer;
    private static double savedX;
    private static double savedY;

    /** Save.saveOther head: a save made while down / dead stores the player alive (next to the partner) */
    public static void beforeSave() {
        saveFixed = false;
        if (!(down || spectating) || gameOver) {
            return;
        }
        saveFixed = true;
        savedHeal = MyPanel.ctheal[300];
        savedHealPlayer = MyPanel.healplayer;
        savedX = MyPanel.doucount[0][300];
        savedY = MyPanel.doucount[1][300];
        double hp = respawnHp();
        MyPanel.ctheal[300] = hp;
        MyPanel.healplayer = hp;
        if (spectating && Puppet.active()) {
            double[] p = besidePartner();
            MyPanel.doucount[0][300] = p[0];
            MyPanel.doucount[1][300] = p[1];
        }
    }

    /** Save.saveOther tail: back to the real (down / dead) state */
    public static void afterSave() {
        if (!saveFixed) {
            return;
        }
        saveFixed = false;
        MyPanel.ctheal[300] = savedHeal;
        MyPanel.healplayer = savedHealPlayer;
        MyPanel.doucount[0][300] = savedX;
        MyPanel.doucount[1][300] = savedY;
    }

    /**
     * MyPanel.unity closes the map, notices and story pictures while "deathed": a dead player who watches
     * his partner may look at the map and sees the story pictures and notices of the shared game
     */
    public static boolean unityDeathed() {
        return MyPanel.deathed && !spectating();
    }

    // ------------------------------------------------------------------ messages

    public static void handle(Msg m) {
        switch (m.type) {
            case Proto.PLAYER_DEAD: {
                int kind = m.remaining() > 0 ? m.u8() : K_DEAD;
                if (kind == K_DOWN) {
                    Coop.message(T.s("{0} is down! Go to him and press E to revive him.", Puppet.name));
                } else if (kind == K_BACK) {
                    Coop.message(T.s("{0} is back in the game.", Puppet.name));
                } else {
                    Coop.message(T.s("{0} is dead and watches you. He comes back at the next map change or cutscene.",
                            Puppet.name));
                }
                break;
            }
            case Proto.REVIVE: {
                int kind = m.remaining() > 0 ? m.u8() : R_REQUEST;
                if (kind == R_REQUEST) {
                    Coop.runSim(() -> {
                        if (down && !gameOver) {
                            standUp(downX, downY, reviveHp(), false);
                            Net.send(new Msg(Proto.REVIVE).u8(R_DONE));
                            Coop.message(T.s("{0} revived you.", Puppet.name));
                            Log.info("revived by the partner");
                        }
                    });
                } else {
                    Coop.message(T.s("You revived {0}.", Puppet.name));
                }
                break;
            }
            case Proto.GAME_OVER: {
                int word = m.u8();
                Coop.runSim(() -> {
                    if (gameOver) {
                        return;
                    }
                    if (down || spectating) {
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
