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

    /** the figure is shown this much in the past, so there are always two states to interpolate between */
    static final long RENDER_DELAY_MS = 60;

    /** interpolation buffer of received states */
    private static final int N = 8;
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
            // late packet: keep moving with the last speed for a short time
            if (count >= 2) {
                PlayerSync.State before = buf[(head - 1 + N) % N];
                long dt = newest.time - before.time;
                double ahead = Math.min(100, t - newest.time);
                if (dt > 5 && dt < 200 && (newest.flags & PlayerSync.F_MOVE) != 0) {
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
        spawned = true;
        Log.info("Mitspieler-Figur erzeugt: " + name + " bei " + (int) s.x + "," + (int) s.y);
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
        Log.info("Mitspieler-Figur entfernt");
    }

    /** after a load() all counters were reset: spawn again later */
    public static void reset() {
        spawned = false;
        synchronized (Puppet.class) {
            count = 0;
            head = -1;
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
        long t = System.currentTimeMillis() - RENDER_DELAY_MS;
        double[] p = sample(t);
        if (p == null) {
            return;
        }
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
        appliedX = p[0];
        appliedY = p[1];
        MyPanel.doucount[0][k] = p[0];
        MyPanel.doucount[1][k] = p[1];
        MyPanel.gocounter[0][k] = p[0];
        MyPanel.gocounter[1][k] = p[1];
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
            }
        }
        MyPanel.ctheal[k] = Math.max(0.5, s.heal);
        MyPanel.ctfall[k] = (s.flags & PlayerSync.F_FALL) != 0;
        if (MyPanel.ctfall[k]) {
            MyPanel.ctfallkind[k] = (byte) s.fallkind;
        }
        // held item
        MyPanel.cthandlekind[k] = s.handlekind;
        MyPanel.cthandlename[k] = s.handlename;
        MyPanel.cthandleid[k] = poolSlot(s.handlekind, s.handlename);
        MyPanel.cthandlereload[k] = (s.flags & PlayerSync.F_RELOAD) != 0;
        MyPanel.ctrunaimfired[k] = !MyPanel.ctrun[k];
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
