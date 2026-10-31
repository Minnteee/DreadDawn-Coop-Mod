package coop.sync;

import Game.Direction;
import Game.MapThing;
import Game.MyPanel;
import coop.Coop;
import coop.Log;
import coop.net.Msg;
import coop.net.Net;
import coop.net.Proto;
import java.io.File;

/**
 * The story with two players (the story itself runs on the host):
 * - story goals "go there / talk there / reach the area" and map events (ambushes, scenes, hints ...)
 *   are triggered by EITHER player; mission markers ("!") can be started by the guest too;
 * - story rewards (items, EXP, skill points) and story rests (full health) reach the guest as well;
 * - while the host is dead and watches, the story and mission modes go on for the guest.
 */
public final class StoryCoop {
    private StoryCoop() {}

    static final int S_MISSION_START = 1;
    static final int S_GIVE = 2;
    static final int S_SPECIAL = 3;

    private static final ThreadLocal<Boolean> applying = ThreadLocal.withInitial(() -> false);

    /** host: the guest is in this world, alive, on the same map */
    static boolean guestOk() {
        return Net.isHost() && AiAssist.active() && !Puppet.isRemoteDead() && MyPanel.ctheal[Puppet.K] > 0;
    }

    // ------------------------------------------------------------------ goals reached by the guest

    /** REDIRECT of Direction.targetclose(D)Z inside Direction.targetouch() */
    public static boolean targetclose(Direction d, double r) {
        if (d.targetclose(r)) {
            return true;
        }
        if (!guestOk()) {
            return false;
        }
        int t = d.targetnow;
        if (t < 0) {
            return false;
        }
        int kind = d.targetkind[t];
        int other = d.targetother[t][0];
        // talk / photo / reach a point; not: stand at a point, take, on a skateboard (need the host's figure)
        boolean allowed = kind == 0 || kind == 5 || kind == 22 || (kind == 1 && other != 1);
        if (!allowed || MyPanel.selectid != d.targetid[t] || !MyPanel.select.equals(d.target[t])) {
            return false;
        }
        double dx = d.targetpos[t][0] - MyPanel.doucount[0][Puppet.K];
        double dy = d.targetpos[t][1] - MyPanel.doucount[1][Puppet.K];
        return r * r > dx * dx + dy * dy;
    }

    /** REDIRECT of Direction.inarea(DDI)Z inside Direction.targetouch() */
    public static boolean inarea(Direction d, double x, double y, int area) {
        if (d.inarea(x, y, area)) {
            return true;
        }
        if (!guestOk() || x != MyPanel.doucount[0][300] || y != MyPanel.doucount[1][300]) {
            return false; // zombie / NPC checks stay as they are
        }
        int t = d.targetnow;
        boolean goal = t >= 0 && d.targetkind[t] == 10 && area == d.targetother[t][0];
        boolean missionArea = d.missionhead && d.missionheadnow >= 0 && area == d.missionpos[d.missionheadnow][0];
        if (!goal && !missionArea) {
            return false;
        }
        return d.inarea(MyPanel.doucount[0][Puppet.K], MyPanel.doucount[1][Puppet.K], area);
    }

    // ------------------------------------------------------------------ the story goes on while the host is dead

    private static boolean hostDeadGuestPlays() {
        return Net.isHost() && DeathSync.spectating() && guestOk();
    }

    /** FIELD_GET deathed in Direction.targetouch() */
    public static boolean storyDeathed() {
        return MyPanel.deathed && !hostDeadGuestPlays();
    }

    private static final double[] ALIVE = new double[301];

    static {
        java.util.Arrays.fill(ALIVE, 1.0);
    }

    /** FIELD_GET ctheal in MyPanel.realtime(): its only reads are "ctheal[300] > 0" around the mission modes */
    public static double[] realtimeHeal() {
        return hostDeadGuestPlays() ? ALIVE : MyPanel.ctheal;
    }

    // ------------------------------------------------------------------ mission markers ("!")

    private static long lastStartRequest;

    /** HEAD_CANCEL Direction.startmission(): on the client the host starts it */
    public static boolean clientStartMission() {
        if (!Net.isClient() || !Coop.clientSimOff()) {
            return false;
        }
        Direction d = MyPanel.Direction;
        long now = System.currentTimeMillis();
        if (d != null && d.missionhead && now - lastStartRequest > 1000 && Coop.playing()) {
            lastStartRequest = now;
            Net.send(new Msg(Proto.STORY2, 16).u8(S_MISSION_START).i32(d.missionuming).i32(d.missionheadnow));
            Log.info("mission requested: " + d.missionuming + "/" + d.missionheadnow);
        }
        return true;
    }

    private static void hostStartMission(int mission, int head) {
        Direction d = MyPanel.Direction;
        if (d == null || !d.missionhead || d.targetnow >= 0 || d.missionuming != mission || d.missionheadnow != head
                || MyPanel.HeadDialog.HeadDialog || MyPanel.Animation.play) {
            return;
        }
        d.startmission();
        Log.info("mission started by the guest: " + mission + "/" + head);
    }

    // ------------------------------------------------------------------ map events the guest walks into

    /** TAIL MapThing.treasure(Z)V on the host: the same check once more with the guest's position */
    public static void treasureGuestPass(boolean fore) {
        if (fore || !guestOk() || MyPanel.HeadDialog.HeadDialog || MyPanel.Animation.play || !MyPanel.travelingfinish
                || MyPanel.treasureTIME == 0.0) {
            return;
        }
        MapThing mt = MyPanel.MapThing;
        if (mt == null || mt.touchlong || mt.standtime != 0) {
            return;
        }
        double gx = MyPanel.doucount[0][Puppet.K];
        double gy = MyPanel.doucount[1][Puppet.K];
        for (int x = 0; x < mt.treasurenuming; x++) {
            int kind = mt.treasurekind[x];
            if (mt.treasuremap[x] != null || kind == 30 || kind == 14 || kind == 20) {
                continue;
            }
            boolean use;
            if (mt.treasure[0][x] < 0) {
                use = mt.treasure[1][x] == 0 && mt.inarea(gx, gy, mt.treasureareaid[x]);
            } else {
                double dx = gx - mt.treasure[0][x];
                double dy = gy - mt.treasure[1][x];
                use = dx * dx + dy * dy < 40000.0 && MyPanel.checkpointTAKE(gx, gy, mt.treasure[0][x], mt.treasure[1][x]);
            }
            if (!use) {
                continue;
            }
            if (mt.treasurecheck[x] != null) {
                String[] buff = mt.treasurecheck[x].split("\\*");
                if (buff[0].equals("0")) {
                    int px = buff.length == 3 ? Integer.parseInt(buff[1]) : mt.treasure[0][x];
                    int py = buff.length == 3 ? Integer.parseInt(buff[2]) : mt.treasure[1][x];
                    if (MyPanel.Humanrespawn.find_human_near(px, py) < 0) {
                        mt.removetreasure(x);
                        return;
                    }
                }
            }
            if (mt.treasurewait[x] > 0.0) {
                mt.treasure[0][x] = -1;
                mt.treasure[1][x] = 1; // the game turns it into a timed event as well
                return;
            }
            String mission = mt.treasuremission[x];
            String branch = mt.treasurebranch[x];
            String option = mt.treasureoption[x];
            int zonetime = mt.treasurezonetime[x];
            mt.removetreasure(x);
            if (kind == 10) {
                // a deadly trap: it hits the one who walked into it
                MyPanel.ctdamage(Puppet.K, 5000.0, 0.0, false, false);
            } else {
                mt.treasureUSE(kind, mission, branch, option, zonetime);
            }
            MyPanel.treasureTIME = 0.0;
            Log.info("map event triggered by the guest (kind " + kind + ")");
            return;
        }
    }

    // ------------------------------------------------------------------ rewards and rests

    /** HEAD Direction.give(path) on the host: the guest gets the same reward */
    public static void giveHead(String path) {
        if (applying.get() || !Net.isHost() || !Coop.playing() || path == null) {
            return;
        }
        Net.send(new Msg(Proto.STORY2, 256).u8(S_GIVE).str(StorySync.toTemplate(path)).str(StorySync.lang()));
    }

    /** HEAD Direction.addtargetnow() on the host: a story rest (special 1 / 5) heals the guest too */
    public static void targetDoneHead(Direction d) {
        if (!Net.isHost() || !Coop.playing() || d.targetnow < 0 || d.targetnow >= d.targetkind.length) {
            return;
        }
        if (d.targetkind[d.targetnow] == 24) {
            int other = d.targetother[d.targetnow][0];
            if (other == 1 || other == 5) {
                Net.send(new Msg(Proto.STORY2, 8).u8(S_SPECIAL).u8(other));
            }
        }
    }

    private static void clientSpecial(int other) {
        MyPanel.ctheal[300] = MyPanel.healplayer = MyPanel.heal;
        if (other == 5) {
            MyPanel.ctkevlar[300] = MyPanel.kevlarplayer = MyPanel.kevlar;
        } else {
            MyPanel.kevlarplayer = 0.0;
            MyPanel.ctkevlar[300] = 0.0;
        }
        MyPanel.cthunger[300] = MyPanel.hungered;
        MyPanel.ctstrength[300] = MyPanel.cthunger[300];
        MyPanel.ctthirst[300] = MyPanel.thirsted;
        MyPanel.sleep = MyPanel.sleeped;
    }

    // ------------------------------------------------------------------ messages

    public static void handle(Msg m) {
        int kind = m.u8();
        switch (kind) {
            case S_MISSION_START: {
                int mission = m.i32();
                int head = m.i32();
                if (Net.isHost()) {
                    Coop.runSim(() -> hostStartMission(mission, head));
                }
                break;
            }
            case S_GIVE: {
                String t = m.str();
                String l = m.str();
                if (Net.isClient()) {
                    Coop.runSim(() -> {
                        String path = StorySync.fromTemplate(t, l);
                        if (MyPanel.Direction == null || !new File(path).exists()) {
                            return;
                        }
                        applying.set(true);
                        try {
                            MyPanel.Direction.give(path);
                            Log.info("story reward received: " + new File(path).getName());
                        } catch (Throwable e) {
                            Log.error("story reward", e);
                        } finally {
                            applying.set(false);
                        }
                    });
                }
                break;
            }
            case S_SPECIAL: {
                int other = m.u8();
                if (Net.isClient()) {
                    Coop.runSim(() -> clientSpecial(other));
                }
                break;
            }
            default:
                break;
        }
    }
}
