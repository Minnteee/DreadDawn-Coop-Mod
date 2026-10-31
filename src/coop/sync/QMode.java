package coop.sync;

import Game.Function;
import Game.MyPanel;

/**
 * Command mode (Q) and build mode for the guest. On the client the game's AI (Function.bot) is off,
 * because the host simulates zombies and NPCs. In command mode the own figure is walked by that AI
 * (to the hold point, to the build site), so without it the figure walked to an old target and building
 * failed ("no builder near"). This is the part of Function.bot that steers slot 300, run on the client.
 */
public final class QMode {
    private QMode() {}

    private static final int X = 300;
    private static final int[] out = new int[2];

    /** client worker, instead of Function.bot */
    public static void clientTick(Function f) {
        int x = X;
        if (MyPanel.ctheal[x] <= 0 || MyPanel.position == null) {
            return;
        }
        // the own grid cell (used when entering command mode and by the path search)
        if (MyPanel.position.findposition((int) MyPanel.doucount[0][x], (int) MyPanel.doucount[1][x], out)) {
            MyPanel.count[0][x] = out[0];
            MyPanel.count[1][x] = out[1];
            MyPanel.position.findpositionstupid((int) MyPanel.doucount[0][x], (int) MyPanel.doucount[1][x], out);
            MyPanel.countother[0][x] = out[0];
            MyPanel.countother[1][x] = out[1];
        }
        if (MyPanel.addplayer != 1 || MyPanel.ctsleeping[x] || MyPanel.ctfall[x] || DeathSync.localDead()) {
            return;
        }
        boolean touched = f.cttouch[x];
        f.cttouch[x] = MyPanel.getdistance2(MyPanel.gocounter[0][x], MyPanel.gocounter[1][x], MyPanel.doucount[0][x],
                MyPanel.doucount[1][x]) < f.reachdistance;
        if (f.ctbotime[x] > f.botsmalltimed && touched && f.cttouch[x]) {
            f.ctbotime[x] = f.botsmalltimed;
        }
        if (!(f.ctbotime[x] <= 0 || !touched && f.cttouch[x])) {
            f.ctbotime[x] = (float) (f.ctbotime[x] - MyPanel.timespeedex);
            return;
        }
        f.ctbotime[x] = f.botimed;
        byte c = MyPanel.ctcontrolkind[x];
        double toHold = MyPanel.getdistance2(MyPanel.doucount[0][x], MyPanel.doucount[1][x], MyPanel.ctpositionreal[0][x],
                MyPanel.ctpositionreal[1][x]);
        if (c == 1) {
            f.act.search(x, 4); // attack
        } else if (c == 17) {
            // build mode: go to the build site
            if (toHold < 3600.0) {
                hold(x);
            } else {
                f.act.search(x, 13);
            }
        } else if (c == 2 || c == 8 || c == 9 || c == 11 || c == 12 || c == 14 || c == 18 || c == 22) {
            // go to / hold the ordered point
            if (toHold < 3600.0) {
                hold(x);
            } else {
                f.act.search(x, 7);
            }
        } else {
            // no order: stay where he is (and never walk to an old target)
            MyPanel.gocounter[0][x] = MyPanel.doucount[0][x];
            MyPanel.gocounter[1][x] = MyPanel.doucount[1][x];
        }
        MyPanel.ctrun[x] = false;
        if (!MyPanel.ctrunaimfired[x] && MyPanel.getdistance2(MyPanel.doucount[0][x], MyPanel.doucount[1][x],
                MyPanel.gocounter[0][x], MyPanel.gocounter[1][x]) > 900.0) {
            MyPanel.aimcount[x] = MyPanel.toradian(MyPanel.doucount[0][x], MyPanel.doucount[1][x], MyPanel.gocounter[0][x],
                    MyPanel.gocounter[1][x]);
        }
    }

    private static void hold(int x) {
        MyPanel.gocounter[0][x] = MyPanel.ctpositionreal[0][x];
        MyPanel.gocounter[1][x] = MyPanel.ctpositionreal[1][x];
    }
}
