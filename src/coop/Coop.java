package coop;

import Game.MyPanel;
import coop.debug.DebugServer;
import coop.net.Msg;
import coop.net.Net;
import coop.net.Proto;
import coop.sync.Combat;
import coop.sync.DeathSync;
import coop.sync.EnvSync;
import coop.sync.HumanSync;
import coop.sync.PlayerSync;
import coop.sync.Profile;
import coop.sync.Puppet;
import coop.sync.Travel;
import coop.sync.WorldSync;
import coop.sync.WorldTransfer;
import coop.sync.ZombieSync;
import coop.ui.MenuUI;
import coop.ui.Overlay;
import java.util.concurrent.ConcurrentLinkedQueue;
import javafx.stage.Stage;

/** Central state of the co-op mod: session flow, frame timing and message dispatch. */
public final class Coop {
    private Coop() {}

    public static final String VERSION = "1.2";

    public static MyPanel panel;
    public static Stage stage;
    public static boolean testMode;
    /** te (zombie slots) forced by the host for this session, -1 = own option */
    public static volatile int forcedTe = -1;
    public static volatile int forcedDifficulty = -1;
    /** test only (debug command "god 1"): the own player takes no damage */
    public static volatile boolean testGod;

    /** a load() is running */
    public static volatile boolean loading;
    /** client: the world received from the host is the one in memory */
    public static volatile boolean clientInWorld;
    /** host: the client has loaded the current world and plays */
    public static volatile boolean clientPlaying;

    private static final ConcurrentLinkedQueue<Runnable> fxTasks = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<Runnable> simTasks = new ConcurrentLinkedQueue<>();

    static void init(MyPanel p, Stage s) {
        panel = p;
        stage = s;
        testMode = System.getProperty("coop.window") != null || System.getProperty("coop.debugPort") != null;
        Config.load();
        coop.sync.SaveSync.restoreFromConfig();
        Log.info("Coop.init: Name=" + Config.name + " Port=" + Config.port + " window " + MyPanel.screen_save_X + "x"
                + MyPanel.screen_save_Y + " render=" + MyPanel.screensizex + "x" + MyPanel.screensizey);
        DebugServer.startIfEnabled();
    }

    public static void runFx(Runnable r) {
        fxTasks.add(r);
    }

    /** runs r on the game's simulation thread at the next in-game frame (or the FX thread when not in game) */
    public static void runSim(Runnable r) {
        simTasks.add(r);
    }

    public static boolean inGame() {
        return (MyPanel.page == 3 || pausedInCoop()) && !MyPanel.dir1 && !loading && MyPanel.ctcode != null;
    }

    /** the pause menu (and its sub pages) while playing together: the world keeps running */
    static boolean pausedInCoop() {
        int p = MyPanel.page;
        return Net.connected() && (Net.isHost() ? clientPlaying : clientInWorld)
                && (p == 4 || (p >= 5 && p <= 8 && (MyPanel.lastpage == 4 || MyPanel.lastpage == 3)));
    }

    private static int lastPage;

    /** FX thread, start of game(): schedule the simulation while the pause menu is open in co-op. */
    static void pauseMenuFrame() {
        int p = MyPanel.page;
        if (p != lastPage) {
            if (p == 5 && lastPage != 5 && Net.isClient() && clientInWorld && Net.connected()) {
                // the guest's "Save": the host saves the shared game (the guest's character is saved with it)
                coop.sync.SaveSync.clientAsk();
                MyPanel.page = 4;
                MyPanel.lastpage = 3;
                p = 4;
            }
            if (lastPage == 3 && pausedInCoop()) {
                // keys held when the menu opened would keep the player walking
                panel.W = panel.A = panel.S = panel.D = false;
                panel.shiftRUNed = false;
                MyPanel.click = false;
                MyPanel.rightclick = false;
            }
            lastPage = p;
        }
        if (p != 3 && pausedInCoop() && panel.synunityed) {
            panel.synunityed = false;
            panel.fixedThreadPool.execute(() -> {
                try {
                    panel.unity();
                } catch (Throwable t) {
                    Log.error("unity (Pause)", t);
                } finally {
                    panel.synunityed = true;
                }
            });
        }
    }

    /** client plays the host's world: its own AI/spawning must stay off */
    public static boolean clientSimOff() {
        return clientInWorld;
    }

    public static boolean inCoopWorld() {
        return clientInWorld;
    }

    /** true while both machines play the same world */
    public static boolean playing() {
        if (!Net.connected() || !inGame()) {
            return false;
        }
        return Net.isHost() ? clientPlaying : clientInWorld;
    }

    // ------------------------------------------------------------------------- frames

    /** FX thread, every frame (all pages). */
    static void fxFrame() {
        Runnable r;
        while ((r = fxTasks.poll()) != null) {
            safe(r);
        }
        if (!inGame()) {
            pump();
            runSimTasks();
            if (Net.active()) {
                Net.checkAlive();
                Session.tick();
            }
            if (Net.isClient() && MyPanel.page != 3) {
                WorldTransfer.clientTick();
            }
        }
        MenuUI.fxFrame();
        if (Net.active()) {
            coop.sync.StorySync.fxTick();
        }
    }

    /** Worker thread, every in-game frame. */
    static void simFrame() {
        Combat.envDepth = 0;
        if (!Net.active()) {
            return;
        }
        pump();
        runSimTasks();
        Net.checkAlive();
        Session.tick();
        if (Net.isHost()) {
            WorldTransfer.hostTick();
        } else {
            WorldTransfer.clientTick();
        }
    }

    private static void runSimTasks() {
        Runnable r;
        while ((r = simTasks.poll()) != null) {
            safe(r);
        }
    }

    private static void safe(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            Log.error("task", t);
        }
    }

    /** Worker: positions of remote-controlled things, right after the game moved everything. */
    static void afterMove() {
        if (!playing()) {
            return;
        }
        Puppet.apply();
        if (Net.isClient()) {
            ZombieSync.apply();
            HumanSync.apply();
        }
        DeathSync.tick();
    }

    private static long lastPlayerSend;
    private static long lastEntitySend;
    private static long lastHumanSend;
    private static long lastEnvSend;
    private static long lastWorldSend;

    /** Worker: capture and send state. */
    static void collectionTail() {
        if (!playing()) {
            return;
        }
        long now = System.nanoTime();
        coop.sync.SpeechSync.flush();
        coop.sync.ShotSync.flush();
        if (now - lastPlayerSend >= 33_333_333L) {
            // steady rhythm: the next one is due 33 ms after the last due time, not after this frame
            lastPlayerSend = now - lastPlayerSend > 100_000_000L ? now : lastPlayerSend + 33_333_333L;
            Net.send(PlayerSync.capture());
        }
        if (Net.isHost()) {
            if (now - lastEntitySend > 50_000_000L) {
                lastEntitySend = now;
                Net.send(ZombieSync.capture());
            }
            if (now - lastHumanSend > 50_000_000L) {
                lastHumanSend = now;
                Net.send(HumanSync.capture());
            }
            if (now - lastEnvSend > 1_000_000_000L) {
                lastEnvSend = now;
                Net.send(EnvSync.capture());
            }
            if (now - lastWorldSend > 250_000_000L) {
                lastWorldSend = now;
                WorldSync.hostTick();
            }
            coop.sync.VehicleSync.hostTick();
            coop.sync.TeamSync.hostTick();
        } else {
            coop.sync.VehicleSync.clientTick();
            WorldSync.clientTick();
            coop.sync.LifeSync.clientTick();
            Profile.clientTick();
        }
    }

    static void loadHead() {
        loading = true;
        Log.info("load() start: map=" + MyPanel.select + " client=" + clientInWorld);
    }

    static void loadTail() {
        loading = false;
        Log.info("load() done: map=" + MyPanel.select + MyPanel.selectid);
        Puppet.reset();
        ZombieSync.reset();
        HumanSync.reset();
        WorldSync.reset();
        DeathSync.reset();
        coop.sync.StorySync.reset();
        coop.sync.VehicleSync.reset();
        coop.sync.GuestEdits.reset();
        if (Net.isHost() && Net.connected() && !Net.peerName.isEmpty()) {
            try {
                coop.sync.TeamSync.load(WorldTransfer.gamePath("Save\\auto\\coop"),
                        coop.sync.Profile.fileName(Net.peerName).replace(".profile", ""));
            } catch (Throwable t) {
                Log.error("guest companions", t);
            }
        } else {
            coop.sync.TeamSync.reset();
        }
        if (Net.isClient() && clientInWorld) {
            WorldTransfer.clientLoaded();
        } else if (Net.isHost()) {
            if (Net.connected()) {
                // a reload (death, menu continue, load) changes the world: send it again
                WorldTransfer.requestSend("reloaded", false);
            }
        }
    }

    // ------------------------------------------------------------------------- messages

    /** Reads all received messages. Synchronized: called from the FX or the worker thread. */
    static synchronized void pump() {
        if (Net.conn == null) {
            return;
        }
        Msg m;
        int n = 0;
        while ((m = Net.conn == null ? null : Net.conn.poll()) != null) {
            try {
                dispatch(m);
            } catch (Throwable t) {
                Log.error("dispatch " + m.type, t);
            }
            if (++n > 2000) {
                break;
            }
        }
    }

    private static void dispatch(Msg m) {
        switch (m.type) {
            case Proto.HELLO:
            case Proto.WELCOME:
            case Proto.REJECT:
            case Proto.PING:
            case Proto.PONG:
            case Proto.BYE:
            case Proto.MESSAGE:
                Session.handle(m);
                break;
            case Proto.WORLD_BEGIN:
            case Proto.WORLD_CHUNK:
            case Proto.WORLD_END:
            case Proto.WORLD_LOADED:
            case Proto.WORLD_REQUEST:
                WorldTransfer.handle(m);
                break;
            case Proto.PLAYER_STATE:
                PlayerSync.receive(m);
                break;
            case Proto.ENTITIES:
                ZombieSync.receive(m);
                break;
            case Proto.HUMANS:
                HumanSync.receive(m);
                break;
            case Proto.ENV:
                EnvSync.receive(m);
                break;
            case Proto.SHOT:
                coop.sync.ShotSync.handle(m);
                break;
            case Proto.HIT_ZOMBIE:
            case Proto.HIT_HUMAN:
            case Proto.DAMAGE_PLAYER:
            case Proto.ZOMBIE_EVENT:
            case Proto.MELEE:
                Combat.handle(m);
                break;
            case Proto.PLAYER_DEAD:
            case Proto.GAME_OVER:
            case Proto.REVIVE:
                DeathSync.handle(m);
                break;
            case Proto.TRAVEL_REQUEST:
                Travel.handle(m);
                break;
            case Proto.SAVE:
                coop.sync.SaveSync.handle(m);
                break;
            case Proto.GRENADE:
                coop.sync.Projectiles.handle(m);
                break;
            case Proto.WORLD_EVENT:
            case Proto.MAPACK:
            case Proto.CONTAINER_REQ:
            case Proto.CONTAINER_DATA:
            case Proto.CONTAINER_EDIT:
                WorldSync.handle(m);
                break;
            case Proto.PROFILE:
                Profile.handle(m);
                break;
            case Proto.SPEAK:
                coop.sync.SpeechSync.handle(m);
                break;
            case Proto.CUT_START:
            case Proto.CUT_TIME:
            case Proto.CUT_END:
            case Proto.DIALOG_START:
            case Proto.DIALOG_STEP:
            case Proto.STORY_VOTE:
            case Proto.PHOTO:
            case Proto.STORY_HINT:
            case Proto.STORY_SOUND:
                coop.sync.StorySync.handle(m);
                break;
            case Proto.VEH_STATE:
            case Proto.VEH_CLIENT:
            case Proto.VEH_EVENT:
                coop.sync.VehicleSync.handle(m);
                break;
            case Proto.TEAM:
                coop.sync.TeamSync.handle(m);
                break;
            case Proto.HAZARD:
                coop.sync.HazardSync.handle(m);
                break;
            case Proto.STORY2:
                coop.sync.StoryCoop.handle(m);
                break;
            case Proto.LIFE:
                coop.sync.LifeSync.handle(m);
                break;
            case Proto.GUEST:
                coop.sync.GuestEdits.handle(m);
                break;
            default:
                Log.rare("unknown message " + m.type);
        }
    }

    // ------------------------------------------------------------------------- session events

    /** Called when hosting/joining stops (menu, error, quit). */
    public static void onSessionEnded(Net.Role role, String reason) {
        boolean wasClientWorld = clientInWorld;
        clientPlaying = false;
        Puppet.despawn();
        forcedTe = -1;
        if (forcedDifficulty >= 0) {
            forcedDifficulty = -1;
        }
        DeathSync.reset();
        if (role == Net.Role.CLIENT && wasClientWorld) {
            // the client's game shows the host's world: go back to the main menu
            runSim(() -> {
                clientInWorld = false;
                WorldTransfer.leaveWorld();
            });
            runFx(() -> {
                if (MyPanel.page != 0) {
                    MyPanel.page = 0;
                }
            });
        } else {
            clientInWorld = false;
        }
    }

    /** Host: the client disconnected; keep playing alone. */
    public static void onPartnerLost(String why) {
        clientPlaying = false;
        runSim(Puppet::despawn);
        message(T.s("Your partner left the game ({0})", why));
    }

    public static void message(String text) {
        Overlay.message(text);
    }

    public static String statusText() {
        return "role=" + Net.role + " state=" + Net.state + " playing=" + playing() + " clientInWorld=" + clientInWorld
                + " clientPlaying=" + clientPlaying + " puppet=" + Puppet.active() + " partner=" + Net.peerName
                + " rtt=" + Net.rttMs + " status=" + Net.status;
    }

    /** Extra debug commands handled by the mod. */
    public static String debugCommand(String[] a, String line) {
        switch (a[0]) {
            case "host":
                Config.port = a.length > 1 ? Integer.parseInt(a[1]) : Config.port;
                Net.host(Config.port);
                return "OK";
            case "join":
                Net.join(a[1], a.length > 2 ? Integer.parseInt(a[2]) : Config.port);
                return "OK";
            case "stop":
                Net.stop("Debug");
                return "OK";
            case "name":
                Config.name = Config.clean(line.substring(5));
                return "OK";
            case "kill":
                runSim(() -> MyPanel.ctdamage(300, 99999, 0, false, true));
                return "OK";
            case "zpos": {
                // test: positions of living zombies (z) and NPCs (h) near the local player, with a timestamp
                StringBuilder sb = new StringBuilder("t=" + System.currentTimeMillis());
                double px = MyPanel.doucount[0][300];
                double py = MyPanel.doucount[1][300];
                for (int x = 0; x < MyPanel.te; x++) {
                    if (MyPanel.theal[x] > 0 && Math.abs(MyPanel.douterro[0][x] - px) < 2000
                            && Math.abs(MyPanel.douterro[1][x] - py) < 2000) {
                        sb.append(" z").append(x).append(':').append(Math.round(MyPanel.douterro[0][x])).append(',')
                                .append(Math.round(MyPanel.douterro[1][x]));
                    }
                }
                for (int x = 0; x < 299; x++) {
                    if (MyPanel.ctheal[x] > 0 && Math.abs(MyPanel.doucount[0][x] - px) < 2000
                            && Math.abs(MyPanel.doucount[1][x] - py) < 2000) {
                        sb.append(" h").append(x).append(':').append(Math.round(MyPanel.doucount[0][x])).append(',')
                                .append(Math.round(MyPanel.doucount[1][x]));
                    }
                }
                return sb.toString();
            }
            case "npcs": {
                // living NPCs: slot:x,y,controlkind,teamatekind,group,wealth
                StringBuilder sb = new StringBuilder();
                for (int x = 0; x < 300; x++) {
                    if (MyPanel.ctheal[x] > 0) {
                        sb.append(' ').append(x).append(':').append(Math.round(MyPanel.doucount[0][x])).append(',')
                                .append(Math.round(MyPanel.doucount[1][x])).append(",c").append(MyPanel.ctcontrolkind[x])
                                .append(",t").append(MyPanel.cteamatekind[x]).append(",g").append(MyPanel.ctgroup[x])
                                .append(",w").append(MyPanel.ctwealth[x]);
                    }
                }
                return sb.toString();
            }
            case "god":
                testGod = a.length > 1 && a[1].equals("1");
                return "god=" + testGod;
            case "packinfo": {
                // the own inventory: pack id of ctcode[300] and the 8 hotbar slots (kind/name)
                String code = MyPanel.ctcode[300];
                int id = MyPanel.gethumpacknameid(code);
                StringBuilder sb = new StringBuilder("code=" + code + " id=" + id + " sel=" + MyPanel.selection);
                if (id >= 0) {
                    sb.append(" n=").append(MyPanel.humanpackstocknuming[id]);
                    for (int i = 0; i < Math.min(10, MyPanel.humanpackstocknuming[id]); i++) {
                        int kind = MyPanel.humanpackstockind[i][id];
                        sb.append(' ').append(i).append(':').append(kind);
                        if (kind != 0) {
                            MyPanel.pack.getstatistic(kind, MyPanel.humanpackid[i][id], 0);
                            sb.append('/').append((int) MyPanel.goodname[0]);
                        }
                    }
                }
                int same = 0;
                for (int x = 0; x < 2000; x++) {
                    if (code != null && code.equals(MyPanel.humanpackname[x])) {
                        same++;
                    }
                }
                sb.append(" packsWithCode=").append(same);
                for (int x = 0; x < 300; x++) {
                    if (code != null && code.equals(MyPanel.ctcode[x])) {
                        sb.append(" alsoSlot=").append(x);
                    }
                }
                return sb.toString();
            }
            case "zinfo": {
                // "zinfo C R": zombies within R of counter C: slot:x,y,action,stop,heal
                int c = Integer.parseInt(a[1]);
                double r = Double.parseDouble(a[2]);
                StringBuilder sb = new StringBuilder("t=" + System.currentTimeMillis());
                for (int x = 0; x < MyPanel.te; x++) {
                    if ((MyPanel.theal[x] > 0 || MyPanel.taction[x] != 0)
                            && Math.abs(MyPanel.douterro[0][x] - MyPanel.doucount[0][c]) < r
                            && Math.abs(MyPanel.douterro[1][x] - MyPanel.doucount[1][c]) < r) {
                        sb.append(' ').append(x).append(':').append(Math.round(MyPanel.douterro[0][x])).append(',')
                                .append(Math.round(MyPanel.douterro[1][x])).append(',').append(MyPanel.taction[x]).append(',')
                                .append(Math.round(MyPanel.zombiestop[x])).append(',').append(MyPanel.theal[x])
                                .append(MyPanel.tin[x] ? ",in" : "");
                    }
                }
                return sb.toString();
            }
            case "sim": {
                // run a debug action on the simulation thread
                String what = a[1];
                runSim(() -> coop.debug.Status.simCommand(what, a));
                return "OK";
            }
            default:
                return "ERR unknown command: " + a[0];
        }
    }
}
