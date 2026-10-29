package coop.sync;

import Game.Bone;
import Game.MyPanel;
import coop.Log;
import coop.net.Msg;
import coop.net.Proto;

/**
 * Zombies ("terro" slots) are simulated by the host only. The host sends every zombie near the
 * client's camera 20x per second; the client shows exactly these, interpolated.
 */
public final class ZombieSync {
    private ZombieSync() {}

    static final int MAX = 1500;
    /** half size of the area around the client's camera that is sent (world units) */
    static final double RANGE_X = 3200;
    static final double RANGE_Y = 2600;

    // ---- host -----------------------------------------------------------------------------

    private static final int[] attackCount = new int[MAX];
    private static final boolean[] lastTuse = new boolean[MAX];
    // last sent position per slot, to send the speed (world units per ms)
    private static final double[] lastPx = new double[MAX];
    private static final double[] lastPy = new double[MAX];
    private static final long[] lastPt = new long[MAX];

    /** speed of slot x in units per ms, measured between two captures (0 after a jump/teleport) */
    private static float[] velocity(int x, double px, double py, long nowNs) {
        float vx = 0;
        float vy = 0;
        if (lastPt[x] != 0) {
            double dt = (nowNs - lastPt[x]) / 1e6;
            if (dt > 5 && dt < 300) {
                vx = (float) ((px - lastPx[x]) / dt);
                vy = (float) ((py - lastPy[x]) / dt);
                if (Math.abs(vx) > 2.5f || Math.abs(vy) > 2.5f) {
                    vx = 0;
                    vy = 0;
                }
            }
        }
        lastPx[x] = px;
        lastPy[x] = py;
        lastPt[x] = nowNs;
        return new float[] {vx, vy};
    }

    public static Msg capture() {
        PlayerSync.State c = Puppet.latest;
        double cx = c != null ? c.camx : MyPanel.doucount[0][300];
        double cy = c != null ? c.camy : MyPanel.doucount[1][300];
        int te = Math.min(MyPanel.te, MAX);
        Msg m = new Msg(Proto.ENTITIES, 8192);
        m.i32(te);
        int countPos = m.length();
        m.i16(0);
        int n = 0;
        Bone b = MyPanel.Bone;
        long nowNs = System.nanoTime();
        for (int x = 0; x < te; x++) {
            double px = MyPanel.douterro[0][x];
            double py = MyPanel.douterro[1][x];
            if (px < -9000 || Math.abs(px - cx) > RANGE_X || Math.abs(py - cy) > RANGE_Y) {
                continue;
            }
            if (MyPanel.theal[x] <= 0 && b != null && b.tdeath[x] && MyPanel.Imterro[x] < 0) {
                continue;
            }
            boolean tuse = b != null && b.tuse[x];
            if (tuse && !lastTuse[x]) {
                attackCount[x]++;
            }
            lastTuse[x] = tuse;
            int flags = 0;
            if (MyPanel.zombierun[x]) {
                flags |= 1;
            }
            if (MyPanel.terrohand[x]) {
                flags |= 2;
            }
            if (MyPanel.cteyed[x]) {
                flags |= 4;
            }
            if (MyPanel.tburned[x]) {
                flags |= 8;
            }
            if (b != null && b.tstand[x]) {
                flags |= 16;
            }
            if (MyPanel.zombiecharge[x] > 0) {
                flags |= 32;
            }
            if (MyPanel.terrohumanclose[x]) {
                flags |= 64;
            }
            if (MyPanel.terropacktaked[x]) {
                flags |= 128;
            }
            if (MyPanel.tmeleed[x]) {
                flags |= 256;
            }
            if (MyPanel.tburntime[x] > 0) {
                flags |= 512;
            }
            int cut = 0;
            for (int i = 0; i < Math.min(16, MyPanel.tbonecut[x].length); i++) {
                if (MyPanel.tbonecut[x][i]) {
                    cut |= 1 << i;
                }
            }
            m.i16(x);
            m.f32((float) px).f32((float) py).f32((float) MyPanel.angterro[x]);
            m.i32(MyPanel.theal[x]).u8(MyPanel.taction[x]).u8(MyPanel.Imterro[x]).i16(MyPanel.tshape[x]);
            m.i16(flags).f32((float) MyPanel.theight[x]).f32(MyPanel.tfly[x]);
            m.u8(clamp(MyPanel.tinjury[x])).u8(clamp(MyPanel.zombiestop[x])).u8(clamp(MyPanel.tothertime1[x]));
            m.u8(MyPanel.thitstandkind[x]).u8(clamp(MyPanel.t_e_shocktime[x])).u8(MyPanel.tblood[x]);
            m.i16(cut).u8(attackCount[x] & 0xff).u8(MyPanel.tmeleehitkind[x]);
            m.u8(MyPanel.zombiecharge[x]);
            float[] v = MyPanel.theal[x] > 0 ? velocity(x, px, py, nowNs) : new float[2];
            m.f32(v[0]).f32(v[1]);
            n++;
        }
        byte[] arr = m.array();
        arr[countPos] = (byte) (n >>> 8);
        arr[countPos + 1] = (byte) n;
        return m;
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, v));
    }

    // ---- client ---------------------------------------------------------------------------

    static final class Snap {
        long time;
        int n;
        int[] slot;
        float[] x;
        float[] y;
        float[] ang;
        int[] heal;
        int[] action;
        int[] kind;
        int[] shape;
        int[] flags;
        float[] height;
        float[] fly;
        int[] injury;
        int[] stop;
        int[] other1;
        int[] hitstand;
        int[] shock;
        int[] blood;
        int[] cut;
        int[] attack;
        int[] meleekind;
        int[] charge;
        float[] vx;
        float[] vy;
    }

    private static volatile Snap cur;
    private static volatile Snap prev;
    private static final boolean[] present = new boolean[MAX];
    private static final int[] lastAttack = new int[MAX];
    private static final float[] errX = new float[MAX];
    private static final float[] errY = new float[MAX];
    private static final float[] prevAng = new float[MAX];
    private static final long[] prevTime = new long[MAX];

    /** how far ahead of a snapshot the host's zombies are now (ms): age of the snapshot + half the ping */
    static double lead(long now, long snapTime) {
        long rtt = coop.net.Net.rttMs;
        return Math.max(0, Math.min(150, (now - snapTime) + (rtt > 0 ? rtt / 2.0 : 0)));
    }
    private static final boolean[] alive = new boolean[MAX];

    public static void receive(Msg m) {
        int te = m.i32();
        int n = m.i16() & 0xffff;
        Snap s = new Snap();
        s.time = System.currentTimeMillis();
        s.n = n;
        s.slot = new int[n];
        s.x = new float[n];
        s.y = new float[n];
        s.ang = new float[n];
        s.heal = new int[n];
        s.action = new int[n];
        s.kind = new int[n];
        s.shape = new int[n];
        s.flags = new int[n];
        s.height = new float[n];
        s.fly = new float[n];
        s.injury = new int[n];
        s.stop = new int[n];
        s.other1 = new int[n];
        s.hitstand = new int[n];
        s.shock = new int[n];
        s.blood = new int[n];
        s.cut = new int[n];
        s.attack = new int[n];
        s.meleekind = new int[n];
        s.charge = new int[n];
        s.vx = new float[n];
        s.vy = new float[n];
        for (int i = 0; i < n; i++) {
            s.slot[i] = m.i16() & 0xffff;
            s.x[i] = m.f32();
            s.y[i] = m.f32();
            s.ang[i] = m.f32();
            s.heal[i] = m.i32();
            s.action[i] = m.s8();
            s.kind[i] = m.s8();
            s.shape[i] = m.i16();
            s.flags[i] = m.i16() & 0xffff;
            s.height[i] = m.f32();
            s.fly[i] = m.f32();
            s.injury[i] = m.u8();
            s.stop[i] = m.u8();
            s.other1[i] = m.u8();
            s.hitstand[i] = m.s8();
            s.shock[i] = m.u8();
            s.blood[i] = m.s8();
            s.cut[i] = m.i16() & 0xffff;
            s.attack[i] = m.u8();
            s.meleekind[i] = m.s8();
            s.charge[i] = m.s8();
            s.vx[i] = m.f32();
            s.vy[i] = m.f32();
        }
        if (te > 0 && te != MyPanel.te && te <= MAX) {
            MyPanel.te = te;
        }
        prev = cur;
        cur = s;
    }

    public static void reset() {
        cur = null;
        prev = null;
        java.util.Arrays.fill(present, false);
        java.util.Arrays.fill(alive, false);
    }

    /** Client worker thread, after Move(). */
    public static void apply() {
        Snap s = cur;
        if (s == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Bone b = MyPanel.Bone;
        boolean[] seen = new boolean[MAX];
        if (s != lastStored) {
            // a new snapshot: keep drawing the zombie where it is now and blend the difference out
            lastStored = s;
            double lead = lead(now, s.time);
            for (int i = 0; i < s.n; i++) {
                int x = s.slot[i];
                if (x >= 0 && x < MAX && present[x]) {
                    double tx = s.x[i] + s.vx[i] * lead;
                    double ty = s.y[i] + s.vy[i] * lead;
                    double ex = MyPanel.douterro[0][x] - tx;
                    double ey = MyPanel.douterro[1][x] - ty;
                    if (Math.abs(ex) > 300 || Math.abs(ey) > 300) {
                        ex = 0; // jumped (knock back, teleport): no gliding
                        ey = 0;
                    }
                    errX[x] = (float) ex;
                    errY[x] = (float) ey;
                    prevAng[x] = (float) MyPanel.angterro[x];
                    prevTime[x] = now;
                }
            }
        }
        double lead = lead(now, s.time);
        for (int i = 0; i < s.n; i++) {
            int x = s.slot[i];
            if (x < 0 || x >= MAX) {
                continue;
            }
            seen[x] = true;
            boolean nowAlive = s.heal[i] > 0;
            if (!present[x] || (nowAlive && !alive[x])) {
                // new zombie in this slot (spawned, or came into range)
                spawnSlot(x, s, i, b);
                errX[x] = 0;
                errY[x] = 0;
                prevAng[x] = s.ang[i];
                prevTime[x] = now - 1000;
            }
            present[x] = true;
            double f = Math.max(0, Math.min(1, (now - prevTime[x]) / 50.0));
            double keep = Math.max(0, 1 - (now - prevTime[x]) / 100.0);
            // where the zombie is on the host right now (moving on with its speed)
            double px = s.x[i] + (nowAlive ? s.vx[i] * lead : 0) + errX[x] * keep;
            double py = s.y[i] + (nowAlive ? s.vy[i] * lead : 0) + errY[x] * keep;
            double da = s.ang[i] - prevAng[x];
            while (da > Math.PI) {
                da -= Math.PI * 2;
            }
            while (da < -Math.PI) {
                da += Math.PI * 2;
            }
            double pa = prevAng[x] + da * f;
            if (!StorySync.scripted(x, 1)) {
                MyPanel.douterro[0][x] = px;
                MyPanel.douterro[1][x] = py;
                MyPanel.goterrorist[0][x] = px;
                MyPanel.goterrorist[1][x] = py;
                MyPanel.tspeed[0][x] = 0;
                MyPanel.tspeed[1][x] = 0;
                MyPanel.angterro[x] = pa;
                MyPanel.aimterro[x] = pa;
                MyPanel.terro[0][x] = MyPanel.position((int) px, false);
                MyPanel.terro[1][x] = MyPanel.position((int) py, true);
            } else {
                errX[x] = 0;
                errY[x] = 0;
            }
            int oldHeal = MyPanel.theal[x];
            MyPanel.theal[x] = s.heal[i];
            if (!nowAlive && oldHeal > 0) {
                // died on the host
                if (b != null && !MyPanel.tin[x]) {
                    try {
                        b.terroristdeadoutofscreen(x);
                    } catch (Throwable t) {
                        // ignore
                    }
                }
                MyPanel.cteyed[x] = false;
            }
            alive[x] = nowAlive;
            int act = s.action[i];
            if (MyPanel.taction[x] != act) {
                MyPanel.taction[x] = (byte) act;
            }
            MyPanel.Imterro[x] = (byte) s.kind[i];
            MyPanel.tshape[x] = (short) s.shape[i];
            int fl = s.flags[i];
            MyPanel.zombierun[x] = (fl & 1) != 0;
            MyPanel.terrohand[x] = (fl & 2) != 0;
            MyPanel.cteyed[x] = (fl & 4) != 0;
            MyPanel.tburned[x] = (fl & 8) != 0;
            if ((fl & 512) != 0) {
                if (MyPanel.tburntime[x] <= 0) {
                    MyPanel.tburntime[x] = 30f; // flames on the zombie (the burn damage is the host's)
                }
            } else if (MyPanel.tburntime[x] > 0) {
                MyPanel.tburntime[x] = 0f;
            }
            if (b != null && nowAlive) {
                b.tstand[x] = (fl & 16) != 0;
            }
            MyPanel.terrohumanclose[x] = (fl & 64) != 0;
            MyPanel.terropacktaked[x] = (fl & 128) != 0;
            MyPanel.theight[x] = s.height[i];
            MyPanel.tfly[x] = s.fly[i];
            MyPanel.tinjury[x] = s.injury[i];
            MyPanel.zombiestop[x] = s.stop[i];
            MyPanel.tothertime1[x] = s.other1[i];
            MyPanel.thitstandkind[x] = (byte) s.hitstand[i];
            MyPanel.t_e_shocktime[x] = s.shock[i];
            MyPanel.tblood[x] = (byte) s.blood[i];
            MyPanel.zombiecharge[x] = (byte) s.charge[i];
            int cut = s.cut[i];
            boolean[] tc = MyPanel.tbonecut[x];
            for (int k = 0; k < Math.min(16, tc.length); k++) {
                tc[k] = (cut & (1 << k)) != 0;
            }
            if (s.attack[i] != lastAttack[x]) {
                lastAttack[x] = s.attack[i];
                if (b != null && nowAlive) {
                    if (b.tuse[x]) {
                        b.tusechange[x] = true;
                    } else {
                        b.tuse[x] = true;
                    }
                }
                MyPanel.tmeleed[x] = true;
                MyPanel.tmeleehitkind[x] = (byte) s.meleekind[i];
            }
        }
        // everything the host did not send is not near us (or does not exist): hide it
        int te = Math.min(MyPanel.te, MAX);
        for (int x = 0; x < te; x++) {
            if (!seen[x] && (present[x] || MyPanel.theal[x] > 0 || MyPanel.douterro[0][x] > -9000)) {
                hide(x, b);
            }
        }
    }

    private static Snap lastStored;

    private static void spawnSlot(int x, Snap s, int i, Bone b) {
        try {
            MyPanel.Zombierespawn.zombiefore(x);
        } catch (Throwable t) {
            Log.error("zombiefore", t);
        }
        MyPanel.Imterro[x] = (byte) s.kind[i];
        MyPanel.tshape[x] = (short) s.shape[i];
        try {
            MyPanel.Zombierespawn.getzombieweapon(x);
        } catch (Throwable t) {
            // ignore
        }
        if (b != null) {
            b.tdeath[x] = s.heal[i] <= 0;
            b.tuse[x] = false;
            b.tusechange[x] = false;
            b.tstand[x] = true;
        }
        lastAttack[x] = s.attack[i];
        alive[x] = s.heal[i] > 0;
    }

    private static void hide(int x, Bone b) {
        present[x] = false;
        alive[x] = false;
        MyPanel.theal[x] = 0;
        MyPanel.douterro[0][x] = -10000;
        MyPanel.douterro[1][x] = -10000;
        MyPanel.cteyed[x] = false;
        MyPanel.zombierun[x] = false;
        if (b != null) {
            b.tdeath[x] = true;
            b.tuse[x] = false;
        }
    }
}
