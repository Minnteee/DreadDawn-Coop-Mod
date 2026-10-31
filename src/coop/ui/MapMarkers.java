package coop.ui;

import Game.MapView;
import Game.MyPanel;
import coop.Coop;
import coop.sync.DeathSync;
import coop.sync.PlayerSync;
import coop.sync.Puppet;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.TextAlignment;

/** The other player as a yellow dot on the minimap (radar) and on the big map. */
public final class MapMarkers {
    private MapMarkers() {}

    private static boolean show() {
        return Puppet.active() && Coop.playing() && !DeathSync.partnerGone();
    }

    /** MyPanel.rad: the game would add the other player as a green NPC dot; we draw him ourselves */
    public static boolean skipRadar(double x, double y) {
        return Puppet.active() && x == MyPanel.doucount[0][Puppet.K] && y == MyPanel.doucount[1][Puppet.K];
    }

    /** blinks while the partner is down */
    private static Color dotColor() {
        if (DeathSync.partnerDown() && (System.currentTimeMillis() / 300) % 2 == 0) {
            return Color.color(0.9, 0.15, 0.1);
        }
        return Overlay.PARTNER;
    }

    /** page3, right after the radar picture was drawn (once per frame) */
    public static void radar() {
        MyPanel p = Coop.panel;
        if (p == null || p.radtime == 0 || !show()) {
            return;
        }
        int k = Puppet.K;
        GraphicsContext g = MyPanel.g;
        double s = MyPanel.sizexscreen;
        double cx = MyPanel.fovxscreen - MyPanel.rrad / s;
        double cy = MyPanel.rrad / s;
        double ax = MyPanel.doucount[0][k] + MyPanel.keyx;
        double ay = MyPanel.doucount[1][k] + MyPanel.keyy;
        double d = Math.sqrt(ax * ax + ay * ay);
        // like the game's companions: beyond the radar range the dot stays on the rim
        double f = (d > p.radsize ? p.rradscreen / Math.max(1e-6, d) : p.rradscreen / p.radsize) / s;
        double x = cx + ax * f;
        double y = cy + ay * f;
        double r = 5.0 / s;
        g.save();
        g.setFill(Color.color(0, 0, 0, 0.85));
        g.fillOval(x - r - 1.5 / s, y - r - 1.5 / s, 2 * r + 3 / s, 2 * r + 3 / s);
        g.setFill(dotColor());
        g.fillOval(x - r, y - r, 2 * r, 2 * r);
        g.restore();
    }

    /** MapView.smallmap / mapview, before the mission buttons: the partner on the big map */
    public static void bigMap(MapView mv) {
        if (!show()) {
            return;
        }
        PlayerSync.State st = Puppet.latestState();
        if (st == null) {
            return;
        }
        int k = Puppet.K;
        double s = MyPanel.sizexscreen;
        double x;
        double y;
        String here = MyPanel.select + MyPanel.selectid;
        if (mv.openmapkind == 2) {
            // area map of the current map
            if (st.map != null && !st.map.equals(here)) {
                return;
            }
            x = mv.rtmx(MyPanel.doucount[0][k]);
            y = mv.rtmy(MyPanel.doucount[1][k]);
        } else {
            // world map: next to the node of the partner's map
            int n = -1;
            for (int i = 0; i < mv.mapnum; i++) {
                if (mv.mapname[i] != null && (mv.mapname[i] + mv.mapid[i]).equals(st.map)) {
                    n = i;
                    break;
                }
            }
            if (n < 0) {
                n = mv.mapnownum;
            }
            if (n < 0 || n >= mv.mapnum) {
                return;
            }
            x = mv.map[0][n] + mv.mapnowsize * 0.9;
            y = mv.map[1][n] - mv.mapnowsize * 0.6;
        }
        GraphicsContext g = MyPanel.g;
        double r = 9.0 / s;
        g.save();
        g.setFill(Color.color(0, 0, 0, 0.85));
        g.fillOval(x - r - 2.5 / s, y - r - 2.5 / s, 2 * r + 5 / s, 2 * r + 5 / s);
        g.setFill(dotColor());
        g.fillOval(x - r, y - r, 2 * r, 2 * r);
        g.setTextAlign(TextAlignment.CENTER);
        g.setFont(javafx.scene.text.Font.font(MyPanel.languageFONT[MyPanel.language], javafx.scene.text.FontWeight.BOLD,
                Math.max(11, 22.0 / s)));
        g.setFill(Color.color(0, 0, 0, 0.8));
        g.fillText(Puppet.name, x + 2 / s, y - r - 6 / s + 2 / s);
        g.setFill(Overlay.PARTNER);
        g.fillText(Puppet.name, x, y - r - 6 / s);
        g.restore();
    }
}
