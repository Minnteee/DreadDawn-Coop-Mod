package coop.sync;

import Game.Animation;
import Game.HeadDialog;
import Game.Humanrespawn;
import Game.MyPanel;
import Game.Zombierespawn;
import coop.Coop;
import coop.Log;
import coop.net.Msg;
import coop.net.Net;
import coop.net.Proto;
import java.io.File;
import coop.T;

/**
 * Story presentation for both players:
 * - cutscenes (Animation): the host's script is played by the client's own game, time-synchronized;
 *   the figures come from the host (the host's player figure is the puppet on the client);
 * - dialog boxes with portraits (HeadDialog) of the story and of story figures: shown on both machines,
 *   the next line comes when BOTH players pressed space (nobody misses text);
 * - story pictures, story hint texts and story sounds.
 * Text files are loaded in each player's own language when they exist.
 * The story itself advances on the host only (the client's own triggers are off).
 */
public final class StorySync {
    private StorySync() {}

    static final int V_CUT = 1;
    static final int V_DIALOG = 2;

    private static final ThreadLocal<Boolean> applying = ThreadLocal.withInitial(() -> false);

    // ------------------------------------------------------------------ paths

    static String lang() {
        try {
            return MyPanel.languagename_[MyPanel.language];
        } catch (Throwable t) {
            return "-E";
        }
    }

    /** game path -> machine independent template ({ROOT} = game folder, {LANG} = language suffix) */
    static String toTemplate(String path) {
        if (path == null) {
            return "";
        }
        String s = path;
        String prefix = "";
        if (s.startsWith("file:")) {
            prefix = "file:";
            s = s.substring(5);
        }
        String root = MyPanel.pathall;
        if (root != null && s.startsWith(root)) {
            s = "{ROOT}" + s.substring(root.length());
        }
        String l = lang();
        int i = s.lastIndexOf(l + ".");
        if (i >= 0) {
            s = s.substring(0, i) + "{LANG}" + s.substring(i + l.length());
        }
        return prefix + s;
    }

    /** template -> local path, in the own language if that file exists, else in the sender's language */
    static String fromTemplate(String t, String senderLang) {
        String s = t;
        String prefix = "";
        if (s.startsWith("file:")) {
            prefix = "file:";
            s = s.substring(5);
        }
        s = s.replace("{ROOT}", MyPanel.pathall);
        if (s.contains("{LANG}")) {
            String own = s.replace("{LANG}", lang());
            s = new File(own).exists() ? own : s.replace("{LANG}", senderLang);
        }
        return prefix + s;
    }

    // ------------------------------------------------------------------ cutscenes

    private static boolean hostWasPlaying;
    private static long lastTimeSend;
    private static double lastHostTime;
    /** number of jumps (skips, rewinds) done by the host, part of the vote token */
    private static int skipEpoch;
    private static volatile int clientEpoch;
    /** client: the running cutscene came from the host */
    private static volatile boolean mirrored;

    /** cutscenes that belong to one player only (lover scenes, the map editor) */
    private static boolean privateScene(String path) {
        return MyPanel.loveranimation || path == null || path.contains("etc\\Play") || path.contains("Editor\\Play")
                || MyPanel.editor;
    }

    /** Hook at the end of Animation.playfore(path, smooth): the host tells the client. */
    public static void cutsceneStarted(String path, boolean smooth) {
        Animation a = MyPanel.Animation;
        if (applying.get() || a == null || !a.play || !Net.isHost() || !Coop.playing() || privateScene(path)) {
            return;
        }
        // the host is down or dead: the story goes on with him
        DeathSync.requestRespawn(a.inplaying(300, 0));
        Msg m = new Msg(Proto.CUT_START, 256).str(toTemplate(path)).str(lang()).bool(smooth);
        int n = a.playnuming;
        m.u8(n);
        for (int x = 0; x < n; x++) {
            m.i16(a.playerid[x]).u8(a.playerkind[x]).u8(a.playerfinishkind[x]);
        }
        m.f64(a.playtimeall).f64(a.playtimefore).f64(a.playtimeallup).bool(a.hideteamate);
        Net.send(m);
        hostWasPlaying = true;
        lastHostTime = a.playtimeall;
        clearVotes();
        Log.info("cutscene to the partner: " + toTemplate(path));
    }

    /** FX thread, every frame: the host keeps the client's cutscene clock in step. */
    public static void fxTick() {
        Animation a = MyPanel.Animation;
        if (a == null) {
            return;
        }
        if (Net.isHost()) {
            if (!Coop.playing()) {
                hostWasPlaying = false;
                return;
            }
            long now = System.currentTimeMillis();
            if (a.play && hostWasPlaying) {
                boolean jumped = a.playtimeall < lastHostTime - 0.3; // rewind (arrow key)
                lastHostTime = a.playtimeall;
                if (jumped) {
                    skipEpoch++;
                }
                if (jumped || now - lastTimeSend > 100) {
                    lastTimeSend = now;
                    sendTime(a);
                }
            }
            if (!a.play && hostWasPlaying) {
                hostWasPlaying = false;
                Net.send(new Msg(Proto.CUT_END));
                clearVotes();
            }
        } else {
            if (mirrored && !a.play) {
                mirrored = false;
                clearVotes();
                clientSceneEnded();
            }
            besideTick();
        }
    }

    private static void sendTime(Animation a) {
        Net.send(new Msg(Proto.CUT_TIME).f64(a.playtimeall).f64(a.pauseTIME).bool(a.move).i32(skipEpoch));
    }

    /** the host's scene hides teammates: at its end the client's figure stands next to the host, like a teammate */
    private static volatile boolean endBesideHost;
    /** client: when to check the distance to the host after a scene (the host's real position is known again) */
    private static volatile long besideAt;

    private static void clientSceneEnded() {
        if (endBesideHost) {
            endBesideHost = false;
            besideAt = System.currentTimeMillis() + 700;
        }
    }

    private static void besideTick() {
        if (besideAt == 0 || System.currentTimeMillis() < besideAt) {
            return;
        }
        besideAt = 0;
        Coop.runSim(StorySync::standBesideHost);
    }

    private static void standBesideHost() {
        PlayerSync.State s = Puppet.latestState();
        Animation a = MyPanel.Animation;
        if (s == null || !Puppet.active() || !Coop.playing() || MyPanel.ctheal[300] <= 0 || (a != null && a.play)
                || s.heal <= 0) {
            return;
        }
        double dx = MyPanel.doucount[0][300] - s.x;
        double dy = MyPanel.doucount[1][300] - s.y;
        if (dx * dx + dy * dy <= 300 * 300) {
            return;
        }
        int[] out = new int[2];
        double[][] tries = {{40, 25}, {-40, 25}, {40, -25}, {-40, -25}, {0, 45}, {0, -45}, {0, 0}};
        for (double[] o : tries) {
            double nx = s.x + o[0];
            double ny = s.y + o[1];
            boolean free;
            try {
                free = MyPanel.position.findposition((int) nx, (int) ny, out);
            } catch (Throwable t) {
                free = o[0] == 0 && o[1] == 0;
            }
            if (free || (o[0] == 0 && o[1] == 0)) {
                MyPanel.doucount[0][300] = nx;
                MyPanel.doucount[1][300] = ny;
                MyPanel.ctspeed[0][300] = 0;
                MyPanel.ctspeed[1][300] = 0;
                Log.info("placed next to the host after the cutscene");
                return;
            }
        }
    }

    private static void clientStart(String template, String senderLang, boolean smooth, int[] ids, int[] kinds,
            int[] finish, double t, double tf, double tup, boolean hide) {
        Animation a = MyPanel.Animation;
        if (a == null || !Coop.playing()) {
            return;
        }
        String path = fromTemplate(template, senderLang);
        if (!new File(path + "move.txt").exists()) {
            Log.info("cutscene missing: " + path);
            return;
        }
        applying.set(true);
        try {
            a.playfore(path, smooth);
        } catch (Throwable e) {
            Log.error("starting cutscene " + path, e);
        } finally {
            applying.set(false);
        }
        if (!a.play) {
            return;
        }
        // a dead / downed guest comes back for the scene (beside the host)
        DeathSync.requestRespawn(false);
        // the figures of the host's script: the host's own player is our puppet
        for (int z = 0; z < MyPanel.te && z < a.tplayid.length; z++) {
            a.tplayid[z] = -1;
        }
        for (int x = 0; x < a.playnuming; x++) {
            int id = x < ids.length ? ids[x] : -1;
            int kind = x < kinds.length ? kinds[x] : 2;
            int fin = x < finish.length ? finish[x] : 2;
            if (kind == 0 && id == 300) {
                id = Puppet.active() ? Puppet.K : -1;
                fin = 2; // the puppet keeps following the network when the scene ends
            } else if (kind == 0 && id == Puppet.K) {
                id = -1; // never move the own player by the host's script
            }
            a.playerid[x] = id;
            a.playerkind[x] = (byte) kind;
            a.playerfinishkind[x] = (byte) fin;
            if (id > -1 && kind == 0) {
                a.player[x][0] = MyPanel.doucount[0][id];
                a.player[x][1] = MyPanel.doucount[1][id];
            } else if (id > -1 && kind == 1) {
                a.player[x][0] = MyPanel.douterro[0][id];
                a.player[x][1] = MyPanel.douterro[1][id];
                if (id < a.tplayid.length) {
                    a.tplayid[id] = (byte) x;
                }
            }
            if (id > -1 && (kind == 0 || kind == 1)) {
                double sx = a.playmove[x][0][0][0];
                double sy = a.playmove[x][0][0][1];
                double ddx = a.player[x][0] - sx;
                double ddy = a.player[x][1] - sy;
                if (ddx * ddx + ddy * ddy > 250000.0) {
                    a.player[x][0] = sx;
                    a.player[x][1] = sy;
                }
            }
        }
        a.hideteamate = hide;
        a.playtimeall = t;
        a.playtimefore = tf;
        a.playtimeallup = tup;
        mirrored = true;
        endBesideHost = hide;
        clearVotes();
        Log.info("cutscene from the host: " + path);
    }

    /** client: this figure is moved by the running cutscene (kind 0 = human, 1 = zombie) */
    public static boolean scripted(int id, int kind) {
        if (!mirrored) {
            return false;
        }
        Animation a = MyPanel.Animation;
        if (a == null || !a.play) {
            return false;
        }
        if (kind == 1) {
            return id >= 0 && id < a.tplayid.length && a.tplayid[id] >= 0;
        }
        return a.inplaying(id, kind);
    }

    /** client: a cutscene from the host is running */
    public static boolean clientScenePlaying() {
        Animation a = MyPanel.Animation;
        return mirrored && a != null && a.play;
    }

    /** Hook at the start of Animation.play() (FX thread): space / arrow keys become a vote in co-op. */
    public static void cutscenePlayHead() {
        Animation a = MyPanel.Animation;
        if (a == null || !Coop.playing() || (Net.isClient() && !mirrored) || (Net.isHost() && !hostWasPlaying)) {
            return;
        }
        boolean want = false;
        if (MyPanel.SPACE) {
            MyPanel.SPACE = false;
            want = true;
        }
        if (a.skip == 1) {
            a.skip = 0;
            want = true;
        }
        if (a.skip == -1 && Net.isClient()) {
            a.skip = 0; // rewinding is the host's
        }
        if (want) {
            vote(V_CUT, Net.isHost() ? skipEpoch : clientEpoch);
        }
    }

    // ---- the client never creates figures or gives guns for a scene: they come from the host ----

    private static boolean clientScript() {
        return applying.get() && Net.isClient();
    }

    /** redirect of Humanrespawn.animation(kind, teamatekind) inside Animation.playfore */
    public static int cutHumanActor(Humanrespawn h, byte kind, byte teamatekind) {
        if (clientScript()) {
            return 0; // any valid slot; replaced by the host's figure right after
        }
        return h.animation(kind, teamatekind);
    }

    /** redirect of Zombierespawn.animation(kind) inside Animation.playfore */
    public static int cutZombieActor(Zombierespawn z, byte kind) {
        if (clientScript()) {
            return 0;
        }
        return z.animation(kind);
    }

    /** redirect of Humanrespawn.getgunbyname(name, pack, ex) inside Animation.playfore */
    public static void cutGiveGun(Humanrespawn h, int name, int pack, double ex) {
        if (clientScript()) {
            return;
        }
        h.getgunbyname(name, pack, ex);
    }

    // ------------------------------------------------------------------ dialog boxes

    /** dialog box currently open on both machines */
    private static volatile boolean sharedDialog;

    /** story dialogs (no speaker position) and lines of story figures on the map are shown to both */
    static boolean storyDialog(String path, int wx, int wy) {
        if (path == null) {
            return false;
        }
        return (wx == -1 && wy == -1) || path.contains("\\MapHuman\\");
    }

    /** Hook at the end of HeadDialog.headDialogfore(path, x, y). */
    public static void dialogStarted(String path, int wx, int wy) {
        HeadDialog d = MyPanel.HeadDialog;
        if (applying.get()) {
            return;
        }
        if (d == null || !d.HeadDialog || !Coop.playing() || !storyDialog(path, wx, wy)) {
            sharedDialog = false;
            return;
        }
        sharedDialog = true;
        clearVotes();
        int npc = path.contains("\\MapHuman\\") ? MyPanel.dialogid : -1;
        Net.send(new Msg(Proto.DIALOG_START, 256).str(toTemplate(path)).str(lang()).i16(npc));
    }

    private static void remoteDialog(String template, String senderLang, int npc) {
        HeadDialog d = MyPanel.HeadDialog;
        if (d == null || !Coop.playing()) {
            return;
        }
        if (Net.isHost() && sharedDialog && d.HeadDialog && !d.close) {
            return; // both talked at the same moment: the host's dialog wins
        }
        if (Net.isHost() && npc >= 0 && npc < Puppet.K && MyPanel.Humanrespawn != null) {
            // the client talked to a story figure: it has said its line (as in single player)
            if (!MyPanel.Humanrespawn.speakloop[npc]) {
                MyPanel.Humanrespawn.speak[npc] = null;
            }
        }
        String path = fromTemplate(template, senderLang);
        if (!new File(path).exists()) {
            Log.info("dialog missing: " + path);
            return;
        }
        applying.set(true);
        try {
            d.headDialogfore(path, -1, -1);
        } finally {
            applying.set(false);
        }
        sharedDialog = d.HeadDialog;
        clearVotes();
    }

    /** Hook at the start of HeadDialog.speak() (space). true = skip the game's own handling. */
    public static boolean dialogSpeak() {
        HeadDialog d = MyPanel.HeadDialog;
        if (applying.get() || d == null || !d.HeadDialog || !sharedDialog || !Coop.playing()) {
            return false;
        }
        if (d.alphahead == 1.0 && d.word[d.wordnuming] != null && d.wordlengthing < d.word[d.wordnuming].length()) {
            return false; // first press shows the whole line (only here)
        }
        if (d.alphahead == 1.0) {
            vote(V_DIALOG, d.wordnuming);
        }
        return true;
    }

    /** the next line (or close) on this machine, exactly like a space press in single player */
    private static void advanceDialog() {
        HeadDialog d = MyPanel.HeadDialog;
        if (d == null || !d.HeadDialog || d.close) {
            return;
        }
        applying.set(true);
        try {
            if (d.word[d.wordnuming] != null) {
                d.wordlengthing = d.word[d.wordnuming].length();
            }
            d.alphahead = 1.0;
            d.speak();
        } finally {
            applying.set(false);
        }
    }

    private static void dialogStep(int line, boolean close) {
        HeadDialog d = MyPanel.HeadDialog;
        if (d == null || !d.HeadDialog || !sharedDialog) {
            return;
        }
        int guard = 0;
        while (d.HeadDialog && !d.close && d.wordnuming < line && guard++ < 60) {
            advanceDialog();
        }
        if (close && d.HeadDialog && !d.close) {
            advanceDialog();
        }
        clearVotes();
    }

    // ------------------------------------------------------------------ story pictures, hints, sounds

    /** Hook at the start of Direction.photo(path) (host: story picture). */
    public static void storyPhoto(String path) {
        if (applying.get() || !Net.isHost() || !Coop.playing() || path == null) {
            return;
        }
        Net.send(new Msg(Proto.PHOTO, 256).str(toTemplate("file:" + path)).str(lang()));
    }

    /** Hook at the start of MapThing.treasureUSE (host: a map event fires). */
    public static void treasureUse(int kind, String mission, String branch, String option, int zonetime) {
        if (applying.get() || !Net.isHost() || !Coop.playing() || MyPanel.MapThing == null || option == null) {
            return;
        }
        String base = MyPanel.MapThing.getreasurepath(mission, branch);
        switch (kind) {
            case 0: // picture
                Net.send(new Msg(Proto.PHOTO, 256).str(toTemplate(base + "Photo\\" + option + lang() + ".png")).str(lang()));
                break;
            case 4: // hint text
                Net.send(new Msg(Proto.STORY_HINT, 256).str(toTemplate(base + "Hint\\" + option + lang() + ".txt"))
                        .str(lang()).f64(500.0));
                break;
            case 6: // sound
                Net.send(new Msg(Proto.STORY_SOUND, 128).str(toTemplate(MyPanel.pathall + "Sounds\\Mission\\" + option + ".wav"))
                        .str(lang()));
                break;
            default:
                break; // dialogs and scenes have their own hooks, the rest is the host's
        }
    }

    /** after WordAll.hint(path, time) inside Direction.targetouch (host: story hint text) */
    public static void storyHint(String path, double time) {
        if (!applying.get() && Net.isHost() && Coop.playing() && path != null) {
            Net.send(new Msg(Proto.STORY_HINT, 256).str(toTemplate(path)).str(lang()).f64(time));
        }
    }

    private static void remotePhoto(String template, String senderLang) {
        if (!Coop.playing()) {
            return;
        }
        String url = fromTemplate(template, senderLang);
        try {
            MyPanel.picturehint = new javafx.scene.image.Image(url);
            MyPanel.PHOTO = true;
            MyPanel.playeruse = true;
        } catch (Throwable t) {
            Log.error("Bild anzeigen " + url, t);
        }
    }

    private static void remoteHint(String template, String senderLang, double time) {
        if (!Coop.playing() || MyPanel.WordAll == null) {
            return;
        }
        String path = fromTemplate(template, senderLang);
        if (new File(path).exists()) {
            MyPanel.WordAll.hint(path, time);
        }
    }

    private static void remoteSound(String template, String senderLang) {
        if (!Coop.playing() || MyPanel.Animation == null) {
            return;
        }
        File f = new File(fromTemplate(template, senderLang));
        if (f.exists()) {
            try {
                MyPanel.Animation.sound(new javafx.scene.media.Media(f.toURI().toString()));
            } catch (Throwable t) {
                Log.error("story sound " + f, t);
            }
        }
    }

    // ------------------------------------------------------------------ votes ("both pressed space")

    private static int myKind;
    private static int myToken;
    private static long myAt;
    private static int partnerKind;
    private static int partnerToken;
    private static long partnerAt;
    private static final long VOTE_TTL = 15000;

    private static synchronized void clearVotes() {
        myKind = 0;
        partnerKind = 0;
    }

    private static void vote(int kind, int token) {
        synchronized (StorySync.class) {
            myKind = kind;
            myToken = token;
            myAt = System.currentTimeMillis();
        }
        Net.send(new Msg(Proto.STORY_VOTE).u8(kind).i32(token));
        if (Net.isHost()) {
            checkVotes();
        }
    }

    private static void checkVotes() {
        int kind;
        synchronized (StorySync.class) {
            long now = System.currentTimeMillis();
            if (myKind == 0 || partnerKind != myKind || partnerToken != myToken || now - myAt > VOTE_TTL
                    || now - partnerAt > VOTE_TTL) {
                return;
            }
            kind = myKind;
            myKind = 0;
            partnerKind = 0;
        }
        if (kind == V_CUT) {
            Animation a = MyPanel.Animation;
            if (a != null && a.play) {
                a.skip(false);
                skipEpoch++;
                lastHostTime = a.playtimeall;
                sendTime(a);
            }
        } else if (kind == V_DIALOG) {
            advanceDialog();
            HeadDialog d = MyPanel.HeadDialog;
            Net.send(new Msg(Proto.DIALOG_STEP).i32(d.wordnuming).bool(d.close || !d.HeadDialog));
        }
    }

    /** text for the overlay: who still has to press space */
    public static String voteHint() {
        if (!Coop.playing()) {
            return null;
        }
        long now = System.currentTimeMillis();
        boolean mine = myKind != 0 && now - myAt < VOTE_TTL;
        boolean partner = partnerKind != 0 && now - partnerAt < VOTE_TTL;
        if (mine && !partner) {
            return T.s("Waiting for {0} (Space) ...", Net.peerName);
        }
        if (partner && !mine) {
            return T.s("{0} is ready - press Space", Net.peerName);
        }
        return null;
    }

    // ------------------------------------------------------------------ messages

    private static void clientTime(double t, double pause, boolean move, int epoch) {
        Animation a = MyPanel.Animation;
        boolean jump = epoch != clientEpoch;
        if (jump) {
            clientEpoch = epoch;
            clearVotes();
        }
        if (a == null || !a.play || !mirrored) {
            return;
        }
        a.move = move;
        if (jump) {
            a.playtimeall = t; // skip or rewind on the host
            a.pauseTIME = pause;
            return;
        }
        double d = t - a.playtimeall;
        if (d > 0.8) {
            a.playtimeall = t; // behind: catch up
            a.pauseTIME = pause;
        } else if (d < -0.8) {
            // ahead: wait (10 pause units = 1 time unit); never jump back, events would repeat
            a.pauseTIME = Math.max(Math.max(a.pauseTIME, pause), -d * 10.0);
        } else if (pause > a.pauseTIME) {
            a.pauseTIME = pause;
        }
    }

    public static void handle(Msg m) {
        switch (m.type) {
            case Proto.CUT_START: {
                String t = m.str();
                String l = m.str();
                boolean smooth = m.bool();
                int n = m.u8();
                int[] ids = new int[n];
                int[] kinds = new int[n];
                int[] finish = new int[n];
                for (int i = 0; i < n; i++) {
                    ids[i] = m.i16();
                    kinds[i] = m.u8();
                    finish[i] = m.u8();
                }
                double t0 = m.f64();
                double tf = m.f64();
                double tup = m.f64();
                boolean hide = m.bool();
                if (Net.isClient()) {
                    Coop.runFx(() -> clientStart(t, l, smooth, ids, kinds, finish, t0, tf, tup, hide));
                }
                break;
            }
            case Proto.CUT_TIME: {
                double t = m.f64();
                double pause = m.f64();
                boolean move = m.bool();
                int epoch = m.i32();
                if (Net.isClient()) {
                    Coop.runFx(() -> clientTime(t, pause, move, epoch));
                }
                break;
            }
            case Proto.CUT_END:
                if (Net.isClient()) {
                    Coop.runFx(() -> {
                        Animation a = MyPanel.Animation;
                        if (a != null && a.play && mirrored) {
                            a.playtimeall = a.playtimeallup + 1;
                            a.pauseTIME = 0;
                        }
                        clearVotes();
                    });
                }
                break;
            case Proto.DIALOG_START: {
                String t = m.str();
                String l = m.str();
                int npc = m.i16();
                Coop.runFx(() -> remoteDialog(t, l, npc));
                break;
            }
            case Proto.DIALOG_STEP: {
                int line = m.i32();
                boolean close = m.bool();
                Coop.runFx(() -> dialogStep(line, close));
                break;
            }
            case Proto.STORY_VOTE: {
                int kind = m.u8();
                int token = m.i32();
                synchronized (StorySync.class) {
                    partnerKind = kind;
                    partnerToken = token;
                    partnerAt = System.currentTimeMillis();
                }
                if (Net.isHost()) {
                    Coop.runFx(StorySync::checkVotes);
                }
                break;
            }
            case Proto.PHOTO: {
                String t = m.str();
                String l = m.str();
                Coop.runFx(() -> remotePhoto(t, l));
                break;
            }
            case Proto.STORY_HINT: {
                String t = m.str();
                String l = m.str();
                double time = m.f64();
                Coop.runFx(() -> remoteHint(t, l, time));
                break;
            }
            case Proto.STORY_SOUND: {
                String t = m.str();
                String l = m.str();
                Coop.runFx(() -> remoteSound(t, l));
                break;
            }
            default:
                break;
        }
    }

    public static void reset() {
        mirrored = false;
        sharedDialog = false;
        hostWasPlaying = false;
        endBesideHost = false;
        besideAt = 0;
        clearVotes();
    }

    /** host: HeadDialog.close (prologue skip, mission change): close the guest's copy of the dialog too */
    public static void hostDialogClosed() {
        if (applying.get() || !Net.isHost() || !Coop.playing() || !sharedDialog) {
            return;
        }
        HeadDialog d = MyPanel.HeadDialog;
        Net.send(new Msg(Proto.DIALOG_STEP).i32(d == null ? 0 : d.wordnuming).bool(true));
    }
}
