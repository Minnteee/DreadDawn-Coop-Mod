package coop;

import Game.Bone;
import Game.MyPanel;
import coop.net.Net;
import coop.sync.Combat;
import coop.sync.DeathSync;
import coop.sync.Puppet;
import coop.sync.Travel;
import coop.sync.WorldTransfer;
import coop.ui.MenuUI;
import coop.ui.Overlay;
import java.awt.Dimension;
import java.awt.Robot;
import java.awt.Toolkit;
import javafx.stage.Stage;

/**
 * Entry points called from the patched game code. Every method catches its own errors:
 * a bug in the mod must never crash the game.
 */
public final class Hooks {
    private Hooks() {}

    public static MyPanel panel;
    public static Stage stage;

    // ---- window / test setup ---------------------------------------------------------------

    static int[] parsePair(String s, String sep) {
        if (s == null) {
            return null;
        }
        try {
            String[] p = s.trim().split(sep);
            return new int[] {Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim())};
        } catch (Exception e) {
            return null;
        }
    }

    /** Replaces Toolkit.getScreenSize() in MyPanel's static init: lets tests render at a smaller size. */
    public static Dimension screenSize(Toolkit tk) {
        int[] r = parsePair(System.getProperty("coop.renderSize"), "x");
        if (r != null) {
            return new Dimension(r[0], r[1]);
        }
        return tk.getScreenSize();
    }

    private static boolean haveOrig;
    private static boolean origFullscreen;
    private static double origX;
    private static double origY;
    private static int origTe = -1;

    /** After the options were read: apply a forced window size (test mode) without changing option.txt. */
    public static void afterReaderOption() {
        try {
            if (origTe < 0) {
                origTe = MyPanel.te;
            }
            if (Coop.forcedTe > 0) {
                MyPanel.te = Coop.forcedTe;
            }
            if (origDifficulty < 0) {
                origDifficulty = MyPanel.Difficulty;
            }
            if (Coop.forcedDifficulty >= 0) {
                MyPanel.Difficulty = Coop.forcedDifficulty;
            }
            int[] w = parsePair(System.getProperty("coop.window"), "x");
            if (w == null) {
                return;
            }
            if (!haveOrig) {
                haveOrig = true;
                origFullscreen = MyPanel.fullscreen;
                origX = MyPanel.screen_save_X;
                origY = MyPanel.screen_save_Y;
            }
            MyPanel.fullscreen = false;
            MyPanel.screen_save_X = w[0];
            MyPanel.screen_save_Y = w[1];
        } catch (Throwable t) {
            Log.error("afterReaderOption", t);
        }
    }

    private static boolean curFullscreen;
    private static double curX;
    private static double curY;
    private static int curTe;
    private static int curDifficulty;
    static int origDifficulty = -1;

    /** option.txt must keep the player's own settings, so swap them in while saving. */
    public static void beforeSaveOption() {
        curTe = MyPanel.te;
        curDifficulty = MyPanel.Difficulty;
        if (Coop.forcedTe > 0 && origTe > 0) {
            MyPanel.te = origTe;
        }
        if (Coop.forcedDifficulty >= 0 && origDifficulty >= 0) {
            MyPanel.Difficulty = origDifficulty;
        }
        if (!haveOrig) {
            return;
        }
        curFullscreen = MyPanel.fullscreen;
        curX = MyPanel.screen_save_X;
        curY = MyPanel.screen_save_Y;
        MyPanel.fullscreen = origFullscreen;
        MyPanel.screen_save_X = origX;
        MyPanel.screen_save_Y = origY;
    }

    public static void afterSaveOption() {
        MyPanel.te = curTe;
        MyPanel.Difficulty = curDifficulty;
        if (!haveOrig) {
            return;
        }
        MyPanel.fullscreen = curFullscreen;
        MyPanel.screen_save_X = curX;
        MyPanel.screen_save_Y = curY;
    }

    public static void onStartHead(MyPanel p, Stage s) {
        panel = p;
        stage = s;
        Log.info("Dread Dawn Coop " + Coop.VERSION + " started (game version " + MyPanel.verision + ")");
    }

    public static void stageSetX(Stage s, double x) {
        int[] pos = parsePair(System.getProperty("coop.windowPos"), ",");
        s.setX(pos != null ? pos[0] : x);
    }

    public static void stageSetY(Stage s, double y) {
        int[] pos = parsePair(System.getProperty("coop.windowPos"), ",");
        s.setY(pos != null ? pos[1] : y);
    }

    public static void onStartTail(MyPanel p, Stage s) {
        try {
            String title = System.getProperty("coop.title");
            if (title != null) {
                s.setTitle(title);
            }
            Coop.init(p, s);
        } catch (Throwable t) {
            Log.error("onStartTail", t);
        }
    }

    public static void robotMouseMove(Robot r, int x, int y) {
        // intentionally ignored: never move the player's real mouse cursor
    }

    public static boolean skipCheckfps() {
        return Coop.testMode || Net.active();
    }

    // ---- safety net ----------------------------------------------------------------------------

    private static long lastDrawError;
    private static long lastGameError;
    private static long lastUnityError;

    public static void safeDraw(MyPanel p) {
        try {
            p.draw();
        } catch (Throwable t) {
            long now = System.currentTimeMillis();
            if (now - lastDrawError > 3000) {
                lastDrawError = now;
                Log.error("draw (caught, the game keeps running)", t);
            }
        }
    }

    public static void safeGame(MyPanel p) {
        try {
            p.game();
        } catch (Throwable t) {
            long now = System.currentTimeMillis();
            if (now - lastGameError > 3000) {
                lastGameError = now;
                Log.error("game (caught, the game keeps running)", t);
            }
        }
    }

    public static void safeUnity(MyPanel p) {
        try {
            p.unity();
        } catch (Throwable t) {
            long now = System.currentTimeMillis();
            if (now - lastUnityError > 3000) {
                lastUnityError = now;
                Log.error("unity (caught, the game keeps running)", t);
            }
        }
    }

    /** zombies/NPCs count as "on screen" when the host OR the client can see them */
    public static boolean inscreenCoop(double x, double y, double bond) {
        boolean r = MyPanel.inscreenREAL(x, y, bond);
        if (r || !Net.isHost() || !Puppet.active()) {
            return r;
        }
        try {
            coop.sync.PlayerSync.State c = Puppet.latestState();
            if (c == null) {
                return false;
            }
            double[] p = MyPanel.pFAST;
            double dx = c.camx - (-MyPanel.keyxed);
            double dy = (c.camy - (-MyPanel.keyyed)) * MyPanel.flatex;
            if (x + bond < p[0] + dx || x - bond > p[1] + dx) {
                return false;
            }
            double fy = MyPanel.Rtgy(y);
            return !(fy + bond < p[2] + dy) && !(fy - bond > p[3] + dy);
        } catch (Throwable t) {
            return false;
        }
    }

    /** start of game(): while playing together the pause menu does not stop the simulation */
    public static void gameHead() {
        try {
            Coop.pauseMenuFrame();
        } catch (Throwable t) {
            Log.error("gameHead", t);
        }
    }

    public static void hintHead() {
        if (Puppet.active()) {
            MyPanel.ctgroup[Puppet.K] = 2;
        }
    }

    public static void hintTail() {
        if (Puppet.active()) {
            MyPanel.ctgroup[Puppet.K] = 0;
            // the other player's body is never offered for looting ("press E" is our revive prompt)
            if (panel != null && panel.counterhintnum == Puppet.K) {
                panel.counterhintnum = -1;
            }
        }
    }

    // ---- frame hooks ---------------------------------------------------------------------------

    /** FX thread, start of every frame. */
    public static void drawHead() {
        coop.sync.HazardSync.frameReset();
        try {
            Coop.fxFrame();
        } catch (Throwable t) {
            Log.error("drawHead", t);
        }
        try {
            Puppet.fxRefresh();
        } catch (Throwable t) {
            Log.rare("fxRefresh " + t);
        }
    }

    /** MyPanel.counterfall / counterfallback: the other player's figure is only knocked down by his own machine */
    public static boolean puppetFall(int x, double px, double py) {
        return x == Puppet.K && Puppet.active() && Coop.playing();
    }

    /** FX thread, end of every frame: status text, dialogs. */
    public static void drawTail() {
        try {
            Overlay.drawTail();
        } catch (Throwable t) {
            Log.error("drawTail", t);
        }
        try {
            coop.sync.AiAssist.fxFrame();
        } catch (Throwable t) {
            Log.error("AiAssist", t);
        }
    }

    // ---- host: the guest's screen area is simulated like the own one ------------------------

    public static int ghostHit(int hitkind, int x, int victim) {
        try {
            return coop.sync.ShotSync.ghostHit(hitkind, x, victim);
        } catch (Throwable t) {
            return hitkind;
        }
    }

    // ---- companions and NPC dialogs of the guest -------------------------------------------

    public static void teamCommandHead() {
        try {
            if (Net.isClient()) {
                coop.sync.TeamSync.clientCommand();
            } else {
                coop.sync.TeamSync.hostCommandHead();
            }
        } catch (Throwable t) {
            Log.error("teamCommand", t);
        }
    }

    public static void dialogActHead() {
        try {
            coop.sync.TeamSync.clientSnapshot(coop.sync.TeamSync.DIALOG);
        } catch (Throwable t) {
            Log.error("dialogActHead", t);
        }
    }

    public static void dialogActTail() {
        try {
            coop.sync.TeamSync.clientDiff(coop.sync.TeamSync.DIALOG);
        } catch (Throwable t) {
            Log.error("dialogActTail", t);
        }
    }

    public static void teamMenuHead() {
        try {
            coop.sync.TeamSync.clientSnapshot(coop.sync.TeamSync.MENU);
        } catch (Throwable t) {
            Log.error("teamMenuHead", t);
        }
    }

    public static void teamMenuTail() {
        try {
            coop.sync.TeamSync.clientDiff(coop.sync.TeamSync.MENU);
        } catch (Throwable t) {
            Log.error("teamMenuTail", t);
        }
    }

    // ---- the story with two players ------------------------------------------------------------

    public static boolean targetcloseCoop(Game.Direction d, double r) {
        try {
            return coop.sync.StoryCoop.targetclose(d, r);
        } catch (Throwable t) {
            Log.error("targetcloseCoop", t);
            return d.targetclose(r);
        }
    }

    public static boolean inareaCoop(Game.Direction d, double x, double y, int area) {
        try {
            return coop.sync.StoryCoop.inarea(d, x, y, area);
        } catch (Throwable t) {
            Log.error("inareaCoop", t);
            return d.inarea(x, y, area);
        }
    }

    public static void treasureGuestPass(boolean fore) {
        try {
            coop.sync.StoryCoop.treasureGuestPass(fore);
        } catch (Throwable t) {
            Log.error("treasureGuestPass", t);
        }
    }

    public static boolean clientStartMission() {
        try {
            return coop.sync.StoryCoop.clientStartMission();
        } catch (Throwable t) {
            Log.error("clientStartMission", t);
            return Net.isClient() && Coop.clientSimOff();
        }
    }

    public static void storyGive(String path) {
        try {
            coop.sync.StoryCoop.giveHead(path);
        } catch (Throwable t) {
            Log.error("storyGive", t);
        }
    }

    public static void storyTargetDone(Game.Direction d) {
        try {
            coop.sync.StoryCoop.targetDoneHead(d);
        } catch (Throwable t) {
            Log.error("storyTargetDone", t);
        }
    }

    public static boolean storyDeathed() {
        try {
            return coop.sync.StoryCoop.storyDeathed();
        } catch (Throwable t) {
            return MyPanel.deathed;
        }
    }

    public static double[] realtimeHeal() {
        try {
            return coop.sync.StoryCoop.realtimeHeal();
        } catch (Throwable t) {
            return MyPanel.ctheal;
        }
    }

    // ---- everyday things: bonfires, feeding, water, sleeping, minigames ----------------------

    public static boolean bonfireBurn(int i) {
        try {
            return coop.sync.LifeSync.bonfireBurn(i);
        } catch (Throwable t) {
            Log.error("bonfireBurn", t);
            return false;
        }
    }

    public static void bonfireTime(int i) {
        try {
            coop.sync.LifeSync.bonfireTime(i);
        } catch (Throwable t) {
            Log.error("bonfireTime", t);
        }
    }

    public static void feedHead(int x, int y, byte kind) {
        try {
            coop.sync.LifeSync.feed(x, y, kind);
        } catch (Throwable t) {
            Log.error("feedHead", t);
        }
    }

    public static void sleepAddHour(int h) {
        try {
            coop.sync.LifeSync.sleepAddHour(h);
        } catch (Throwable t) {
            Log.error("sleepAddHour", t);
            MyPanel.addhour(h);
        }
    }

    public static boolean minigameBlock() {
        try {
            return coop.sync.LifeSync.minigameBlock();
        } catch (Throwable t) {
            return false;
        }
    }

    /** client: crafted traps trigger on the host only (their state comes from there) */
    public static void craftMineHead(Game.Craft c) {
        if (Net.isClient() && Coop.clientSimOff()) {
            c.mineTIME = 0;
        }
    }

    public static void craftLogHead(Game.Craft c) {
        if (Net.isClient() && Coop.clientSimOff()) {
            c.logTIME = 0;
        }
    }

    // ---- locks, keys, power lines, vents, flame jets, story messages (GuestEdits) -------------

    public static void keyTaken(int id) {
        try {
            coop.sync.GuestEdits.keyTaken(id);
        } catch (Throwable t) {
            Log.error("keyTaken", t);
        }
    }

    public static void keyUsing(int id) {
        try {
            coop.sync.GuestEdits.keyUsing(id);
        } catch (Throwable t) {
            Log.error("keyUsing", t);
        }
    }

    public static void doorKey(int key) {
        try {
            coop.sync.GuestEdits.doorKey(key);
        } catch (Throwable t) {
            Log.error("doorKey", t);
        }
    }

    public static void powerShot(Game.Other o, int x) {
        try {
            coop.sync.GuestEdits.powerShot(o, x);
        } catch (Throwable t) {
            Log.error("powerShot", t);
        }
    }

    public static void powerBlast(Game.Other o, int x) {
        try {
            coop.sync.GuestEdits.powerBlast(o, x);
        } catch (Throwable t) {
            Log.error("powerBlast", t);
        }
    }

    public static void hostNotice(Game.NoticeView nv, String word) {
        try {
            coop.sync.GuestEdits.hostNotice(nv, word);
        } catch (Throwable t) {
            Log.error("hostNotice", t);
        }
    }

    public static void hostHint(Game.WordAll w, String hint, double time) {
        try {
            coop.sync.GuestEdits.hostHint(w, hint, time);
        } catch (Throwable t) {
            Log.error("hostHint", t);
        }
    }

    public static boolean hostGuidance(Game.Direction d, String map, int mapid, int x, int y, String branch) {
        try {
            return coop.sync.GuestEdits.hostGuidance(d, map, mapid, x, y, branch);
        } catch (Throwable t) {
            Log.error("hostGuidance", t);
            return false;
        }
    }

    public static boolean storyDirection(Game.MapThing mt, String option, String branch) {
        try {
            return coop.sync.GuestEdits.storyDirection(mt, option, branch);
        } catch (Throwable t) {
            Log.error("storyDirection", t);
            return false;
        }
    }

    public static void jumpCamp() {
        try {
            coop.sync.GuestEdits.jumpCamp();
        } catch (Throwable t) {
            Log.error("jumpCamp", t);
        }
    }

    /** flame jets burn while near either player (the host simulates the flames that hurt the guest) */
    public static boolean flamerInscreen(double x, double y, double w, double h) {
        try {
            return coop.sync.AiAssist.inscreenBox(x, y, w, h);
        } catch (Throwable t) {
            return MyPanel.inscreen(x, y, w, h);
        }
    }

    /** client: vents only drop zombies on the host (the guest's figure counts there) */
    public static void otherMoveHead(Game.Other o) {
        if (Net.isClient() && Coop.clientSimOff()) {
            o.pipetopTIME = 0;
        }
    }

    /** host: vents and hanging zombies react to the guest as well as to the host */
    public static double nearEither(double x1, double y1, double x2, double y2) {
        double d = MyPanel.getdistance2(x1, y1, x2, y2);
        try {
            if (Net.isHost() && coop.sync.AiAssist.active() && x2 == MyPanel.doucount[0][300] && y2 == MyPanel.doucount[1][300]) {
                d = Math.min(d, MyPanel.getdistance2(x1, y1, MyPanel.doucount[0][Puppet.K], MyPanel.doucount[1][Puppet.K]));
            }
        } catch (Throwable t) {
            // keep the plain distance
        }
        return d;
    }

    // ---- explosions, barrels, boomer gas, traps ------------------------------------------------

    public static void hazardShared() {
        coop.sync.HazardSync.sharedEnter();
    }

    public static void hazardSharedEnd() {
        coop.sync.HazardSync.sharedExit();
    }

    public static void explodeHead(int x, int y, double distance, double shock, double damage, boolean counter) {
        try {
            coop.sync.HazardSync.explodeHead(x, y, distance, shock, damage, counter);
        } catch (Throwable t) {
            Log.error("explodeHead", t);
        }
    }

    public static boolean boomerHead(int x) {
        try {
            return coop.sync.HazardSync.boomerHead(x);
        } catch (Throwable t) {
            Log.error("boomerHead", t);
            return false;
        }
    }

    public static void barrelHead() {
        try {
            coop.sync.HazardSync.barrelHead();
        } catch (Throwable t) {
            Log.error("barrelHead", t);
        }
    }

    public static void barrelCreating(double x, double y, int kind) {
        try {
            coop.sync.HazardSync.barrelCreating(x, y, kind);
        } catch (Throwable t) {
            Log.error("barrelCreating", t);
        }
    }

    public static void bladeHead(MyPanel self) {
        coop.sync.HazardSync.bladeHead(self);
    }

    public static void nailHead(MyPanel self) {
        coop.sync.HazardSync.nailHead(self);
    }

    public static boolean spiterDamage(int x) {
        return coop.sync.HazardSync.spiterDamage(x);
    }

    // ---- cars and skateboards ----------------------------------------------------------------

    public static void afterCar() {
        try {
            coop.sync.VehicleSync.afterCar();
        } catch (Throwable t) {
            Log.error("afterCar", t);
        }
    }

    public static void afterSkate() {
        try {
            coop.sync.VehicleSync.afterSkate();
        } catch (Throwable t) {
            Log.error("afterSkate", t);
        }
    }

    public static void carCreating(double x, double y, double ang, int kind, int speed, int heal, int code) {
        try {
            coop.sync.VehicleSync.carCreating(x, y, ang, kind, speed, heal, code);
        } catch (Throwable t) {
            Log.error("carCreating", t);
        }
    }

    public static void carPickupHead(int x) {
        try {
            coop.sync.VehicleSync.carPickupHead(x);
        } catch (Throwable t) {
            Log.error("carPickupHead", t);
        }
    }

    public static void carPickupTail(int x) {
        try {
            coop.sync.VehicleSync.carPickupTail(x);
        } catch (Throwable t) {
            Log.error("carPickupTail", t);
        }
    }

    public static void skatePickupHead(int x) {
        try {
            coop.sync.VehicleSync.skatePickupHead(x);
        } catch (Throwable t) {
            Log.error("skatePickupHead", t);
        }
    }

    public static void skatePickupTail(int x) {
        try {
            coop.sync.VehicleSync.skatePickupTail(x);
        } catch (Throwable t) {
            Log.error("skatePickupTail", t);
        }
    }

    public static boolean clientSkipNpc(int x) {
        return x < 300 && Net.isClient() && Coop.clientSimOff();
    }

    public static boolean skipSlot(int x) {
        return coop.sync.AiAssist.skipSlot(x);
    }

    public static boolean[] tinEither() {
        return coop.sync.AiAssist.tin();
    }

    public static boolean[] ctinEither() {
        return coop.sync.AiAssist.ctin();
    }

    public static boolean inscreenCoop4(double x, double y, double z, double bond) {
        return coop.sync.AiAssist.inscreenCoop4(x, y, z, bond);
    }

    public static boolean inrespawnCoop(Game.Humanrespawn h, double x, double y) {
        try {
            return coop.sync.AiAssist.inrespawnCoop(h, x, y);
        } catch (Throwable t) {
            return h.inrespawnarea(x, y);
        }
    }

    public static void widenSeeblock() {
        try {
            coop.sync.AiAssist.widenSeeblock();
        } catch (Throwable t) {
            Log.error("widenSeeblock", t);
        }
    }

    public static int longrunFill(Game.Function f, int roued, int fin, boolean[][][] walk, int kind) {
        return coop.sync.AiAssist.longrunFill(f, roued, fin, walk, kind);
    }

    public static void page0Head() {
        try {
            MenuUI.page0Head();
        } catch (Throwable t) {
            Log.error("page0Head", t);
        }
    }

    public static void afterWord() {
        try {
            MenuUI.afterWord();
        } catch (Throwable t) {
            Log.error("afterWord", t);
        }
    }

    /** FX thread, before the world is drawn: spectator camera. */
    public static void page3Head() {
        try {
            DeathSync.cameraFx();
        } catch (Throwable t) {
            Log.error("page3Head", t);
        }
    }

    /** FX thread, after the world and speech bubbles were drawn: name tags. */
    public static void drawWorldOverlay() {
        try {
            Overlay.drawWorld();
        } catch (Throwable t) {
            Log.error("drawWorldOverlay", t);
        }
    }

    /** Worker thread, every in-game frame (also while the inventory is open). */
    public static void unityHead() {
        coop.sync.HazardSync.frameReset();
        try {
            Coop.simFrame();
        } catch (Throwable t) {
            Log.error("unityHead", t);
        }
    }

    /** In co-op the world keeps running while the inventory / notes are open. */
    public static void fixBoneplay() {
        try {
            if (Net.connected() && Coop.inGame() && MyPanel.Bone != null && !Coop.panel.codetimeBOOL) {
                MyPanel.Bone.boneplay = true;
            }
        } catch (Throwable t) {
            Log.error("fixBoneplay", t);
        }
    }

    public static void beforePackCollection() {
        try {
            Puppet.beforeUse();
        } catch (Throwable t) {
            Log.error("beforePackCollection", t);
        }
    }

    public static void afterMove() {
        try {
            Coop.afterMove();
        } catch (Throwable t) {
            Log.error("afterMove", t);
        }
    }

    public static void collectionTail() {
        try {
            Coop.collectionTail();
        } catch (Throwable t) {
            Log.error("collectionTail", t);
        }
    }

    /** true = skip this load() (the client only loads worlds sent by the host) */
    public static boolean loadHead() {
        try {
            if (WorldTransfer.blockClientLoad()) {
                Log.info("load() suppressed on the client (waiting for the host)");
                if (!MyPanel.deathed && Net.connected()) {
                    // e.g. "Continue" in the menu: get the host's current world
                    Net.send(new coop.net.Msg(coop.net.Proto.WORLD_REQUEST));
                }
                return true;
            }
            Coop.loadHead();
        } catch (Throwable t) {
            Log.error("loadHead", t);
        }
        return false;
    }

    public static void afterSaveGame() {
        try {
            coop.sync.Profile.onSaveGame();
        } catch (Throwable t) {
            Log.error("afterSaveGame", t);
        }
    }

    /** Client: the clock and its events come from the host; only the level-up check runs here. */
    public static boolean clientRealtime() {
        if (!(Net.isClient() && Coop.clientSimOff())) {
            return false;
        }
        try {
            if (MyPanel.LV < MyPanel.LVED && MyPanel.EXP >= panel.EXPed()) {
                if (!MyPanel.L4DMode()) {
                    MyPanel.NoticeView.notice(MyPanel.NoticeView.levelup);
                    MyPanel.WordAll.warn(13, 400.0);
                }
                MyPanel.EXP = MyPanel.EXP - panel.EXPed();
                MyPanel.LV++;
                MyPanel.skillpoint += 5;
            }
            if (MyPanel.historytime > 0) {
                MyPanel.historytime--;
            }
            if (MyPanel.camp() && MyPanel.WordAll != null) {
                MyPanel.WordAll.camp();
            }
        } catch (Throwable t) {
            Log.error("clientRealtime", t);
        }
        return true;
    }

    public static void loadTail() {
        try {
            Coop.loadTail();
        } catch (Throwable t) {
            Log.error("loadTail", t);
        }
    }

    // ---- remote player puppet ------------------------------------------------------------

    /** Loop limit for AI loops over counters ("300 + addplayer"): skips the puppet slot. */
    public static int aiLimit() {
        return Puppet.active() ? Puppet.K : 300;
    }

    public static void boneSkin(Bone b) {
        try {
            Puppet.skin(b);
        } catch (Throwable t) {
            Log.error("boneSkin", t);
        }
    }

    // ---- client: no own simulation -------------------------------------------------------

    public static boolean clientNoAI() {
        return Net.isClient() && Coop.clientSimOff();
    }

    public static boolean clientNoSave() {
        return Net.isClient() && Coop.inCoopWorld();
    }

    // ---- damage --------------------------------------------------------------------------

    /** return true = the game must not apply this damage itself */
    public static boolean ctdamage(int id, double damage, double angshock, boolean kevlar, boolean blood) {
        try {
            return Combat.ctdamage(id, damage, angshock, kevlar, blood);
        } catch (Throwable t) {
            Log.error("ctdamage", t);
            return false;
        }
    }

    public static boolean tdamage(int id, int hitter, int damage, double angshock, boolean upblood) {
        try {
            return Combat.tdamage(id, hitter, damage, angshock, upblood);
        } catch (Throwable t) {
            Log.error("tdamage", t);
            return false;
        }
    }

    public static boolean death(int word) {
        try {
            return DeathSync.death(word);
        } catch (Throwable t) {
            Log.error("death", t);
            return false;
        }
    }

    public static void shootcreater(MyPanel self, double humx, double humy, double speedx, double speedy, double ang,
            byte kind, int damage, int shooter) {
        try {
            Combat.shootcreater(humx, humy, speedx, speedy, ang, kind, damage, shooter);
            coop.sync.ShotSync.onShot(humx, humy, speedx, speedy, ang, kind, shooter);
        } catch (Throwable t) {
            Log.error("shootcreater", t);
        }
    }

    public static void cutsceneStarted(String path, boolean smooth) {
        try {
            coop.sync.StorySync.cutsceneStarted(path, smooth);
        } catch (Throwable t) {
            Log.error("cutsceneStarted", t);
        }
    }

    public static void cutscenePlayHead() {
        try {
            coop.sync.StorySync.cutscenePlayHead();
        } catch (Throwable t) {
            Log.error("cutscenePlayHead", t);
        }
    }

    public static void dialogStarted(String path, int x, int y) {
        try {
            coop.sync.StorySync.dialogStarted(path, x, y);
        } catch (Throwable t) {
            Log.error("dialogStarted", t);
        }
    }

    public static boolean dialogSpeak() {
        try {
            return coop.sync.StorySync.dialogSpeak();
        } catch (Throwable t) {
            Log.error("dialogSpeak", t);
            return false;
        }
    }

    public static int cutHumanActor(Game.Humanrespawn h, byte kind, byte teamatekind) {
        return coop.sync.StorySync.cutHumanActor(h, kind, teamatekind);
    }

    public static int cutZombieActor(Game.Zombierespawn z, byte kind) {
        return coop.sync.StorySync.cutZombieActor(z, kind);
    }

    public static void cutGiveGun(Game.Humanrespawn h, int name, int pack, double ex) {
        coop.sync.StorySync.cutGiveGun(h, name, pack, ex);
    }

    public static boolean hostStoryHold() {
        try {
            return Net.isHost() && (Coop.playing() && (MyPanel.PHOTO || MyPanel.NOTICE || MyPanel.packnum != -1)
                    || WorldTransfer.hostAwaitingGuest());
        } catch (Throwable t) {
            return false;
        }
    }

    public static void storyPhoto(String path) {
        try {
            coop.sync.StorySync.storyPhoto(path);
        } catch (Throwable t) {
            Log.error("storyPhoto", t);
        }
    }

    public static void treasureUse(int kind, String mission, String branch, String option, int zonetime) {
        try {
            coop.sync.StorySync.treasureUse(kind, mission, branch, option, zonetime);
        } catch (Throwable t) {
            Log.error("treasureUse", t);
        }
    }

    public static void storyHint(Game.WordAll w, String path, double time) {
        w.hint(path, time);
        try {
            coop.sync.StorySync.storyHint(path, time);
        } catch (Throwable t) {
            Log.error("storyHint", t);
        }
    }

    public static void speak(int id, String word) {
        try {
            coop.sync.SpeechSync.onSpeak(id, word);
        } catch (Throwable t) {
            Log.error("speak", t);
        }
    }

    // ---- projectiles / environment damage ------------------------------------------------

    public static void projMark() {
        try {
            coop.sync.Projectiles.mark();
        } catch (Throwable t) {
            Log.error("projMark", t);
        }
    }

    public static void projCreated() {
        try {
            coop.sync.Projectiles.created();
        } catch (Throwable t) {
            Log.error("projCreated", t);
        }
    }

    public static void envEnter() {
        Combat.envDepth++;
    }

    public static void envExit() {
        if (Combat.envDepth > 0) {
            Combat.envDepth--;
        }
    }

    // ---- world objects ---------------------------------------------------------------------

    public static boolean clientCaseDialog(int x) {
        try {
            return coop.sync.ItemSync.clientCaseDialog(x);
        } catch (Throwable t) {
            Log.error("clientCaseDialog", t);
            return false;
        }
    }

    public static boolean clientTerroristDialog(int x) {
        try {
            return coop.sync.ItemSync.clientTerroristDialog(x);
        } catch (Throwable t) {
            Log.error("clientTerroristDialog", t);
            return false;
        }
    }

    public static boolean clientDropDialog(int x) {
        try {
            return coop.sync.ItemSync.clientDropDialog(x);
        } catch (Throwable t) {
            Log.error("clientDropDialog", t);
            return false;
        }
    }

    public static void afterOpenDoor(int x) {
        try {
            coop.sync.WorldSync.clientOpenedDoor(x);
        } catch (Throwable t) {
            Log.error("afterOpenDoor", t);
        }
    }

    // ---- world / travel ------------------------------------------------------------------

    public static String savePath(String s) {
        return WorldTransfer.mapPath(s);
    }

    public static boolean travel(String map, int id, int x, int y) {
        try {
            return Travel.travel(map, id, x, y);
        } catch (Throwable t) {
            Log.error("travel", t);
            return false;
        }
    }

    public static boolean travelingHead(String map, int x, int y, int id) {
        try {
            return Travel.travelingHead(map, x, y, id);
        } catch (Throwable t) {
            Log.error("travelingHead", t);
            return false;
        }
    }

    public static void afterTraveling() {
        try {
            Travel.afterTraveling();
        } catch (Throwable t) {
            Log.error("afterTraveling", t);
        }
    }

    // ------------------------------------------------------------------ minimap / map (MapMarkers)

    public static void radCoord(MyPanel p, double x, double y, int kind, boolean out) {
        if (coop.ui.MapMarkers.skipRadar(x, y)) {
            return;
        }
        p.getradcoordinate(x, y, kind, out);
    }

    public static void radarDrawn() {
        try {
            coop.ui.MapMarkers.radar();
        } catch (Throwable t) {
            Log.rare("radar " + t);
        }
    }

    public static void mapPartner(Game.MapView mv) {
        try {
            coop.ui.MapMarkers.bigMap(mv);
        } catch (Throwable t) {
            Log.rare("map partner " + t);
        }
    }

    public static boolean unityDeathed() {
        return coop.sync.DeathSync.unityDeathed();
    }

    /** Function.bot: on the client only the own figure's command-mode steering (QMode), the rest runs on the host */
    public static boolean clientBot(Game.Function f) {
        if (!(Net.isClient() && Coop.clientSimOff())) {
            return false;
        }
        try {
            coop.sync.QMode.clientTick(f);
        } catch (Throwable t) {
            Log.rare("QMode " + t);
        }
        return true;
    }

    // ------------------------------------------------------------------ down / revive / saving (DeathSync, SaveSync)

    /** MyPanel.opendialog (E): next to the downed partner E revives him */
    public static boolean openDialogHead() {
        try {
            return DeathSync.tryRevive();
        } catch (Throwable t) {
            Log.rare("tryRevive " + t);
            return false;
        }
    }

    /** MyPanel.counterDialog: the other player's figure has no dialog and can never be looted */
    public static boolean counterDialogHead(int x) {
        return x == Puppet.K && Puppet.active();
    }

    public static void saveStockHead(String source, String target, boolean insave) {
        try {
            if (Net.isHost() || !Net.active()) {
                coop.sync.SaveSync.slotCopy(source, target, insave);
            }
        } catch (Throwable t) {
            Log.rare("slotCopy " + t);
        }
    }

    public static void saveOtherHead() {
        try {
            DeathSync.beforeSave();
        } catch (Throwable t) {
            Log.rare("beforeSave " + t);
        }
    }

    public static void saveOtherTail() {
        try {
            DeathSync.afterSave();
        } catch (Throwable t) {
            Log.rare("afterSave " + t);
        }
    }

    public static boolean page4Deathed() {
        return coop.sync.SaveSync.menuDeathed();
    }

    public static void backfireHead(MyPanel p) {
        try {
            coop.sync.SaveSync.clientF5(p);
        } catch (Throwable t) {
            Log.rare("clientF5 " + t);
        }
    }

    // ------------------------------------------------------------------ story waits for the guest (StorySync)

    /** MapThing.treasure / treasurewait on the host while the guest loads the world */
    public static boolean treasureGate(boolean fore) {
        try {
            return Net.isHost() && WorldTransfer.holdMapEvents(fore);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean treasureWaitGate() {
        return treasureGate(false);
    }

    /** "watch the scene or skip it?" in co-op: always watched by both (skipping works by voting with space) */
    public static void storyYesNo(Game.NoticeView nv, String word, int kind) {
        if (kind == 4 && Net.isHost() && Coop.playing() && MyPanel.Direction != null) {
            MyPanel.Direction.playjump(false);
            return;
        }
        nv.notice_yesno(word, kind);
    }

    /** HeadDialog.close on the host (e.g. the prologue is skipped): the guest's copy closes too */
    public static void headDialogClosed() {
        try {
            coop.sync.StorySync.hostDialogClosed();
        } catch (Throwable t) {
            Log.rare("hostDialogClosed " + t);
        }
    }
}
