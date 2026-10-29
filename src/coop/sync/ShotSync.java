package coop.sync;

import Game.MyPanel;
import coop.Coop;
import coop.Log;
import coop.net.Msg;
import coop.net.Net;
import coop.net.Proto;
import java.util.ArrayList;
import java.util.List;

/**
 * Bullets (tracer lines) of the other machine: every bullet a figure fires there flies here too as a
 * "ghost bullet". It looks and stops like a real one (walls, zombies, bandits, blood) but never does
 * damage: the damage is decided where the shot really happened.
 * Host: sends the shots of its own player, of the NPCs and of a turret it uses; client: its own player
 * and a turret it uses.
 */
public final class ShotSync {
    private ShotSync() {}

    /** shooter id of ghost bullets (the game uses 300 = player, 0..299 = people, -1/-2 = turrets) */
    public static final int GHOST = -3;

    private static final List<float[]> pending = new ArrayList<>();
    private static final ThreadLocal<Boolean> applying = ThreadLocal.withInitial(() -> false);

    /** Hook at the start of MyPanel.shootcreater: remember the shot for the other machine. */
    public static void onShot(double x, double y, double vx, double vy, double ang, byte kind, int shooter) {
        if (applying.get() || shooter == GHOST || !Coop.playing()) {
            return;
        }
        boolean send;
        if (Net.isHost()) {
            send = shooter == 300 || shooter == -2 || (shooter >= 0 && shooter < Puppet.K) || (shooter == -1 && fromTurret(x, y));
        } else {
            send = shooter == 300 || shooter == -2;
        }
        if (!send) {
            return;
        }
        float ox;
        float oy;
        if (shooter >= 0) {
            // relative to the shooter's body: the other machine starts the line at the figure it shows
            ox = (float) (x - MyPanel.doucount[0][shooter]);
            oy = (float) (y - MyPanel.doucount[1][shooter]);
        } else {
            ox = (float) x;
            oy = (float) y;
        }
        synchronized (pending) {
            if (pending.size() < 120) {
                pending.add(new float[] {shooter, ox, oy, (float) vx, (float) vy, (float) ang, kind});
            }
        }
    }

    private static boolean fromTurret(double x, double y) {
        try {
            for (int t = 0; t < MyPanel.macgunuming; t++) {
                if (Math.abs(MyPanel.macgun[0][t] - x) < 1 && Math.abs(MyPanel.macgun[1][t] - y) < 1) {
                    return true;
                }
            }
        } catch (Throwable e) {
            return false;
        }
        return false;
    }

    /** Worker, end of the frame: one message with all shots of this frame. */
    public static void flush() {
        List<float[]> out;
        synchronized (pending) {
            if (pending.isEmpty()) {
                return;
            }
            out = new ArrayList<>(pending);
            pending.clear();
        }
        Msg m = new Msg(Proto.SHOT, 16 + out.size() * 26);
        m.u8(out.size());
        for (float[] s : out) {
            m.i16((int) s[0]).f32(s[1]).f32(s[2]).f32(s[3]).f32(s[4]).f32(s[5]).u8((int) s[6]);
        }
        Net.send(m);
    }

    public static void handle(Msg m) {
        int n = m.u8();
        float[][] shots = new float[n][];
        for (int i = 0; i < n; i++) {
            shots[i] = new float[] {m.i16(), m.f32(), m.f32(), m.f32(), m.f32(), m.f32(), m.u8()};
        }
        Coop.runSim(() -> {
            if (!Coop.playing() || Coop.panel == null) {
                return;
            }
            for (float[] s : shots) {
                int shooter = (int) s[0];
                double x;
                double y;
                if (shooter >= 0) {
                    // the other player is our puppet; NPC slots are the same on both machines
                    int here = shooter == 300 ? Puppet.K : shooter;
                    if (here == Puppet.K && !Puppet.active()) {
                        continue;
                    }
                    if (MyPanel.doucount[0][here] < -50000) {
                        continue;
                    }
                    x = MyPanel.doucount[0][here] + s[1];
                    y = MyPanel.doucount[1][here] + s[2];
                } else {
                    x = s[1];
                    y = s[2];
                }
                applying.set(true);
                try {
                    Coop.panel.shootcreater(x, y, s[3], s[4], s[5], (byte) s[6], 0, GHOST);
                } catch (Throwable t) {
                    Log.rare("Geist-Kugel " + t);
                } finally {
                    applying.set(false);
                }
            }
        });
    }

    /**
     * MyPanel.shoot(), right before the hit is handled (switch over the hit kind): ghost bullets only
     * show blood and stop (returns -1 = no hit handling, the bullet stops as after a hit).
     */
    public static int ghostHit(int hitkind, int x, int victim) {
        if (x < 0 || x >= MyPanel.shootplayerid.length || MyPanel.shootplayerid[x] != GHOST) {
            return hitkind;
        }
        try {
            double px;
            double py;
            if (hitkind == 0) {
                px = MyPanel.douterro[0][victim];
                py = MyPanel.douterro[1][victim];
            } else if (hitkind == 1) {
                px = MyPanel.doucount[0][victim];
                py = MyPanel.doucount[1][victim];
            } else {
                return -1;
            }
            double sx = MyPanel.shootspeed[0][x];
            double sy = MyPanel.shootspeed[1][x];
            double len = Math.sqrt(sx * sx + sy * sy);
            if (len < 1e-6) {
                len = 1e-6;
            }
            for (int i = 0; i < 5; i++) {
                double sp = 20 + Math.random() * 60;
                double ax = sx / len + (Math.random() - 0.5) * 0.6;
                double ay = sy / len + (Math.random() - 0.5) * 0.6;
                MyPanel.objectcreater(px, py, MyPanel.shootall, ax * sp, ay * sp, Math.random() * 4, 3,
                        0.2 + Math.random() * 0.5, 300 + Math.random() * 80, (Math.random() - 0.5), true);
            }
        } catch (Throwable t) {
            Log.rare("Geist-Treffer " + t);
        }
        return -1;
    }
}
