package coop.sync;

import Game.MyPanel;
import coop.Config;
import coop.Coop;
import coop.Log;
import coop.net.Msg;
import coop.net.Net;
import coop.net.Proto;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The host's world (Save\auto) is zipped and sent to the client whenever the client joins and
 * after every map change or reload. The client keeps it in Save\coop\auto, so its own single
 * player saves are never touched.
 */
public final class WorldTransfer {
    private WorldTransfer() {}

    public static final String COOP_DIR = "Save\\coop\\auto";

    /** Meta data of the transferred world. */
    public static final class Meta {
        String map;
        int mapId;
        int mode;
        int difficulty;
        int te;
        double hostX;
        double hostY;
        int size;
        String reason;
    }

    // ------------------------------------------------------------------ host

    private static volatile boolean sendPending;
    private static volatile boolean sending;
    private static volatile String pendingReason = "";
    private static volatile long requestedAt;
    private static volatile boolean saveFirst;
    private static long sentAt;

    /**
     * @param save true: save the current state first (join, map change). false: the world was just
     *             loaded from the save (e.g. after both died) and must be sent exactly as it is.
     */
    public static void requestSend(String reason, boolean save) {
        if (Net.isHost() && Net.connected()) {
            if (!sendPending) {
                saveFirst = save;
            } else {
                saveFirst = saveFirst && save;
            }
            sendPending = true;
            pendingReason = reason;
            requestedAt = System.currentTimeMillis();
            Coop.clientPlaying = false;
        }
    }

    /** Host worker thread, every in-game frame. */
    public static void hostTick() {
        if (!sendPending || sending || !Net.connected() || !Coop.inGame()) {
            return;
        }
        if (MyPanel.Animation != null && MyPanel.Animation.play) {
            return; // wait for the end of a cutscene
        }
        if (System.currentTimeMillis() - requestedAt < 1500) {
            return; // a load is often followed by a travel: send once, when things are quiet
        }
        sendPending = false;
        sending = true;
        Coop.clientPlaying = false;
        Puppet.despawn();
        Meta meta = new Meta();
        if (saveFirst) {
            try {
                Profile.hostWriteProfiles();
                new Game.SaveGame();
            } catch (Throwable t) {
                Log.error("SaveGame vor Weltversand", t);
            }
        } else {
            Profile.forgetHostProfiles();
        }
        meta.map = MyPanel.select;
        meta.mapId = MyPanel.selectid;
        meta.mode = MyPanel.modeKIND;
        meta.difficulty = MyPanel.Difficulty;
        meta.te = MyPanel.te;
        meta.hostX = MyPanel.doucount[0][300];
        meta.hostY = MyPanel.doucount[1][300];
        meta.reason = pendingReason;
        Path dir = gamePath("Save\\auto");
        // read the files here, on the game thread: the game cannot write a save at the same time
        java.util.LinkedHashMap<String, byte[]> files;
        try {
            files = readDir(dir);
        } catch (IOException e) {
            Log.error("Welt lesen", e);
            sending = false;
            sendPending = true;
            return;
        }
        Thread t = new Thread(() -> {
            try {
                byte[] zip = zip(files);
                meta.size = zip.length;
                Msg begin = new Msg(Proto.WORLD_BEGIN, 256);
                begin.str(meta.map).i32(meta.mapId).i32(meta.mode).i32(meta.difficulty).i32(meta.te);
                begin.f64(meta.hostX).f64(meta.hostY).i32(meta.size).str(meta.reason);
                Net.send(begin);
                int chunk = 256 * 1024;
                for (int off = 0; off < zip.length; off += chunk) {
                    int n = Math.min(chunk, zip.length - off);
                    Net.send(new Msg(Proto.WORLD_CHUNK, n + 8).bytes(zip, off, n));
                }
                Net.send(new Msg(Proto.WORLD_END).i32(zip.length));
                sentAt = System.currentTimeMillis();
                Log.info("Welt gesendet: " + meta.map + meta.mapId + " (" + zip.length / 1024 + " KB, " + meta.reason + ")");
                Net.status = "Welt an " + Net.peerName + " gesendet, warte bis geladen ...";
            } catch (Throwable e) {
                Log.error("Weltversand", e);
                sendPending = true;
            } finally {
                sending = false;
            }
        }, "coop-world-send");
        t.setDaemon(true);
        t.start();
    }

    static java.util.LinkedHashMap<String, byte[]> readDir(Path dir) throws IOException {
        java.util.LinkedHashMap<String, byte[]> out = new java.util.LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                out.put(dir.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
            }
        }
        return out;
    }

    static byte[] zip(java.util.Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream bout = new ByteArrayOutputStream(1 << 20);
        try (ZipOutputStream zout = new ZipOutputStream(bout)) {
            zout.setLevel(6);
            for (java.util.Map.Entry<String, byte[]> e : files.entrySet()) {
                zout.putNextEntry(new ZipEntry(e.getKey()));
                zout.write(e.getValue());
                zout.closeEntry();
            }
        }
        return bout.toByteArray();
    }

    // ------------------------------------------------------------------ client

    private static Meta incomingMeta;
    private static ByteArrayOutputStream incoming;
    /** a world from the host is being loaded: load() is allowed */
    private static volatile boolean loadAllowed;
    /** the client's save paths point to Save\coop\auto */
    private static volatile boolean redirect;
    static volatile Meta current;
    public static volatile int progress;

    public static void handle(Msg m) {
        switch (m.type) {
            case Proto.WORLD_BEGIN: {
                Meta meta = new Meta();
                meta.map = m.str();
                meta.mapId = m.i32();
                meta.mode = m.i32();
                meta.difficulty = m.i32();
                meta.te = m.i32();
                meta.hostX = m.f64();
                meta.hostY = m.f64();
                meta.size = m.i32();
                meta.reason = m.str();
                incomingMeta = meta;
                incoming = new ByteArrayOutputStream(Math.max(1024, meta.size));
                // positions received before this world are from the old world
                Puppet.forget();
                progress = 0;
                Net.status = "Empfange Welt vom Host ...";
                Log.info("Empfange Welt " + meta.map + meta.mapId + " (" + meta.size / 1024 + " KB, " + meta.reason + ")");
                break;
            }
            case Proto.WORLD_CHUNK: {
                if (incoming != null) {
                    incoming.write(m.array(), 0, m.length());
                    if (incomingMeta != null && incomingMeta.size > 0) {
                        progress = (int) (100L * incoming.size() / incomingMeta.size);
                    }
                }
                break;
            }
            case Proto.WORLD_END: {
                int size = m.i32();
                if (incoming == null || incoming.size() != size) {
                    Log.info("Welt unvollstaendig, fordere neu an");
                    Net.send(new Msg(Proto.WORLD_REQUEST));
                    incoming = null;
                    return;
                }
                byte[] zip = incoming.toByteArray();
                Meta meta = incomingMeta;
                incoming = null;
                // installed by clientTick() as soon as no load() is running
                pendingZip = zip;
                pendingMeta = meta;
                break;
            }
            case Proto.WORLD_LOADED: {
                if (Net.isHost()) {
                    Coop.clientPlaying = true;
                    Net.status = "Verbunden mit " + Net.peerName;
                    Log.info("Client hat die Welt geladen (" + (System.currentTimeMillis() - sentAt) + " ms)");
                    Coop.message(Net.peerName + " ist im Spiel");
                }
                break;
            }
            case Proto.WORLD_REQUEST:
                requestSend("Client fordert Welt an", true);
                break;
            default:
                break;
        }
    }

    private static volatile byte[] pendingZip;
    private static volatile Meta pendingMeta;

    /** Client, every frame (FX thread in menus, game thread in game): install a received world. */
    public static void clientTick() {
        if (pendingZip == null || Coop.loading || MyPanel.dir1) {
            return;
        }
        byte[] zip = pendingZip;
        Meta meta = pendingMeta;
        pendingZip = null;
        pendingMeta = null;
        installAndLoad(zip, meta);
    }

    private static void installAndLoad(byte[] zip, Meta meta) {
        try {
            Path dir = gamePath(COOP_DIR);
            if (Files.exists(dir)) {
                try (Stream<Path> w = Files.walk(dir)) {
                    w.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            // ignore
                        }
                    });
                }
            }
            Files.createDirectories(dir);
            try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zip))) {
                ZipEntry e;
                while ((e = zin.getNextEntry()) != null) {
                    if (e.isDirectory()) {
                        continue;
                    }
                    Path out = dir.resolve(e.getName().replace('/', java.io.File.separatorChar)).normalize();
                    if (!out.startsWith(dir)) {
                        continue; // never write outside the coop folder
                    }
                    Files.createDirectories(out.getParent());
                    Files.write(out, zin.readAllBytes());
                }
            }
        } catch (Throwable t) {
            Log.error("Welt entpacken", t);
            Coop.message("Fehler beim Entpacken der Welt: " + t.getMessage());
            return;
        }
        current = meta;
        startLoad(meta);
    }

    /** Runs on the FX thread (menu) or the worker thread (in game). */
    /** client: own place before a travel-reload of the same map (the guest stays where he is) */
    private static String keepMap;
    private static double keepX;
    private static double keepY;

    private static void startLoad(Meta meta) {
        keepMap = null;
        Profile.travelCarry = null;
        boolean travel = "Kartenwechsel".equals(meta.reason) && Coop.clientInWorld && MyPanel.ctcode != null
                && MyPanel.ctheal[300] > 0 && !DeathSync.localDead();
        if (travel) {
            try {
                // the own character as it is right now (the host's copy can be a few seconds old)
                Profile.travelCarry = Profile.capture();
            } catch (Throwable t) {
                Log.error("Profil mitnehmen", t);
            }
            keepMap = MyPanel.select + MyPanel.selectid;
            keepX = MyPanel.doucount[0][300];
            keepY = MyPanel.doucount[1][300];
        }
        redirect = true;
        loadAllowed = true;
        Coop.clientInWorld = true;
        Coop.forcedTe = meta.te;
        MyPanel.te = meta.te;
        Coop.forcedDifficulty = meta.difficulty;
        MyPanel.Difficulty = meta.difficulty;
        if (MyPanel.Humanrespawn != null) {
            MyPanel.Humanrespawn.newgamekind = -1;
        }
        Game.Jump.jump = 0;
        DeathSync.reset();
        Puppet.reset();
        MyPanel.page = 3;
        MyPanel.dir1 = true;
        Net.status = "Lade die Welt des Hosts ...";
        Log.info("Starte Laden der Host-Welt " + meta.map + meta.mapId);
    }

    /** Client, end of load(): place the player next to the host and report. */
    public static void clientLoaded() {
        loadAllowed = false;
        Meta meta = current;
        double hx = meta != null ? meta.hostX : MyPanel.doucount[0][300];
        double hy = meta != null ? meta.hostY : MyPanel.doucount[1][300];
        PlayerSync.State host = Puppet.latest;
        if (host != null && host.map != null && host.map.equals(MyPanel.select + MyPanel.selectid)) {
            hx = host.x;
            hy = host.y;
        }
        double[] spot = spawnSpot(hx, hy);
        double px = spot[0];
        double py = spot[1];
        if (keepMap != null && keepMap.equals(MyPanel.select + MyPanel.selectid)) {
            // the host reloaded the same map (e.g. after a finished mission): stay where you were
            int[] cell = new int[2];
            if (MyPanel.position.findposition((int) keepX, (int) keepY, cell)) {
                px = keepX;
                py = keepY;
            }
        }
        keepMap = null;
        MyPanel.doucount[0][300] = px;
        MyPanel.doucount[1][300] = py;
        MyPanel.keyx = MyPanel.keyxsmooth = -px;
        MyPanel.keyy = MyPanel.keyysmooth = -py;
        MyPanel.ctname[300] = Config.name;
        Profile.clientApplyAfterLoad();
        Net.send(new Msg(Proto.WORLD_LOADED));
        Net.status = "Verbunden mit " + Net.peerName;
        Log.info("Host-Welt geladen, Spieler bei " + (int) px + "," + (int) py);
    }

    /** a walkable place next to the host that the host can see (not behind a wall) */
    static double[] spawnSpot(double hx, double hy) {
        double[][] offs = {{60, 0}, {-60, 0}, {0, 60}, {0, -60}, {45, 45}, {-45, 45}, {45, -45}, {-45, -45},
            {110, 0}, {-110, 0}, {0, 110}, {0, -110}};
        int[] out = new int[2];
        for (double[] o : offs) {
            double x = hx + o[0];
            double y = hy + o[1];
            try {
                if (MyPanel.position.findposition((int) x, (int) y, out) && MyPanel.checkpointseeFAST(hx, hy, x, y)) {
                    return new double[] {x, y};
                }
            } catch (Throwable t) {
                break;
            }
        }
        return new double[] {hx, hy};
    }

    /** load() on the client only happens for worlds from the host. */
    public static boolean blockClientLoad() {
        return Net.isClient() && Coop.clientInWorld && !loadAllowed;
    }

    /** test instances keep everything in their own folder, e.g. Save\cooptest\host */
    private static final String TEST_ROOT = System.getProperty("coop.saveRoot");

    public static String mapPath(String s) {
        if (s == null) {
            return null;
        }
        if (redirect) {
            s = s.replace("Save\\auto", COOP_DIR);
        }
        return testRoot(s);
    }

    static String testRoot(String s) {
        if (TEST_ROOT != null && s.startsWith("Save\\")) {
            return TEST_ROOT + s.substring(4);
        }
        return s;
    }

    /** absolute path of a save folder as the game sees it ("Save\\auto" ...) */
    public static Path gamePath(String rel) {
        return Path.of(MyPanel.pathall + testRoot(rel));
    }

    /** The client left the session: go back to its own saves once it is in the main menu. */
    public static void leaveWorld() {
        loadAllowed = false;
        Coop.clientInWorld = false;
        if (MyPanel.page != 3) {
            redirect = false;
        } else {
            MyPanel.page = 0;
            Coop.runFx(() -> redirect = false);
        }
        if (Coop.forcedDifficulty >= 0) {
            Coop.forcedDifficulty = -1;
        }
        Log.info("Coop-Welt verlassen");
    }

    public static boolean redirectActive() {
        return redirect;
    }
}
