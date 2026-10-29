package coop.sync;

import Game.MyPanel;
import coop.Coop;
import coop.Log;
import coop.net.Msg;
import coop.net.Net;
import coop.net.Proto;

/**
 * Explosions, barrels, boomer gas and traps.
 * - Explosions that happen on both machines anyway (grenades, cutscenes, cars, barrels) stay as they are.
 *   Any other explosion on the host (mines, turrets, air drops, map events ...) is shown to the guest
 *   (visual only); an explosion the guest causes (explosive spear, a turret he uses, shot power box ...)
 *   is made on the host, where it does its damage.
 * - Barrels: hit by the guest -> exploded by the host; the host's explosions are shown on the guest's
 *   machine; barrels the guest puts down are put down on the host.
 * - Boomer gas: the host decides, the guest sees the cloud.
 * - Blade and nail traps hurt only on the host (they ran on both machines = double damage).
 */
public final class HazardSync {
    private HazardSync() {}

    static final int H_EXPLODE_FX = 1;
    static final int H_EXPLODE_REQ = 2;
    static final int H_BOOMER = 3;
    static final int H_BARREL_HIT = 4;
    static final int H_BARREL_BOOM = 5;
    static final int H_BARREL_ADD = 6;

    private static final ThreadLocal<Boolean> applying = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<int[]> shared = ThreadLocal.withInitial(() -> new int[1]);

    /** start of a frame on this thread: a method left by an exception must not keep the mark */
    public static void frameReset() {
        shared.get()[0] = 0;
    }

    /** HEAD/TAIL of methods whose explosions happen on both machines anyway */
    public static void sharedEnter() {
        shared.get()[0]++;
    }

    public static void sharedExit() {
        int[] d = shared.get();
        if (d[0] > 0) {
            d[0]--;
        }
    }

    // ------------------------------------------------------------------ explosions

    /** HEAD MyPanel.explodecreater(IIDDDZ)V */
    public static void explodeHead(int x, int y, double distance, double shock, double damage, boolean counter) {
        if (applying.get() || shared.get()[0] > 0 || !Coop.playing()) {
            return;
        }
        if (Net.isHost()) {
            Net.send(new Msg(Proto.HAZARD, 32).u8(H_EXPLODE_FX).i32(x).i32(y).f32((float) shock));
        } else {
            Net.send(new Msg(Proto.HAZARD, 48).u8(H_EXPLODE_REQ).i32(x).i32(y).f64(distance).f64(shock).f64(damage)
                    .bool(counter));
        }
    }

    private static void explode(int x, int y, double distance, double shock, double damage, boolean counter) {
        applying.set(true);
        try {
            MyPanel.explodecreater(x, y, distance, shock, damage, counter);
        } catch (Throwable t) {
            Log.rare("Explosion " + t);
        } finally {
            applying.set(false);
        }
    }

    /** a blast that only shows (its damage happened on the host) */
    static void fxAt(int x, int y) {
        explode(x, y, 0.0, 800.0, 0.0, false);
    }

    // ------------------------------------------------------------------ boomer gas

    /** HEAD_CANCEL MyPanel.boommer(I)V: true = skip (client: the host's gas comes as an event) */
    public static boolean boomerHead(int x) {
        if (applying.get() || !Coop.playing()) {
            return false;
        }
        if (Net.isHost()) {
            Net.send(new Msg(Proto.HAZARD, 16).u8(H_BOOMER).i32((int) MyPanel.douterro[0][x]).i32((int) MyPanel.douterro[1][x]));
            return false;
        }
        return Coop.clientSimOff();
    }

    private static void clientBoomer(int x, int y) {
        applying.set(true);
        try {
            int before = MyPanel.smokebloodnuming;
            MyPanel.smokebloodadd(x, y);
            if (MyPanel.smokebloodnuming > before) {
                // the cloud only shows; its damage burst happened on the host
                MyPanel.smokebloodtime[MyPanel.smokebloodnuming - 1] = 0.01f;
            }
        } catch (Throwable t) {
            Log.rare("Gaswolke " + t);
        } finally {
            applying.set(false);
        }
    }

    // ------------------------------------------------------------------ barrels

    private static final boolean[] letThrough = new boolean[500];

    /** HEAD MyPanel.barrel()V */
    public static void barrelHead() {
        if (!Coop.playing() || MyPanel.barrelexplode == null) {
            return;
        }
        int n = Math.min(MyPanel.barrelnuming, MyPanel.barrelexplode.length);
        for (int i = 0; i < n; i++) {
            if (!MyPanel.barrelexplode[i]) {
                continue;
            }
            int bx = (int) MyPanel.barrel[0][i];
            int by = (int) MyPanel.barrel[1][i];
            if (Net.isHost()) {
                Net.send(new Msg(Proto.HAZARD, 16).u8(H_BARREL_BOOM).i32(bx).i32(by));
            } else if (i < letThrough.length && letThrough[i]) {
                letThrough[i] = false; // the host's explosion: happens here too
            } else {
                MyPanel.barrelexplode[i] = false; // the guest hit it: the host explodes it
                Net.send(new Msg(Proto.HAZARD, 16).u8(H_BARREL_HIT).i32(bx).i32(by));
            }
        }
    }

    private static int nearestBarrel(int x, int y) {
        int best = -1;
        double bd = 40 * 40;
        for (int i = 0; i < MyPanel.barrelnuming; i++) {
            double dx = MyPanel.barrel[0][i] - x;
            double dy = MyPanel.barrel[1][i] - y;
            double d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    /** HEAD MyPanel.barrelcreater(DDI)Z: a barrel the guest puts down is put down on the host as well */
    public static void barrelCreating(double x, double y, int kind) {
        if (applying.get() || !Net.isClient() || !Coop.playing() || Coop.loading) {
            return;
        }
        Net.send(new Msg(Proto.HAZARD, 32).u8(H_BARREL_ADD).f64(x).f64(y).i32(kind));
    }

    // ------------------------------------------------------------------ traps (host only)

    /** HEAD MyPanel.blade()V: blades turn on both machines but cut only on the host */
    public static void bladeHead(MyPanel self) {
        if (Net.isClient() && Coop.clientSimOff()) {
            self.bladeTIME = 0;
        }
    }

    /** HEAD MyPanel.trapnail()V: nails hurt zombies only on the host */
    public static void nailHead(MyPanel self) {
        if (Net.isClient() && Coop.clientSimOff()) {
            self.trapnailTIME = 0;
        }
    }

    /** REDIRECT of MyPanel.spiterdamage(I)Z in MyPanel.spiter(): acid and burning ground hurt the guest too */
    public static boolean spiterDamage(int x) {
        if (x == Puppet.K && Net.isHost() && Puppet.active() && Coop.playing()) {
            return true;
        }
        return MyPanel.spiterdamage(x);
    }

    // ------------------------------------------------------------------ messages

    public static void handle(Msg m) {
        int kind = m.u8();
        switch (kind) {
            case H_EXPLODE_FX: {
                int x = m.i32();
                int y = m.i32();
                float shock = m.f32();
                if (Net.isClient()) {
                    Coop.runSim(() -> explode(x, y, 0.0, shock, 0.0, false));
                }
                break;
            }
            case H_EXPLODE_REQ: {
                int x = m.i32();
                int y = m.i32();
                double distance = m.f64();
                double shock = m.f64();
                double damage = m.f64();
                boolean counter = m.bool();
                if (Net.isHost()) {
                    Coop.runSim(() -> explode(x, y, distance, shock, damage, counter));
                }
                break;
            }
            case H_BOOMER: {
                int x = m.i32();
                int y = m.i32();
                if (Net.isClient()) {
                    Coop.runSim(() -> clientBoomer(x, y));
                }
                break;
            }
            case H_BARREL_HIT: {
                int x = m.i32();
                int y = m.i32();
                if (Net.isHost()) {
                    Coop.runSim(() -> {
                        int i = nearestBarrel(x, y);
                        if (i >= 0) {
                            MyPanel.barrelexplode[i] = true;
                        }
                    });
                }
                break;
            }
            case H_BARREL_BOOM: {
                int x = m.i32();
                int y = m.i32();
                if (Net.isClient()) {
                    Coop.runSim(() -> {
                        int i = nearestBarrel(x, y);
                        if (i >= 0 && i < letThrough.length) {
                            letThrough[i] = true;
                            MyPanel.barrelexplode[i] = true;
                        } else {
                            explode(x, y, 0.0, 800.0, 0.0, false); // barrel not here (yet): show the blast
                        }
                    });
                }
                break;
            }
            case H_BARREL_ADD: {
                double x = m.f64();
                double y = m.f64();
                int k = m.i32();
                if (Net.isHost()) {
                    Coop.runSim(() -> {
                        applying.set(true);
                        try {
                            MyPanel.barrelcreater(x, y, k);
                        } finally {
                            applying.set(false);
                        }
                    });
                }
                break;
            }
            default:
                break;
        }
    }
}
