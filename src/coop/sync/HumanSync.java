package coop.sync;

import Game.Bone;
import Game.MyPanel;
import coop.Log;
import coop.net.Msg;
import coop.net.Proto;
import java.util.Objects;

/**
 * Other humans (survivors, bandits, traders, teammates: counter slots 0..298) are simulated by the
 * host. The host sends those near the client's camera 20x per second.
 */
public final class HumanSync {
    private HumanSync() {}

    static final int LAST = Puppet.K; // slots 0..298
    static final double RANGE_X = 3200;
    static final double RANGE_Y = 2600;

    // ---- host -----------------------------------------------------------------------------

    private static final int[] fireCount = new int[301];
    private static final int[] useCount = new int[301];
    private static final boolean[] lastUse = new boolean[301];
    private static final float[] lastFireTime = new float[301];
    private static final double[] lastPx = new double[301];
    private static final double[] lastPy = new double[301];
    private static final long[] lastPt = new long[301];

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
        Msg m = new Msg(Proto.HUMANS, 4096);
        int countPos = m.length();
        m.i16(0);
        int n = 0;
        Bone b = MyPanel.Bone;
        long nowNs = System.nanoTime();
        for (int x = 0; x < LAST; x++) {
            double px = MyPanel.doucount[0][x];
            double py = MyPanel.doucount[1][x];
            if (px < -50000 || Math.abs(px - cx) > RANGE_X || Math.abs(py - cy) > RANGE_Y) {
                continue;
            }
            if (MyPanel.ctheal[x] <= 0 && (b == null || b.ctdeath[x]) && MyPanel.ctcode[x] == null) {
                continue;
            }
            if (MyPanel.ctfiretime[x] > lastFireTime[x] + 0.5f) {
                fireCount[x]++;
            }
            lastFireTime[x] = MyPanel.ctfiretime[x];
            boolean use = b != null && b.ctuse[x];
            if (use && !lastUse[x]) {
                useCount[x]++;
            }
            lastUse[x] = use;
            int flags = 0;
            if (MyPanel.ctrun[x]) {
                flags |= 1;
            }
            if (MyPanel.ctfall[x]) {
                flags |= 2;
            }
            if (MyPanel.ctsleeping[x]) {
                flags |= 4;
            }
            if (MyPanel.cthaveweapon[x]) {
                flags |= 8;
            }
            if (MyPanel.ctrunaimfired[x]) {
                flags |= 16;
            }
            if (MyPanel.ctsafe[x]) {
                flags |= 32;
            }
            if (MyPanel.cthandlereload[x]) {
                flags |= 64;
            }
            if (MyPanel.ctopenflashlight[x]) {
                flags |= 128;
            }
            if (MyPanel.cthaveflashlight[x]) {
                flags |= 256;
            }
            if (MyPanel.ctmove[x]) {
                flags |= 512;
            }
            if (MyPanel.Humanrespawn.speakloop[x]) {
                flags |= 1024;
            }
            m.i16(x);
            m.f32((float) px).f32((float) py).f32((float) MyPanel.angcount[x]).f32((float) MyPanel.ctheal[x]);
            m.u8(MyPanel.Imcount[x]).u8(MyPanel.cteamatekind[x]).u8(MyPanel.ctgroup[x]).u8(TeamSync.shownControl(x));
            m.i32(MyPanel.Humanrespawn.action[x]).i16(flags);
            m.i32(MyPanel.cthandlekind[x]).i32(MyPanel.cthandlename[x]);
            m.f32(MyPanel.jumpcountall[x]).u8(MyPanel.ctfallkind[x]).u8(fireCount[x] & 0xff).u8(useCount[x] & 0xff);
            m.f32((float) MyPanel.gocounter[0][x]).f32((float) MyPanel.gocounter[1][x]);
            m.str(MyPanel.ctname[x]).str(MyPanel.ctcode[x]).str(MyPanel.ctmission[x]);
            float[] v = MyPanel.ctheal[x] > 0 ? velocity(x, px, py, nowNs) : new float[2];
            m.f32(v[0]).f32(v[1]);
            m.str(MyPanel.Humanrespawn.speak[x]);
            m.i32(MyPanel.ctwealth[x]).bool(MyPanel.ctfireatwill[x]);
            n++;
        }
        byte[] arr = m.array();
        arr[countPos] = (byte) (n >>> 8);
        arr[countPos + 1] = (byte) n;
        return m;
    }

    // ---- client ---------------------------------------------------------------------------

    static final class Snap {
        long time;
        float[] vx;
        float[] vy;
        int n;
        int[] slot;
        float[] x;
        float[] y;
        float[] ang;
        float[] heal;
        int[] imcount;
        int[] teamkind;
        int[] group;
        int[] control;
        int[] action;
        int[] flags;
        int[] hkind;
        int[] hname;
        float[] jump;
        int[] fallkind;
        int[] fire;
        int[] use;
        float[] gox;
        float[] goy;
        String[] name;
        String[] code;
        String[] mission;
        String[] speak;
        int[] wealth;
        boolean[] fireAtWill;
    }

    private static volatile Snap cur;
    private static Snap lastStored;
    private static final boolean[] present = new boolean[301];
    private static final boolean[] alive = new boolean[301];
    private static final int[] lastFire = new int[301];
    private static final int[] lastUseC = new int[301];
    private static final float[] errX = new float[301];
    private static final float[] errY = new float[301];
    private static final float[] prevAng = new float[301];
    private static final long[] prevTime = new long[301];

    public static void receive(Msg m) {
        int n = m.i16() & 0xffff;
        Snap s = new Snap();
        s.time = System.currentTimeMillis();
        s.n = n;
        s.vx = new float[n];
        s.vy = new float[n];
        s.slot = new int[n];
        s.x = new float[n];
        s.y = new float[n];
        s.ang = new float[n];
        s.heal = new float[n];
        s.imcount = new int[n];
        s.teamkind = new int[n];
        s.group = new int[n];
        s.control = new int[n];
        s.action = new int[n];
        s.flags = new int[n];
        s.hkind = new int[n];
        s.hname = new int[n];
        s.jump = new float[n];
        s.fallkind = new int[n];
        s.fire = new int[n];
        s.use = new int[n];
        s.gox = new float[n];
        s.goy = new float[n];
        s.name = new String[n];
        s.code = new String[n];
        s.mission = new String[n];
        s.speak = new String[n];
        s.wealth = new int[n];
        s.fireAtWill = new boolean[n];
        for (int i = 0; i < n; i++) {
            s.slot[i] = m.i16();
            s.x[i] = m.f32();
            s.y[i] = m.f32();
            s.ang[i] = m.f32();
            s.heal[i] = m.f32();
            s.imcount[i] = m.s8();
            s.teamkind[i] = m.s8();
            s.group[i] = m.s8();
            s.control[i] = m.s8();
            s.action[i] = m.i32();
            s.flags[i] = m.i16() & 0xffff;
            s.hkind[i] = m.i32();
            s.hname[i] = m.i32();
            s.jump[i] = m.f32();
            s.fallkind[i] = m.s8();
            s.fire[i] = m.u8();
            s.use[i] = m.u8();
            s.gox[i] = m.f32();
            s.goy[i] = m.f32();
            s.name[i] = m.str();
            s.code[i] = m.str();
            s.mission[i] = m.str();
            s.vx[i] = m.f32();
            s.vy[i] = m.f32();
            s.speak[i] = m.str();
            s.wealth[i] = m.i32();
            s.fireAtWill[i] = m.bool();
        }
        cur = s;
    }

    public static void reset() {
        cur = null;
        lastStored = null;
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
        boolean[] seen = new boolean[301];
        if (s != lastStored) {
            lastStored = s;
            double lead = ZombieSync.lead(now, s.time);
            for (int i = 0; i < s.n; i++) {
                int x = s.slot[i];
                if (x >= 0 && x < LAST && present[x]) {
                    double ex = MyPanel.doucount[0][x] - (s.x[i] + s.vx[i] * lead);
                    double ey = MyPanel.doucount[1][x] - (s.y[i] + s.vy[i] * lead);
                    if (Math.abs(ex) > 300 || Math.abs(ey) > 300) {
                        ex = 0;
                        ey = 0;
                    }
                    errX[x] = (float) ex;
                    errY[x] = (float) ey;
                    prevAng[x] = (float) MyPanel.angcount[x];
                    prevTime[x] = now;
                }
            }
        }
        double lead = ZombieSync.lead(now, s.time);
        for (int i = 0; i < s.n; i++) {
            int x = s.slot[i];
            if (x < 0 || x >= LAST) {
                continue;
            }
            seen[x] = true;
            boolean nowAlive = s.heal[i] > 0;
            if (!present[x] || (nowAlive && !alive[x]) || !Objects.equals(MyPanel.ctcode[x], s.code[i])) {
                spawnSlot(x, s, i, b);
                errX[x] = 0;
                errY[x] = 0;
                prevAng[x] = s.ang[i];
                prevTime[x] = now - 1000;
            }
            present[x] = true;
            // a figure of a cutscene from the host is moved by our own copy of the scene
            boolean scripted = StorySync.scripted(x, 0);
            double f = Math.max(0, Math.min(1, (now - prevTime[x]) / 50.0));
            double keep = Math.max(0, 1 - (now - prevTime[x]) / 100.0);
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
            if (!scripted) {
                MyPanel.doucount[0][x] = px;
                MyPanel.doucount[1][x] = py;
                MyPanel.ctspeed[0][x] = 0;
                MyPanel.ctspeed[1][x] = 0;
                MyPanel.gocounter[0][x] = s.gox[i];
                MyPanel.gocounter[1][x] = s.goy[i];
                MyPanel.count[0][x] = MyPanel.position((int) px, false);
                MyPanel.count[1][x] = MyPanel.position((int) py, true);
                MyPanel.angcount[x] = pa;
                MyPanel.aimcount[x] = pa;
            } else {
                errX[x] = 0;
                errY[x] = 0;
            }
            double oldHeal = MyPanel.ctheal[x];
            MyPanel.ctheal[x] = s.heal[i];
            if (!nowAlive && oldHeal > 0 && b != null && !MyPanel.ctin[x]) {
                try {
                    b.counterdeadoutofscreen(x);
                } catch (Throwable t) {
                    // ignore
                }
            }
            alive[x] = nowAlive;
            MyPanel.Imcount[x] = (byte) s.imcount[i];
            MyPanel.cteamatekind[x] = (byte) s.teamkind[i];
            MyPanel.ctgroup[x] = (byte) s.group[i];
            MyPanel.ctcontrolkind[x] = (byte) s.control[i];
            MyPanel.Humanrespawn.action[x] = s.action[i];
            int fl = s.flags[i];
            MyPanel.ctrun[x] = (fl & 1) != 0;
            MyPanel.ctfall[x] = (fl & 2) != 0;
            MyPanel.ctfallkind[x] = (byte) s.fallkind[i];
            MyPanel.ctsleeping[x] = (fl & 4) != 0;
            MyPanel.cthaveweapon[x] = (fl & 8) != 0;
            MyPanel.ctrunaimfired[x] = (fl & 16) != 0;
            MyPanel.ctsafe[x] = (fl & 32) != 0;
            MyPanel.cthandlereload[x] = (fl & 64) != 0;
            MyPanel.ctopenflashlight[x] = (fl & 128) != 0;
            MyPanel.cthaveflashlight[x] = (fl & 256) != 0;
            MyPanel.ctmove[x] = (fl & 512) != 0;
            MyPanel.Humanrespawn.speak[x] = s.speak[i];
            MyPanel.Humanrespawn.speakloop[x] = (fl & 1024) != 0;
            MyPanel.ctwealth[x] = s.wealth[i];
            MyPanel.ctfireatwill[x] = s.fireAtWill[i];
            MyPanel.cthandlekind[x] = s.hkind[i];
            MyPanel.cthandlename[x] = s.hname[i];
            MyPanel.jumpcountall[x] = s.jump[i];
            MyPanel.ctname[x] = s.name[i];
            MyPanel.ctmission[x] = s.mission[i];
            if (s.fire[i] != lastFire[x]) {
                lastFire[x] = s.fire[i];
                MyPanel.ctfiretime[x] = MyPanel.firetimed;
                MyPanel.ctfired[x] = true;
                if (b != null) {
                    if (b.ctuse[x]) {
                        b.ctusechange[x] = true;
                    } else {
                        b.ctuse[x] = true;
                    }
                }
            }
            if (s.use[i] != lastUseC[x]) {
                lastUseC[x] = s.use[i];
                if (b != null) {
                    if (b.ctuse[x]) {
                        b.ctusechange[x] = true;
                    } else {
                        b.ctuse[x] = true;
                    }
                }
                if (s.hkind[i] == 4) {
                    MyPanel.ctmeleed[x] = true;
                }
            }
        }
        for (int x = 0; x < LAST; x++) {
            if (!seen[x] && (present[x] || MyPanel.ctheal[x] > 0)) {
                hide(x, b);
            }
        }
    }

    private static void spawnSlot(int x, Snap s, int i, Bone b) {
        try {
            MyPanel.Humanrespawn.humanfore(x);
        } catch (Throwable t) {
            Log.error("humanfore", t);
        }
        MyPanel.ctcode[x] = s.code[i];
        if (b != null) {
            b.ctdeath[x] = true;
            b.ctuse[x] = false;
            b.ctcut[x] = -1;
        }
        lastFire[x] = s.fire[i];
        lastUseC[x] = s.use[i];
        alive[x] = s.heal[i] > 0;
    }

    private static void hide(int x, Bone b) {
        present[x] = false;
        alive[x] = false;
        MyPanel.ctheal[x] = 0;
        MyPanel.doucount[0][x] = -100000;
        MyPanel.doucount[1][x] = -100000;
        MyPanel.ctcode[x] = null;
        if (b != null) {
            b.ctdeath[x] = true;
            b.ctuse[x] = false;
        }
    }
}
