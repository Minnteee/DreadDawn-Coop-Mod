package coop.sync;

import Game.Bone;
import Game.Function;
import Game.Humanrespawn;
import Game.MyPanel;
import coop.Coop;
import coop.Log;
import coop.net.Net;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;

/**
 * Host: the game simulates many things only around its own screen (single player). In co-op the area
 * around the guest's screen gets the same treatment, so zombies and people near the guest behave exactly
 * as near the host:
 * - animations that change the game state (zombie dodges, falling down / standing up, attacks, deaths)
 *   only advance while a figure is drawn: figures the guest sees are "drawn" on the host too, invisibly;
 * - "on screen" checks for knock-downs, hit effects, fire damage, pushing apart and slot reuse count
 *   the guest's screen as well;
 * - the AI work area (seeblock), the wandering-NPC area and the rush targets include the guest.
 */
public final class AiAssist {
    private AiAssist() {}

    // ------------------------------------------------------------------ guest view

    /** host + co-op + the guest's figure is in this world on the same map */
    public static boolean active() {
        if (!Net.isHost() || !Puppet.active() || !Coop.playing()) {
            return false;
        }
        PlayerSync.State s = Puppet.latestState();
        return s != null && s.map != null && s.map.equals(MyPanel.select + MyPanel.selectid);
    }

    /** is the point inside the guest's screen (the host's screen box moved to the guest's camera)? */
    public static boolean inGuestView(double x, double y, double z, double bond) {
        PlayerSync.State c = Puppet.latestState();
        if (c == null) {
            return false;
        }
        double left;
        double right;
        double top;
        double bottom;
        if (c.viewR > c.viewL) {
            left = c.camx + c.viewL;
            right = c.camx + c.viewR;
            top = c.camy * MyPanel.flatex + c.viewT;
            bottom = c.camy * MyPanel.flatex + c.viewB;
        } else {
            double[] p = MyPanel.pFAST;
            double dx = c.camx - (-MyPanel.keyxed);
            double dy = (c.camy - (-MyPanel.keyyed)) * MyPanel.flatex;
            left = p[0] + dx;
            right = p[1] + dx;
            top = p[2] + dy;
            bottom = p[3] + dy;
        }
        if (x + bond < left || x - bond > right) {
            return false;
        }
        double fy = Double.isNaN(z) ? MyPanel.Rtgy(y) : MyPanel.Rtgy(y, z);
        return !(fy + bond < top) && !(fy - bond > bottom);
    }

    /** MyPanel.inscreenREAL(x, y, z, bond) or inside the guest's screen */
    public static boolean inscreenCoop4(double x, double y, double z, double bond) {
        if (MyPanel.inscreenREAL(x, y, z, bond)) {
            return true;
        }
        try {
            return active() && inGuestView(x, y, z, bond);
        } catch (Throwable t) {
            return false;
        }
    }

    /** MyPanel.inscreen(x, fy, w, h) (a box in screen-flat coordinates) or the same box in the guest's screen */
    public static boolean inscreenBox(double x, double fy, double w, double h) {
        if (MyPanel.inscreen(x, fy, w, h)) {
            return true;
        }
        try {
            if (!active()) {
                return false;
            }
            PlayerSync.State c = Puppet.latestState();
            if (c == null || !(c.viewR > c.viewL)) {
                return false;
            }
            double left = c.camx + c.viewL;
            double right = c.camx + c.viewR;
            double top = c.camy * MyPanel.flatex + c.viewT;
            double bottom = c.camy * MyPanel.flatex + c.viewB;
            return !(x + w < left) && !(x > right) && !(fy + h < top) && !(fy > bottom);
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ "on screen" flags of both players

    private static volatile boolean[] tinEither = new boolean[1500];
    private static volatile boolean[] ctinEither = new boolean[301];
    private static volatile boolean eitherValid;

    /** replaces MyPanel.tin in selected game methods (host co-op: on the host's or the guest's screen) */
    public static boolean[] tin() {
        return eitherValid ? tinEither : MyPanel.tin;
    }

    public static boolean[] ctin() {
        return eitherValid ? ctinEither : MyPanel.ctin;
    }

    /** loops over people skip the other player's figure (it is driven by the network) */
    public static boolean skipSlot(int x) {
        return x == Puppet.K && Puppet.active();
    }

    // ------------------------------------------------------------------ invisible animation

    private static Canvas dummy;
    private static int dummyFrames;
    private static final double VIEW_BOND = 350;

    /** FX thread, end of every frame (after the game drew its own screen). */
    public static void fxFrame() {
        if (!active() || MyPanel.page != 3 || MyPanel.Bone == null || MyPanel.tin == null) {
            eitherValid = false;
            return;
        }
        int te = Math.min(MyPanel.te, MyPanel.tin.length);
        boolean[] t = tinEither.length >= te ? tinEither : new boolean[te];
        boolean[] c = ctinEither;
        Bone b = MyPanel.Bone;
        int[] ghostZ = new int[te];
        int nz = 0;
        for (int x = 0; x < te; x++) {
            boolean own = MyPanel.tin[x];
            boolean guest = !own && (MyPanel.theal[x] > 0 || !b.tdeath[x])
                    && inGuestView(MyPanel.douterro[0][x], MyPanel.douterro[1][x], Double.NaN, VIEW_BOND);
            t[x] = own || guest;
            if (guest) {
                ghostZ[nz++] = x;
            }
        }
        int[] ghostH = new int[Puppet.K];
        int nh = 0;
        for (int x = 0; x < 301; x++) {
            boolean own = MyPanel.ctin[x];
            boolean guest = !own && x != 300
                    && inGuestView(MyPanel.doucount[0][x], MyPanel.doucount[1][x], Double.NaN, VIEW_BOND);
            c[x] = own || guest;
            if (guest && x < Puppet.K && (MyPanel.ctheal[x] > 0 || !b.ctdeath[x])) {
                ghostH[nh++] = x;
            }
        }
        tinEither = t;
        eitherValid = true;
        if (nz == 0 && nh == 0) {
            return;
        }
        // draw them into an invisible canvas: the animation (and what it changes in the game) advances
        if (dummy == null || ++dummyFrames > 10) {
            dummy = new Canvas(4, 4);
            dummyFrames = 0;
        }
        GraphicsContext real = MyPanel.g;
        GraphicsContext g = dummy.getGraphicsContext2D();
        MyPanel.g = g;
        try {
            for (int i = 0; i < nz; i++) {
                try {
                    b.drawterrorist(ghostZ[i]);
                } catch (Throwable e) {
                    Log.rare("Zombie-Animation " + e);
                }
            }
            for (int i = 0; i < nh; i++) {
                try {
                    b.drawcounter(ghostH[i]);
                } catch (Throwable e) {
                    Log.rare("Figur-Animation " + e);
                }
            }
        } finally {
            MyPanel.g = real;
            try {
                g.setTransform(1, 0, 0, 1, 0, 0);
                g.setGlobalAlpha(1);
                g.clearRect(0, 0, 4, 4); // drops the recorded drawing commands
            } catch (Throwable e) {
                dummy = null;
            }
        }
    }

    // ------------------------------------------------------------------ AI work area, NPC area, rushes

    /** Function.bot / Zombierespawn.unity / Humanrespawn.unity start: the AI area covers both screens. */
    public static void widenSeeblock() {
        if (!active()) {
            return;
        }
        PlayerSync.State s = Puppet.latestState();
        int see = 50;
        int hx = MyPanel.position((int) (-MyPanel.keyxed), false);
        int hy = MyPanel.position((int) (-MyPanel.keyyed), true);
        int cx = MyPanel.position((int) s.camx, false);
        int cy = MyPanel.position((int) s.camy, true);
        int[] sb = MyPanel.seeblock;
        sb[0] = Math.max(0, Math.min(hx, cx) - see);
        sb[1] = Math.min(MyPanel.X, Math.max(hx, cx) + see + 1);
        sb[2] = Math.max(0, Math.min(hy, cy) - see);
        sb[3] = Math.min(MyPanel.Y, Math.max(hy, cy) + see + 1);
    }

    /** Humanrespawn.inrespawnarea inside Humanrespawn.unity: wandering people near the guest stay too. */
    public static boolean inrespawnCoop(Humanrespawn h, double x, double y) {
        if (h.inrespawnarea(x, y)) {
            return true;
        }
        if (!active()) {
            return false;
        }
        PlayerSync.State s = Puppet.latestState();
        return x > s.camx - h.respawnX && x < s.camx + h.respawnX && y > s.camy - h.respawnY && y < s.camy + h.respawnY;
    }

    /** Function.fillmap inside zombielongrun: where the zombies of a rush/mission run to includes the guest. */
    public static int longrunFill(Function f, int roued, int fin, boolean[][][] walk, int kind) {
        try {
            if (active() && MyPanel.ctheal[Puppet.K] > 0) {
                int[][] seeds = f.fingreal;
                int ax = MyPanel.countother[0][300];
                int ay = MyPanel.countother[1][300];
                int bx = MyPanel.countother[0][Puppet.K];
                int by = MyPanel.countother[1][Puppet.K];
                boolean has300 = false;
                boolean has299 = false;
                for (int i = 0; i < fin; i++) {
                    if (seeds[0][i] == ax && seeds[1][i] == ay) {
                        has300 = true;
                    }
                    if (seeds[0][i] == bx && seeds[1][i] == by) {
                        has299 = true;
                    }
                }
                if (has300 && !has299 && fin < seeds[0].length && bx > 0 && by > 0) {
                    seeds[0][fin] = bx;
                    seeds[1][fin] = by;
                    fin++;
                }
            }
        } catch (Throwable t) {
            Log.rare("longrunFill " + t);
        }
        return f.fillmap(roued, fin, walk, kind);
    }
}
