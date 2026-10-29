package coop.sync;

import Game.MyPanel;
import coop.Coop;
import coop.Log;
import coop.net.Msg;
import coop.net.Net;
import coop.net.Proto;

/**
 * Thrown and fired things that fly through the world: grenades (incl. stones, decoys, smoke),
 * molotov / acid blobs ("spiter") and flamethrower flames. When one is created on one machine,
 * the same one is created on the other machine. The host's copy decides about damage.
 */
public final class Projectiles {
    private Projectiles() {}

    static final int GRENADE = 1;
    static final int SPITER = 2;
    static final int FLAME = 3;

    private static int lastGren;
    private static int lastSpiter;
    private static int lastFlam;

    /** start of a function that may create projectiles: remember the current counts */
    public static void mark() {
        lastGren = MyPanel.gren;
        lastSpiter = MyPanel.spiternuming;
        lastFlam = MyPanel.flam;
    }

    /** right after a projectile counter was written */
    public static void created() {
        if (!Coop.playing()) {
            mark();
            return;
        }
        try {
            if (MyPanel.gren > lastGren) {
                for (int i = lastGren; i < MyPanel.gren; i++) {
                    sendGrenade(i);
                }
            }
            if (MyPanel.spiternuming > lastSpiter) {
                for (int i = lastSpiter; i < MyPanel.spiternuming; i++) {
                    sendSpiter(i);
                }
            }
            if (MyPanel.flam > lastFlam) {
                for (int i = lastFlam; i < MyPanel.flam; i++) {
                    sendFlame(i);
                }
            }
        } catch (Throwable t) {
            Log.error("Projectiles.created", t);
        }
        mark();
    }

    private static void sendGrenade(int i) {
        Msg m = new Msg(Proto.GRENADE, 96).u8(GRENADE);
        m.f64(MyPanel.grenade[0][i]).f64(MyPanel.grenade[1][i]).f64(MyPanel.grenade[2][i]);
        m.f64(MyPanel.anggren[0][i]).f64(MyPanel.anggren[1][i]).f64(MyPanel.anggren[2][i]);
        m.f32(MyPanel.grenadetime[i]).f32(MyPanel.grenadewhile[i]).f64(MyPanel.angrotation[i]).u8(MyPanel.grenkind[i]);
        Net.send(m);
    }

    private static void sendSpiter(int i) {
        Msg m = new Msg(Proto.GRENADE, 96).u8(SPITER);
        m.f64(MyPanel.spiter[0][i]).f64(MyPanel.spiter[1][i]).f64(MyPanel.spiter[2][i]);
        m.f64(MyPanel.angspite[0][i]).f64(MyPanel.angspite[1][i]).f64(MyPanel.angspite[2][i]);
        m.f32(MyPanel.spitertime[i]).f32(MyPanel.spitertimed[i]).f64(MyPanel.spiterangrotation[i]).u8(MyPanel.spiterkind[i]);
        m.u8(MyPanel.spiternum[i]);
        Net.send(m);
    }

    private static void sendFlame(int i) {
        Msg m = new Msg(Proto.GRENADE, 96).u8(FLAME);
        m.f64(MyPanel.flame[0][i]).f64(MyPanel.flame[1][i]).f64(MyPanel.flame[2][i]);
        m.f64(MyPanel.angflam[0][i]).f64(MyPanel.angflam[1][i]).f64(MyPanel.angflam[2][i]);
        m.f32(MyPanel.flametime[i]).f32(0f).f64(MyPanel.flangrotation[i]).u8(MyPanel.flamekind[i]);
        m.bool(MyPanel.flamlight[i]);
        Net.send(m);
    }

    public static void handle(Msg m) {
        int type = m.u8();
        double x = m.f64();
        double y = m.f64();
        double z = m.f64();
        double vx = m.f64();
        double vy = m.f64();
        double vz = m.f64();
        float time = m.f32();
        float time2 = m.f32();
        double rot = m.f64();
        byte kind = m.s8();
        int extra = m.remaining() > 0 ? m.u8() : 0;
        Coop.runSim(() -> spawn(type, x, y, z, vx, vy, vz, time, time2, rot, kind, extra));
    }

    private static void spawn(int type, double x, double y, double z, double vx, double vy, double vz, float time,
            float time2, double rot, byte kind, int extra) {
        if (!Coop.inGame()) {
            return;
        }
        try {
            switch (type) {
                case GRENADE: {
                    int i = MyPanel.gren;
                    if (i >= MyPanel.grenade[0].length) {
                        return;
                    }
                    MyPanel.grenade[0][i] = MyPanel.lastgren[0][i] = x;
                    MyPanel.grenade[1][i] = MyPanel.lastgren[1][i] = y;
                    MyPanel.grenade[2][i] = z;
                    MyPanel.anggren[0][i] = vx;
                    MyPanel.anggren[1][i] = vy;
                    MyPanel.anggren[2][i] = vz;
                    MyPanel.grenadetime[i] = time;
                    MyPanel.grenadewhile[i] = time2;
                    MyPanel.angrotation[i] = rot;
                    MyPanel.grenkind[i] = kind;
                    MyPanel.gren = i + 1;
                    break;
                }
                case SPITER: {
                    int i = MyPanel.spiternuming;
                    if (i >= MyPanel.spiter[0].length) {
                        return;
                    }
                    MyPanel.spiter[0][i] = MyPanel.lastspiter[0][i] = x;
                    MyPanel.spiter[1][i] = MyPanel.lastspiter[1][i] = y;
                    MyPanel.spiter[2][i] = z;
                    MyPanel.angspite[0][i] = vx;
                    MyPanel.angspite[1][i] = vy;
                    MyPanel.angspite[2][i] = vz;
                    MyPanel.spitertime[i] = time;
                    MyPanel.spitertimed[i] = time2;
                    MyPanel.spiterangrotation[i] = rot;
                    MyPanel.spiterkind[i] = kind;
                    MyPanel.spiternum[i] = (byte) extra;
                    MyPanel.spiternuming = i + 1;
                    break;
                }
                case FLAME: {
                    int i = MyPanel.flam;
                    if (i >= MyPanel.flame[0].length) {
                        return;
                    }
                    MyPanel.flame[0][i] = MyPanel.lastflam[0][i] = x;
                    MyPanel.flame[1][i] = MyPanel.lastflam[1][i] = y;
                    MyPanel.flame[2][i] = z;
                    MyPanel.angflam[0][i] = vx;
                    MyPanel.angflam[1][i] = vy;
                    MyPanel.angflam[2][i] = vz;
                    MyPanel.flametime[i] = time;
                    MyPanel.flangrotation[i] = rot;
                    MyPanel.flamekind[i] = kind;
                    MyPanel.flamlight[i] = extra != 0;
                    MyPanel.flam = i + 1;
                    break;
                }
                default:
                    break;
            }
        } catch (Throwable t) {
            Log.error("Projectiles.spawn", t);
        }
    }
}
