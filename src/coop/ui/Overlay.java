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
        coop.Log.info("Meldung: " + text);
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
        double z = (dead ? 60 : 215) + MyPanel.jumpcountall[x] + (MyPanel.ctskateing[x] ? 15 : 0);
        double sx = MyPanel.Rtsx(MyPanel.doucount[0][x]);
        double sy = MyPanel.Rtsy(MyPanel.doucount[1][x], z);
        double size = Math.max(11, MyPanel.realsize(26));
        g.save();
        g.setTextAlign(TextAlignment.CENTER);
        g.setFont(font(size));
        String text = dead ? name + " (tot)" : name;
        g.setFill(Color.color(0, 0, 0, 0.75));
        g.fillText(text, sx + size * 0.08, sy + size * 0.08);
        g.setFill(dead ? Color.color(0.7, 0.3, 0.3) : color);
        g.fillText(text, sx, sy);
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

    /** FX thread, end of every frame: status line and messages. */
    public static void drawTail() {
        GraphicsContext g = MyPanel.g;
        if (g == null) {
            return;
        }
        double fs = Math.max(11, MyPanel.fovyscreen * 0.02);
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
            g.fillText("Warte auf den Host ...", MyPanel.fovxscreen / 2.0, MyPanel.fovyscreen * 0.85);
        }
        g.restore();
    }

    private static String statusLine() {
        if (!Net.active()) {
            return null;
        }
        if (MyPanel.page == 3 && Net.connected() && Coop.playing()) {
            String s = "Coop: " + Net.peerName + (Net.rttMs >= 0 ? "  (" + Net.rttMs + " ms)" : "");
            if (DeathSync.spectating()) {
                s += "   - Zuschauermodus";
            }
            return s;
        }
        String s = Net.status;
        if (Net.isClient() && WorldTransfer.progress > 0 && WorldTransfer.progress < 100 && s.startsWith("Empfange")) {
            s += " " + WorldTransfer.progress + "%";
        }
        return s.isEmpty() ? null : "Coop: " + s;
    }
}
