package coop.sync;

import Game.Bone;
import Game.MyPanel;
import coop.Coop;
import coop.Log;
import coop.net.Net;

/**
 * The other player as a figure in this game: friendly human slot K (299), driven only by network data.
 * The AI loops skip this slot (Hooks.aiLimit), damage to it is sent to its owner (Combat).
 */
public final class Puppet {
    private Puppet() {}

    public static final int K = 299;
    /** own figure type, not used by the game: not saved, not despawned, not commandable */
    public static final byte KIND = 7;

    private static volatile boolean spawned;
    /** position written by the last apply() (network position of the other player) */
    static volatile double appliedX;
    static volatile double appliedY;
    public static volatile String name = "";

    /** interpolation buffer of received states */
    private static final int N = 32;
    private static final PlayerSync.State[] buf = new PlayerSync.State[N];
    private static int head = -1;
    private static int count;
    static volatile PlayerSync.State latest;

    private static int lastFire = -1;
    private static int lastUse = -1;
    private static boolean wasDead;
    private static double gunPoolName = -1;

    public static boolean active() {
        return spawned;
    }

    static synchronized void push(PlayerSync.State s) {
        head = (head + 1) % N;
        buf[head] = s;
        count = Math.min(count + 1, N);
        latest = s;
    }

    /** state at time t (ms) with linear interpolation between received states */
    private static synchronized double[] sample(long t) {
        if (count == 0) {
            return null;
        }
        PlayerSync.State newest = buf[head];
        if (t >= newest.time) {
            // nothing newer yet: continue the last movement a little (damped), then wait
            if (count >= 2) {
                PlayerSync.State before = buf[(head - 1 + N) % N];
                long dt = newest.time - before.time;
                double ahead = Math.min(50, t - newest.time) * 0.8;
                if (dt > 5 && dt < 250 && (newest.flags & PlayerSync.F_MOVE) != 0) {
                    double vx = (newest.x - before.x) / dt;
                    double vy = (newest.y - before.y) / dt;
                    if (Math.abs(vx) < 2.5 && Math.abs(vy) < 2.5) {
                        return new double[] {newest.x + vx * ahead, newest.y + vy * ahead, newest.ang, newest.jump};
                    }
                }
            }
            return new double[] {newest.x, newest.y, newest.ang, newest.jump};
        }
        for (int i = 0; i < count - 1; i++) {
            PlayerSync.State b = buf[(head - i + N) % N];
            PlayerSync.State a = buf[(head - i - 1 + N) % N];
            if (a.time <= t && t <= b.time) {
                double f = b.time == a.time ? 1 : (double) (t - a.time) / (b.time - a.time);
                double da = b.ang - a.ang;
                while (da > Math.PI) {
                    da -= Math.PI * 2;
                }
                while (da < -Math.PI) {
                    da += Math.PI * 2;
                }
                return new double[] {a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, a.ang + da * f, a.jump + (b.jump - a.jump) * f};
            }
        }
        PlayerSync.State oldest = buf[(head - count + 1 + N) % N];
        return new double[] {oldest.x, oldest.y, oldest.ang, oldest.jump};
    }

    /** the received state shown at time t: flags, held item, shots belong to the shown position */
    private static synchronized PlayerSync.State stateAt(long t) {
        if (count == 0) {
            return null;
        }
        for (int i = 0; i < count; i++) {
            PlayerSync.State s = buf[(head - i + N) % N];
            if (s.time <= t) {
                return s;
            }
        }
        return buf[(head - count + 1 + N) % N];
    }

    // ---- smoothing: a correction of the shown position is faded out instead of jumping ----
    private static double errX;
    private static double errY;
    private static double prevRawX = Double.NaN;
    private static double prevRawY;
    private static long prevAt;
    /** walking direction (unit vector) of the shown figure, 0,0 when standing */
    static volatile double dirX;
    static volatile double dirY;
    /** the state belonging to the shown position */
    static volatile PlayerSync.State shown;
    private static boolean fallOn;

    /** worker: the shown position for raw sample r, v = current speed (units/ms) */
    private static double[] smooth(double rx, double ry, double vx, double vy, long now) {
        if (Double.isNaN(prevRawX)) {
            prevRawX = rx;
            prevRawY = ry;
            prevAt = now;
            return new double[] {rx, ry};
        }
        double dt = Math.max(1, Math.min(200, now - prevAt));
        double px = prevRawX + vx * dt;
        double py = prevRawY + vy * dt;
        double dev = Math.hypot(rx - px, ry - py);
        if (dev > 250) {
            errX = 0; // travel, respawn, teleport: jump there
            errY = 0;
        } else if (dev > 4) {
            errX += px - rx;
            errY += py - ry;
        }
        double k = Math.exp(-dt / 90.0);
        errX *= k;
        errY *= k;
        double e = Math.hypot(errX, errY);
        if (e > 60) {
            errX *= 60 / e;
            errY *= 60 / e;
        }
        prevRawX = rx;
        prevRawY = ry;
        prevAt = now;
        return new double[] {rx + errX, ry + errY};
    }

    private static void resetSmoothing() {
        errX = 0;
        errY = 0;
        prevRawX = Double.NaN;
        fallOn = false;
    }

    /** FX thread, before the world is drawn: the figure exactly where it is at display time */
    public static void fxRefresh() {
        if (!spawned || !Coop.playing()) {
            return;
        }
        PlayerSync.State s = shown;
        if (s == null || s.heal <= 0 || (s.flags & PlayerSync.F_DEAD) != 0 || StorySync.scripted(K, 0)
                || MyPanel.cteamatekind[K] != KIND) {
            return;
        }
        double[] p = sample(coop.net.Clock.now() - coop.net.Clock.renderDelay());
        if (p == null) {
            return;
        }
        double x = p[0] + errX;
        double y = p[1] + errY;
        if (Math.abs(x - MyPanel.doucount[0][K]) > 60 || Math.abs(y - MyPanel.doucount[1][K]) > 60) {
            return; // far from the worker's value (map change in progress): leave it to apply()
        }
        MyPanel.doucount[0][K] = x;
        MyPanel.doucount[1][K] = y;
        MyPanel.gocounter[0][K] = x + dirX * 30;
        MyPanel.gocounter[1][K] = y + dirY * 30;
    }

    /** Places the puppet in the world (worker thread, in game). */
    public static void spawn() {
        PlayerSync.State s = latest;
        if (s == null || MyPanel.Humanrespawn == null || MyPanel.ctcode == null) {
            return;
        }
        int k = K;
        try {
            MyPanel.Humanrespawn.humanfore(k);
            if (MyPanel.ctcode[k] != null) {
                MyPanel.clearpack(MyPanel.ctcode[k]);
            }
            MyPanel.ctcode[k] = MyPanel.getcode();
            MyPanel.packcreater(MyPanel.ctcode[k], 4);
        } catch (Throwable t) {
            Log.error("Puppet.spawn pack", t);
        }
        MyPanel.cteamatekind[k] = KIND;
        MyPanel.ctgroup[k] = 0;
        MyPanel.ctcontrolkind[k] = 0;
        MyPanel.Imcount[k] = 1;
        MyPanel.ctname[k] = name;
        MyPanel.ctheal[k] = Math.max(1, s.heal);
        MyPanel.ctkevlar[k] = s.kevlar;
        MyPanel.cthunger[k] = 100f;
        MyPanel.ctstrength[k] = 100;
        MyPanel.ctsleeping[k] = false;
        MyPanel.ctskateing[k] = false;
        MyPanel.ctfall[k] = false;
        MyPanel.ctinjury[k] = 0;
        MyPanel.doucount[0][k] = s.x;
        MyPanel.doucount[1][k] = s.y;
        MyPanel.gocounter[0][k] = s.x;
        MyPanel.gocounter[1][k] = s.y;
        MyPanel.ctspeed[0][k] = 0;
        MyPanel.ctspeed[1][k] = 0;
        MyPanel.angcount[k] = MyPanel.aimcount[k] = MyPanel.aimcounted[k] = s.ang;
        MyPanel.Humanrespawn.action[k] = -1;
        MyPanel.Humanrespawn.speak[k] = null;
        if (MyPanel.Bone != null) {
            MyPanel.Bone.ctdeath[k] = true;
            MyPanel.Bone.ctcut[k] = -1;
            MyPanel.Bone.ctuse[k] = false;
        }
        wasDead = false;
        lastFire = s.fire;
        lastUse = s.use;
        resetSmoothing();
        spawned = true;
        Log.info("partner figure created: " + name + " at " + (int) s.x + "," + (int) s.y);
    }

    public static void despawn() {
        if (!spawned) {
            return;
        }
        spawned = false;
        int k = K;
        try {
            MyPanel.ctheal[k] = 0;
            MyPanel.doucount[0][k] = -100000;
            MyPanel.doucount[1][k] = -100000;
            MyPanel.cteamatekind[k] = 0;
            if (MyPanel.Bone != null) {
                MyPanel.Bone.ctdeath[k] = true;
            }
            if (MyPanel.ctcode[k] != null) {
                MyPanel.clearpack(MyPanel.ctcode[k]);
                MyPanel.ctcode[k] = null;
            }
        } catch (Throwable t) {
            Log.error("Puppet.despawn", t);
        }
        Log.info("partner figure removed");
    }

    /** after a load() all counters were reset: spawn again later */
    public static void reset() {
        spawned = false;
        synchronized (Puppet.class) {
            count = 0;
            head = -1;
            resetSmoothing();
        }
    }

    /** Worker thread, after Move(): put the figure where the other player is. */
    public static void apply() {
        PlayerSync.State s = latest;
        if (s == null) {
            return;
        }
        if (!spawned) {
            if (Coop.playing()) {
                spawn();
            }
            if (!spawned) {
                return;
            }
        }
        int k = K;
        long now = coop.net.Clock.now();
        long t = now - coop.net.Clock.renderDelay();
        double[] p = sample(t);
        if (p == null) {
            return;
        }
        PlayerSync.State st = stateAt(t);
        if (st != null) {
            s = st; // flags, held item and shots that belong to the shown position
        }
        shown = s;
        boolean dead = s.heal <= 0 || (s.flags & PlayerSync.F_DEAD) != 0;
        if (MyPanel.cteamatekind[k] != KIND) {
            // the game reused the slot (e.g. a map script): take it back
            spawn();
        }
        if (StorySync.scripted(k, 0)) {
            // the host's player in a cutscene from the host: our copy of the scene moves the figure
            MyPanel.ctheal[k] = Math.max(1, MyPanel.ctheal[k]);
            return;
        }
        // walking direction from the last 50 ms (the legs walk that way, not always towards the aim)
        double[] q = sample(t - 50);
        double vx = q == null ? 0 : (p[0] - q[0]) / 50.0;
        double vy = q == null ? 0 : (p[1] - q[1]) / 50.0;
        double sp = Math.hypot(vx, vy);
        if (sp * 50 > 1.5) {
            dirX = vx / sp;
            dirY = vy / sp;
        } else {
            dirX = 0;
            dirY = 0;
        }
        double[] d = smooth(p[0], p[1], vx, vy, now);
        p[0] = d[0];
        p[1] = d[1];
        appliedX = p[0];
        appliedY = p[1];
        MyPanel.doucount[0][k] = p[0];
        MyPanel.doucount[1][k] = p[1];
        MyPanel.gocounter[0][k] = p[0] + dirX * 30;
        MyPanel.gocounter[1][k] = p[1] + dirY * 30;
        MyPanel.ctspeed[0][k] = 0;
        MyPanel.ctspeed[1][k] = 0;
        // grid cells like the game computes them for its own player (zombie paths lead there)
        int[] cell = new int[2];
        boolean found = false;
        try {
            if (MyPanel.position.findposition((int) p[0], (int) p[1], cell)) {
                MyPanel.count[0][k] = cell[0];
                MyPanel.count[1][k] = cell[1];
                MyPanel.position.findpositionstupid((int) p[0], (int) p[1], cell);
                MyPanel.countother[0][k] = cell[0];
                MyPanel.countother[1][k] = cell[1];
                found = true;
            }
        } catch (Throwable e) {
            found = false;
        }
        if (!found) {
            MyPanel.count[0][k] = MyPanel.position((int) p[0], false);
            MyPanel.count[1][k] = MyPanel.position((int) p[1], true);
            MyPanel.countother[0][k] = MyPanel.count[0][k];
            MyPanel.countother[1][k] = MyPanel.count[1][k];
        }
        MyPanel.angcount[k] = p[2];
        MyPanel.aimcount[k] = p[2];
        MyPanel.aimcounted[k] = p[2];
        MyPanel.ctfireang[k] = 0;
        MyPanel.jumpcountall[k] = (float) p[3];
        MyPanel.jumpcountspeed[k] = 0;
        MyPanel.ctrun[k] = (s.flags & PlayerSync.F_RUN) != 0;
        MyPanel.ctmove[k] = (s.flags & PlayerSync.F_MOVE) != 0;
        MyPanel.ctsleeping[k] = false;
        MyPanel.ctkevlar[k] = s.kevlar;
        MyPanel.ctname[k] = name;
        MyPanel.ctgroup[k] = 0;
        MyPanel.ctcontrolkind[k] = 0;
        MyPanel.ctinjury[k] = 0;
        if (dead) {
            if (!wasDead) {
                wasDead = true;
                MyPanel.ctheal[k] = 0;
                MyPanel.ctfall[k] = false;
            }
            MyPanel.ctheal[k] = 0;
            return;
        }
        if (wasDead) {
            wasDead = false;
            if (MyPanel.Bone != null) {
                MyPanel.Bone.ctdeath[k] = true;
                MyPanel.Bone.ctcut[k] = -1;
                MyPanel.Bone.ctstand[k] = true;
            }
        }
        MyPanel.ctheal[k] = Math.max(0.5, s.heal);
        // falls: start the game's fall animation once and let it play (setting it every frame loops the stand-up)
        boolean falling = (s.flags & PlayerSync.F_FALL) != 0;
        if (falling && !fallOn) {
            fallOn = true;
            MyPanel.ctfall[k] = true;
            MyPanel.ctfallkind[k] = (byte) s.fallkind;
            MyPanel.ctfalltime[k] = MyPanel.fallstandtime;
            if (MyPanel.Bone != null) {
                MyPanel.Bone.ctstand[k] = false;
            }
        } else if (!falling && fallOn) {
            fallOn = false;
            MyPanel.ctfall[k] = false;
        }
        // held item
        MyPanel.cthandlekind[k] = s.handlekind;
        MyPanel.cthandlename[k] = s.handlename;
        MyPanel.cthandleid[k] = poolSlot(s.handlekind, s.handlename);
        MyPanel.cthandlereload[k] = (s.flags & PlayerSync.F_RELOAD) != 0;
        // drawn like the local player: legs follow the walking direction relative to the aim (gocounter)
        MyPanel.ctrunaimfired[k] = true;
        MyPanel.zombiepush[k] = s.push;
        // shots and melee swings
        Bone b = MyPanel.Bone;
        if (s.fire != lastFire) {
            lastFire = s.fire;
            MyPanel.ctfiretime[k] = MyPanel.firetimed;
            MyPanel.ctfired[k] = true;
            if (b != null) {
                if (b.ctuse[k]) {
                    b.ctusechange[k] = true;
                } else {
                    b.ctuse[k] = true;
                }
            }
        }
        if (s.use != lastUse) {
            lastUse = s.use;
            if (b != null) {
                if (b.ctuse[k]) {
                    b.ctusechange[k] = true;
                } else {
                    b.ctuse[k] = true;
                }
            }
            if (s.handlekind == 4) {
                MyPanel.ctmeleed[k] = true;
                MyPanel.ctmeleehitkind[k] = 0;
            }
        }
    }

    /** Before Use.unity_counter: the puppet never attacks for real on this machine. */
    public static void beforeUse() {
        if (!spawned) {
            return;
        }
        MyPanel.cthandleuse[K] = false;
        PlayerSync.State s = latest;
        if (s != null) {
            MyPanel.cthaveweapon[K] = true;
            MyPanel.cthaveflashlight[K] = (s.flags & PlayerSync.F_HAVELIGHT) != 0;
            MyPanel.ctopenflashlight[K] = (s.flags & PlayerSync.F_LIGHT) != 0;
        }
    }

    /** The item instance the figure shows: a reserved pool slot holding the same item type. */
    private static int poolSlot(int kind, int itemName) {
        int slot = 4999;
        try {
            switch (kind) {
                case 1:
                    MyPanel.guname[slot] = itemName;
                    break;
                case 2:
                    MyPanel.ammoname[slot] = itemName;
                    break;
                case 3:
                    MyPanel.foodname[slot] = itemName;
                    break;
                case 4:
                    MyPanel.meleename[slot] = itemName;
                    break;
                case 5:
                    MyPanel.grenadename[slot] = itemName;
                    break;
                case 6:
                    MyPanel.buildname[slot] = itemName;
                    break;
                case 7:
                    MyPanel.trashname[slot] = itemName;
                    break;
                default:
                    break;
            }
        } catch (Throwable t) {
            // pools not ready
        }
        return slot;
    }

    /** Bone.drawcounter: the puppet uses the other player's character look. */
    public static void skin(Bone b) {
        if (b.xP == 300) {
            int[] own = PlayerSync.ownSkin();
            if (own != null) {
                b.fleshkindP = own[0];
                b.bonehardP = own[1];
            }
            return;
        }
        if (!spawned || b.xP != K) {
            return;
        }
        // walk or stand as the sender's own animation does (not decided by network stalls)
        PlayerSync.State sh = shown;
        if (sh != null && b.ctlastposition != null && b.ctmoved != null) {
            boolean moving = (sh.flags & PlayerSync.F_MOVED) != 0;
            double ux = dirX;
            double uy = dirY;
            if (moving && Math.abs(ux) + Math.abs(uy) < 0.1) {
                ux = 1;
            }
            b.ctlastposition[0][K] = MyPanel.doucount[0][K] - (moving ? ux * 3 : 0);
            b.ctlastposition[1][K] = MyPanel.doucount[1][K] - (moving ? uy * 3 : 0);
            b.ctmoved[K] = moving;
        }
        PlayerSync.State s = latest;
        if (s != null && s.flesh >= 0) {
            b.fleshkindP = s.flesh;
            b.bonehardP = s.bonehard;
        }
    }

    public static PlayerSync.State latestState() {
        return latest;
    }

    public static boolean isRemoteDead() {
        PlayerSync.State s = latest;
        return s != null && (s.heal <= 0 || (s.flags & PlayerSync.F_DEAD) != 0);
    }

    public static boolean remoteKnown() {
        return latest != null && Net.connected();
    }

    public static void forget() {
        latest = null;
        reset();
    }
}
