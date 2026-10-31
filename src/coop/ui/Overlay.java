package coop.ui;

import Game.MyPanel;
import coop.Config;
import coop.Coop;
import coop.net.Net;
import coop.sync.DeathSync;
import coop.sync.Puppet;
import coop.sync.WorldTransfer;
import java.util.ArrayDeque;
import java.util.Deque;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import coop.T;

/** Everything the mod draws: name tags, status line, short messages. */
public final class Overlay {
    private Overlay() {}

    private static final class Note {
        final String text;
        final long until;

        Note(String text, long until) {
            this.text = text;
            this.until = until;
        }
    }

    private static final Deque<Note> notes = new ArrayDeque<>();

    public static void message(String text) {
        synchronized (notes) {
            notes.addLast(new Note(text, System.currentTimeMillis() + 6000));
            while (notes.size() > 4) {
                notes.removeFirst();
            }
        }
        coop.Log.info("message: " + text);
    }

    private static Font font(double size) {
        return Font.font(MyPanel.languageFONT[MyPanel.language], FontWeight.BOLD, size);
    }

    /** FX thread, after the world: name tags over the players' heads. */
    public static void drawWorld() {
        if (!Net.connected() || MyPanel.Animation == null || MyPanel.Animation.play) {
            return;
        }
        GraphicsContext g = MyPanel.g;
        if (Puppet.active()) {
            drawTag(g, Puppet.K, Puppet.name, DeathSync.spectating() ? Color.color(1.0, 0.85, 0.4) : Color.color(0.85, 0.95, 1.0));
        }
        if (Config.showOwnName && MyPanel.ctheal[300] > 0) {
            drawTag(g, 300, Config.name, Color.color(0.8, 0.8, 0.8));
        }
    }

    private static void drawTag(GraphicsContext g, int x, String name, Color color) {
        if (name == null || name.isEmpty() || !MyPanel.ctin[x]) {
            return;
        }
        boolean dead = MyPanel.ctheal[x] <= 0;
        boolean downP = x == Puppet.K && DeathSync.partnerDown();
        double z = (dead ? 60 : 215) + MyPanel.jumpcountall[x] + (MyPanel.ctskateing[x] ? 15 : 0);
        double sx = MyPanel.Rtsx(MyPanel.doucount[0][x]);
        double sy = MyPanel.Rtsy(MyPanel.doucount[1][x], z);
        double size = Math.max(11, MyPanel.realsize(26));
        g.save();
        g.setTextAlign(TextAlignment.CENTER);
        g.setFont(font(size));
        long left = downP ? DeathSync.partnerDownLeftMs() : 0;
        String text = downP ? name + "  " + (left + 999) / 1000 + " s" : (dead ? name + T.s(" (dead)") : name);
        boolean blink = downP && (System.currentTimeMillis() / 400) % 2 == 0;
        g.setFill(Color.color(0, 0, 0, 0.75));
        g.fillText(text, sx + size * 0.08, sy + size * 0.08);
        g.setFill(downP ? (blink ? Color.color(1.0, 0.35, 0.2) : PARTNER) : (dead ? Color.color(0.7, 0.3, 0.3) : color));
        g.fillText(text, sx, sy);
        if (downP) {
            // countdown bar and the revive prompt at the body
            double w = size * 4;
            double h = Math.max(4, size * 0.25);
            double f = Math.max(0, Math.min(1, left / (double) DeathSync.DOWN_MS));
            g.setFill(Color.color(0, 0, 0, 0.65));
            g.fillRect(sx - w / 2 - 1, sy + size * 0.3 - 1, w + 2, h + 2);
            g.setFill(Color.color(0.95, 0.3 + 0.55 * f, 0.1));
            g.fillRect(sx - w / 2, sy + size * 0.3, w * f, h);
            if (!DeathSync.localDead() && DeathSync.partnerInReviveRange()) {
                String prompt = T.s("[E] Revive");
                double py = sy + size * 0.3 + h + size * 1.1;
                g.setFill(Color.color(0, 0, 0, 0.8));
                g.fillText(prompt, sx + size * 0.08, py + size * 0.08);
                g.setFill(Color.color(1, 1, 1));
                g.fillText(prompt, sx, py);
            }
        }
        if (!dead) {
            // small health bar
            double w = size * 3.2;
            double h = Math.max(3, size * 0.18);
            double hp = Math.max(0, Math.min(1, MyPanel.ctheal[x] / 100.0));
            g.setFill(Color.color(0, 0, 0, 0.6));
            g.fillRect(sx - w / 2, sy + size * 0.25, w, h);
            g.setFill(Color.color(0.75, 0.15, 0.15, 0.9));
            g.fillRect(sx - w / 2, sy + size * 0.25, w * hp, h);
        }
        g.restore();
    }

    /** yellow: the colour of the other player on the minimap, the map and the edge arrow */
    public static final Color PARTNER = Color.color(1.0, 0.85, 0.1);

    /**
     * The other player is outside the screen: an arrow at the screen edge points to him, with his name
     * and the distance next to it.
     */
    static void drawEdgeArrow(GraphicsContext g, double fs) {
        if (!Puppet.active() || !Coop.playing() || MyPanel.page != 3 || MyPanel.Animation == null || MyPanel.Animation.play
                || MyPanel.packnum == 2 || DeathSync.spectating() || DeathSync.partnerGone()) {
            return;
        }
        int k = Puppet.K;
        double s = MyPanel.sizexscreen;
        double w = MyPanel.fovxscreen;
        double h = MyPanel.fovyscreen;
        double tx0 = MyPanel.Rtsx(MyPanel.doucount[0][k]);
        double ty0 = MyPanel.Rtsy(MyPanel.doucount[1][k], 100);
        if (tx0 > -20 / s && tx0 < w + 20 / s && ty0 > -20 / s && ty0 < h + 20 / s) {
            return;
        }
        // keep clear of the minimap (top right), the hotbar (bottom) and the stats (bottom left)
        double side = 46 / s;
        double top = 0.08 * h;
        double bottom = 0.86 * h;
        double cx = w / 2.0;
        double cy = h / 2.0;
        double dx = tx0 - cx;
        double dy = ty0 - cy;
        double f = Math.min(dx > 0 ? (w - side - cx) / dx : dx < 0 ? (side - cx) / dx : 1e9,
                dy > 0 ? (bottom - cy) / dy : dy < 0 ? (top - cy) / dy : 1e9);
        double ax = cx + dx * f;
        double ay = cy + dy * f;
        double radarW = 2 * MyPanel.rrad / s + side;
        double radarH = 2 * MyPanel.rrad / s + 130 / s;
        if (ax > w - radarW && ay < radarH) {
            if (Math.abs(dx) > Math.abs(dy)) {
                ay = radarH;
            } else {
                ax = w - radarW;
            }
        }
        double ang = Math.atan2(dy, dx);
        double len = 22 / s;
        double half = 13 / s;
        boolean blink = DeathSync.partnerDown() && (System.currentTimeMillis() / 300) % 2 == 0;
        g.save();
        g.translate(ax, ay);
        g.rotate(Math.toDegrees(ang));
        g.setFill(Color.color(0, 0, 0, 0.6));
        g.fillPolygon(new double[] {len + 3 / s, -0.7 * len, -0.7 * len}, new double[] {0, half + 3 / s, -half - 3 / s}, 3);
        g.setFill(blink ? Color.color(0.9, 0.15, 0.1) : PARTNER);
        g.fillPolygon(new double[] {len, -0.5 * len, -0.5 * len}, new double[] {0, half, -half}, 3);
        g.restore();
        // name + distance on the inner side of the arrow
        double dist = Math.hypot(MyPanel.doucount[0][k] - MyPanel.doucount[0][300], MyPanel.doucount[1][k] - MyPanel.doucount[1][300]);
        String text = Puppet.name + "  " + Math.round(dist / 50.0) + " m";
        double c = Math.cos(ang);
        double lx = ax - c * 34 / s;
        double ly = ay - Math.sin(ang) * 34 / s;
        g.save();
        g.setFont(font(fs));
        g.setTextBaseline(javafx.geometry.VPos.CENTER);
        g.setTextAlign(c > 0.5 ? TextAlignment.RIGHT : c < -0.5 ? TextAlignment.LEFT : TextAlignment.CENTER);
        g.setFill(Color.color(0, 0, 0, 0.75));
        g.fillText(text, lx + 2 / s, ly + 2 / s);
        g.setFill(PARTNER);
        g.fillText(text, lx, ly);
        g.restore();
    }

    /** FX thread, end of every frame: status line and messages. */
    public static void drawTail() {
        GraphicsContext g = MyPanel.g;
        if (g == null) {
            return;
        }
        double fs = Math.max(11, MyPanel.fovyscreen * 0.02);
        try {
            drawEdgeArrow(g, fs);
        } catch (Throwable t) {
            coop.Log.rare("edge arrow " + t);
        }
        g.save();
        g.setTextAlign(TextAlignment.LEFT);
        g.setFont(font(fs));
        String status = statusLine();
        if (status != null) {
            double x = MyPanel.fovxscreen * 0.01;
            double y = MyPanel.page == 3 ? MyPanel.fovyscreen * 0.035 : MyPanel.fovyscreen * 0.05;
            g.setFill(Color.color(0, 0, 0, 0.7));
            g.fillText(status, x + 2, y + 2);
            g.setFill(Color.color(0.85, 0.85, 0.85, 0.95));
            g.fillText(status, x, y);
        }
        long now = System.currentTimeMillis();
        synchronized (notes) {
            notes.removeIf(n -> n.until < now);
            double y = MyPanel.fovyscreen * 0.12;
            g.setTextAlign(TextAlignment.CENTER);
            g.setFont(font(fs * 1.1));
            for (Note n : notes) {
                double a = Math.min(1, (n.until - now) / 800.0);
                g.setFill(Color.color(0, 0, 0, 0.7 * a));
                g.fillText(n.text, MyPanel.fovxscreen / 2.0 + 2, y + 2);
                g.setFill(Color.color(1.0, 0.92, 0.7, a));
                g.fillText(n.text, MyPanel.fovxscreen / 2.0, y);
                y += fs * 1.5;
            }
        }
        if (DeathSync.localDown() && MyPanel.page == 3) {
            // the own player is down: who can help, and how long is left
            long left = DeathSync.downLeftMs();
            double cy = MyPanel.fovyscreen * 0.74;
            g.setTextAlign(TextAlignment.CENTER);
            g.setFont(font(fs * 1.3));
            String a = T.s("You are down! {0} can revive you.", Puppet.name);
            String b = (left + 999) / 1000 + " s";
            g.setFill(Color.color(0, 0, 0, 0.8));
            g.fillText(a, MyPanel.fovxscreen / 2.0 + 2, cy + 2);
            g.fillText(b, MyPanel.fovxscreen / 2.0 + 2, cy + fs * 1.6 + 2);
            g.setFill(Color.color(1.0, 0.45, 0.3));
            g.fillText(a, MyPanel.fovxscreen / 2.0, cy);
            g.setFill(Color.color(1.0, 0.9, 0.8));
            g.fillText(b, MyPanel.fovxscreen / 2.0, cy + fs * 1.6);
            double w = MyPanel.fovxscreen * 0.22;
            double f = Math.max(0, Math.min(1, left / (double) DeathSync.DOWN_MS));
            double by = cy + fs * 2.2;
            g.setFill(Color.color(0, 0, 0, 0.65));
            g.fillRect(MyPanel.fovxscreen / 2.0 - w / 2 - 2, by - 2, w + 4, fs * 0.5 + 4);
            g.setFill(Color.color(0.95, 0.3 + 0.55 * f, 0.1));
            g.fillRect(MyPanel.fovxscreen / 2.0 - w / 2, by, w * f, fs * 0.5);
        }
        String vote = coop.sync.StorySync.voteHint();
        if (vote != null && MyPanel.page == 3) {
            g.setTextAlign(TextAlignment.CENTER);
            g.setFont(font(fs * 1.1));
            g.setFill(Color.color(0, 0, 0, 0.75));
            g.fillText(vote, MyPanel.fovxscreen / 2.0 + 2, MyPanel.fovyscreen * 0.97 + 2);
            g.setFill(Color.color(1.0, 0.92, 0.7, 0.95));
            g.fillText(vote, MyPanel.fovxscreen / 2.0, MyPanel.fovyscreen * 0.97);
        }
        if (Net.isClient() && Coop.clientInWorld && DeathSync.gameOver() && MyPanel.page == 3) {
            g.setTextAlign(TextAlignment.CENTER);
            g.setFont(font(fs * 1.2));
            g.setFill(Color.color(0.9, 0.9, 0.9, 0.9));
            g.fillText(T.s("Waiting for the host ..."), MyPanel.fovxscreen / 2.0, MyPanel.fovyscreen * 0.85);
        }
        g.restore();
    }

    private static String statusLine() {
        if (!Net.active()) {
            return null;
        }
        if (MyPanel.page == 3 && Net.connected() && Coop.playing()) {
            String s = T.s("Co-op: ") + Net.peerName + (Net.rttMs >= 0 ? "  (" + Net.rttMs + " ms)" : "");
            if (DeathSync.spectating()) {
                s += T.s("   - spectating");
            }
            return s;
        }
        String s = Net.status;
        if (Net.isClient() && WorldTransfer.progress > 0 && WorldTransfer.progress < 100 && s.equals(T.s("Receiving the host's world ..."))) {
            s += " " + WorldTransfer.progress + "%";
        }
        return s.isEmpty() ? null : T.s("Co-op: ") + s;
    }
}
