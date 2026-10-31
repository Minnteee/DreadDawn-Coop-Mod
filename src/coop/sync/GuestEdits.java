package coop.sync;

import Game.Direction;
import Game.MapThing;
import Game.MyPanel;
import Game.NoticeView;
import Game.Other;
import Game.WordAll;
import coop.Coop;
import coop.Log;
import coop.net.Msg;
import coop.net.Net;
import coop.net.Proto;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import coop.T;

/**
 * Shared world rules that are not plain object lists:
 * - locked exits / areas the guest opens (lockpick, code, key, paying) open on the host too;
 * - boxes the guest lockpicks stay open;
 * - story keys belong to both players: picked up or used by one, the other has / has used them too;
 * - power lines: the host decides (shot by the guest, hit by an explosion), both see the sparks / the blast;
 * - camp warnings, "kill them all" and the air drop hint of the host's story / missions reach the guest;
 * - the yellow arrow of story and air drop goals points the guest's way as well (from his own position);
 * - "go to the camp now" from the guest's mission menu starts the siege on the host.
 * The client compares with the last state it got from the host and sends what it changed itself.
 */
public final class GuestEdits {
    private GuestEdits() {}

    static final int G_AREA_UNLOCK = 1;
    static final int G_BOX_UNLOCK = 2;
    static final int G_KEY_TAKE = 3;
    static final int G_KEY_USE = 4;
    static final int G_DOOR_KEY = 5;
    static final int G_POWER_REQ = 6;
    static final int G_POWER = 7;
    static final int G_NOTICE = 8;
    static final int G_HINT = 9;
    static final int G_GUIDE = 10;
    static final int G_GUIDE_DIR = 11;
    static final int G_JUMP_CAMP = 12;

    private static boolean guest() {
        return Net.isClient() && Coop.playing() && Coop.clientSimOff();
    }

    private static boolean host() {
        return Net.isHost() && Coop.playing();
    }

    // ------------------------------------------------------------------ last host state (client)

    private static volatile List<String> areaSeen;
    private static volatile Map<String, Boolean> boxSeen;

    private static String areaKey(int i) {
        return MyPanel.areanameing[i] + "#" + MyPanel.areaiding[i];
    }

    private static List<String> areaList() {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < MyPanel.areanuminging; i++) {
            if (MyPanel.areanameing[i] != null) {
                l.add(areaKey(i));
            }
        }
        return l;
    }

    private static String boxKey(int i) {
        return MyPanel.box[0][i] + "," + MyPanel.box[1][i];
    }

    /** WorldSync (client): a host state of this system was just applied */
    static void applied(String system) {
        switch (system) {
            case "area":
                areaSeen = areaList();
                try {
                    MyPanel.area(); // the exits follow the locks
                } catch (Throwable t) {
                    Log.rare("area " + t);
                }
                break;
            case "box": {
                Map<String, Boolean> m = new HashMap<>();
                for (int i = 0; i < MyPanel.boxexist.length; i++) {
                    if (MyPanel.boxexist[i]) {
                        m.put(boxKey(i), MyPanel.boxlocked[i]);
                    }
                }
                boxSeen = m;
                break;
            }
            default:
                break;
        }
    }

    /** client worker (WorldSync.clientTick, before the newest host state is applied) */
    static void clientTick() {
        if (!guest()) {
            return;
        }
        // exits / areas the guest unlocked
        List<String> seen = areaSeen;
        if (seen != null) {
            List<String> now = areaList();
            for (String k : seen) {
                if (!now.contains(k)) {
                    int h = k.lastIndexOf('#');
                    Net.send(new Msg(Proto.GUEST, 64).u8(G_AREA_UNLOCK).str(k.substring(0, h))
                            .i32(Integer.parseInt(k.substring(h + 1))));
                    Log.info("area unlocked -> host: " + k);
                }
            }
            areaSeen = now;
        }
        // boxes the guest lockpicked
        Map<String, Boolean> bs = boxSeen;
        if (bs != null) {
            for (int i = 0; i < MyPanel.boxexist.length; i++) {
                if (!MyPanel.boxexist[i] || MyPanel.boxlocked[i]) {
                    continue;
                }
                String k = boxKey(i);
                if (Boolean.TRUE.equals(bs.get(k))) {
                    bs.put(k, false);
                    Net.send(new Msg(Proto.GUEST, 16).u8(G_BOX_UNLOCK).i32(MyPanel.box[0][i]).i32(MyPanel.box[1][i]));
                }
            }
        }
    }

    // ------------------------------------------------------------------ keys (hooks)

    /** TAIL Key.takekey(I)V */
    public static void keyTaken(int id) {
        if (guest()) {
            Net.send(new Msg(Proto.GUEST, 8).u8(G_KEY_TAKE).i16(id));
        }
    }

    /** HEAD Key.usekey(I)Z */
    public static void keyUsing(int id) {
        if (guest() && MyPanel.Key != null && id >= 0 && id < MyPanel.Key.keytaked.length && MyPanel.Key.keytaked[id] == 1) {
            Net.send(new Msg(Proto.GUEST, 8).u8(G_KEY_USE).i16(id));
        }
    }

    /** HEAD Other.opendoorwithkey(I)V: all doors of this key open on the host too */
    public static void doorKey(int key) {
        if (guest()) {
            Net.send(new Msg(Proto.GUEST, 8).u8(G_DOOR_KEY).i16(key));
        }
    }

    // ------------------------------------------------------------------ power lines (hooks)

    /** REDIRECT of Other.powertouch(I)V in MyPanel.shoot(): a bullet hit a power box */
    public static void powerShot(Other o, int x) {
        if (!Coop.playing()) {
            o.powertouch(x);
        } else if (Net.isClient()) {
            // the guest's own bullet (bullets of the host never get here): the host decides
            Net.send(new Msg(Proto.GUEST, 16).u8(G_POWER_REQ).i32(o.power[x][0][0]).i32(o.power[x][0][1]));
        } else {
            hostTouch(o, x);
        }
    }

    /** REDIRECT of Other.powertouch(I)V in powerstickexplode / powerhit (explosions, grenades) */
    public static void powerBlast(Other o, int x) {
        if (!Coop.playing()) {
            o.powertouch(x);
        } else if (Net.isHost()) {
            hostTouch(o, x);
        }
        // client: the same explosion / grenade exists on the host, which decides
    }

    private static void hostTouch(Other o, int x) {
        boolean boom = o.powerswitchnum[x] < 0;
        Net.send(new Msg(Proto.GUEST, 16).u8(G_POWER).i32(o.power[x][0][0]).i32(o.power[x][0][1]).bool(boom));
        HazardSync.sharedEnter(); // the blast happens on both machines
        try {
            o.powertouch(x);
        } finally {
            HazardSync.sharedExit();
        }
    }

    private static int powerAt(int x, int y) {
        Other o = MyPanel.Other;
        if (o == null) {
            return -1;
        }
        for (int i = 0; i < o.powernuming; i++) {
            if (o.power[i][0][0] == x && o.power[i][0][1] == y) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ messages of the host's story (hooks)

    /** REDIRECT of NoticeView.notice(String) in MyPanel.realtime() and Direction.targetouch() (host) */
    public static void hostNotice(NoticeView nv, String word) {
        nv.notice(word);
        if (!host() || word == null) {
            return;
        }
        String token = null;
        if (word == nv.campNOW) {
            token = "campNOW";
        } else if (word == nv.killall) {
            token = "killall";
        } else if (nv.camp_siege != null) {
            for (int i = 0; i < nv.camp_siege.length; i++) {
                if (word == nv.camp_siege[i]) {
                    token = "camp_siege" + i;
                }
            }
        }
        if (token != null) {
            Net.send(new Msg(Proto.GUEST, 32).u8(G_NOTICE).str(token));
        }
    }

    /** REDIRECT of WordAll.hintREAL(String, double) in MyPanel.realtime() (host) */
    public static void hostHint(WordAll w, String hint, double time) {
        w.hintREAL(hint, time);
        if (host() && hint != null && hint == w.go_airdrop) {
            Net.send(new Msg(Proto.GUEST, 32).u8(G_HINT).str("go_airdrop").f64(time));
        }
    }

    /** REDIRECT of Direction.newguidance(...) in MyPanel.realtime() (host): air drop arrow */
    public static boolean hostGuidance(Direction d, String map, int mapid, int x, int y, String branch) {
        boolean r = d.newguidance(map, mapid, x, y, branch);
        if (host() && map != null) {
            Msg m = new Msg(Proto.GUEST, 64).u8(G_GUIDE).str(map).i32(mapid).i32(x).i32(y).bool(branch != null);
            if (branch != null) {
                m.str(branch);
            }
            Net.send(m);
        }
        return r;
    }

    /** REDIRECT of MapThing.direction(String, String) in MapThing.treasureUSE (host): story arrow */
    public static boolean storyDirection(MapThing mt, String option, String branch) {
        boolean r = mt.direction(option, branch);
        if (host() && option != null) {
            Msg m = new Msg(Proto.GUEST, 64).u8(G_GUIDE_DIR).str(option).bool(branch != null);
            if (branch != null) {
                m.str(branch);
            }
            Net.send(m);
        }
        return r;
    }

    /** HEAD Mission.jump_camp()V: the guest's "go to the camp now" */
    public static void jumpCamp() {
        if (guest()) {
            Net.send(new Msg(Proto.GUEST, 4).u8(G_JUMP_CAMP));
        }
    }

    // ------------------------------------------------------------------ messages

    public static void handle(Msg m) {
        int kind = m.u8();
        switch (kind) {
            case G_AREA_UNLOCK: {
                String name = m.str();
                int id = m.i32();
                if (Net.isHost()) {
                    Coop.runSim(() -> {
                        for (int i = 0; i < MyPanel.areanuminging; i++) {
                            if (name.equals(MyPanel.areanameing[i]) && MyPanel.areaiding[i] == id) {
                                MyPanel.areanameing[i] = null;
                                MyPanel.area();
                                Log.info("area unlocked by the guest: " + name + id);
                                break;
                            }
                        }
                    });
                }
                break;
            }
            case G_BOX_UNLOCK: {
                int x = m.i32();
                int y = m.i32();
                if (Net.isHost()) {
                    Coop.runSim(() -> {
                        for (int i = 0; i < MyPanel.boxexist.length; i++) {
                            if (MyPanel.boxexist[i] && MyPanel.box[0][i] == x && MyPanel.box[1][i] == y) {
                                MyPanel.boxlocked[i] = false;
                                break;
                            }
                        }
                    });
                }
                break;
            }
            case G_KEY_TAKE: {
                int id = m.i16();
                if (Net.isHost()) {
                    Coop.runSim(() -> {
                        if (MyPanel.Key != null && id >= 0 && id < MyPanel.Key.keytaked.length && MyPanel.Key.keytaked[id] == 0) {
                            MyPanel.Key.takekey(id);
                            Log.info("key taken by the guest: " + id);
                        }
                    });
                }
                break;
            }
            case G_KEY_USE: {
                int id = m.i16();
                if (Net.isHost()) {
                    Coop.runSim(() -> {
                        if (MyPanel.Key != null && id >= 0 && id < MyPanel.Key.keytaked.length && MyPanel.Key.keytaked[id] == 1) {
                            MyPanel.Key.keytaked[id] = 2;
                        }
                    });
                }
                break;
            }
            case G_DOOR_KEY: {
                int key = m.i16();
                if (Net.isHost()) {
                    Coop.runSim(() -> {
                        if (MyPanel.Other != null) {
                            MyPanel.Other.opendoorwithkey(key);
                        }
                    });
                }
                break;
            }
            case G_POWER_REQ: {
                int x = m.i32();
                int y = m.i32();
                if (Net.isHost()) {
                    Coop.runSim(() -> {
                        int i = powerAt(x, y);
                        if (i >= 0) {
                            hostTouch(MyPanel.Other, i);
                        }
                    });
                }
                break;
            }
            case G_POWER: {
                int x = m.i32();
                int y = m.i32();
                boolean boom = m.bool();
                if (Net.isClient()) {
                    Coop.runSim(() -> {
                        int i = powerAt(x, y);
                        if (i < 0) {
                            if (boom) {
                                HazardSync.fxAt(x, y); // already gone here: show the blast
                            }
                            return;
                        }
                        Other o = MyPanel.Other;
                        o.powerfiretime[i] = 0;
                        HazardSync.sharedEnter();
                        try {
                            o.powertouch(i);
                        } finally {
                            HazardSync.sharedExit();
                        }
                    });
                }
                break;
            }
            case G_NOTICE: {
                String token = m.str();
                if (Net.isClient()) {
                    Coop.runSim(() -> {
                        NoticeView nv = MyPanel.NoticeView;
                        if (nv == null) {
                            return;
                        }
                        String text = null;
                        if (token.equals("campNOW")) {
                            text = nv.campNOW;
                        } else if (token.equals("killall")) {
                            text = nv.killall;
                        } else if (token.startsWith("camp_siege")) {
                            int i = Integer.parseInt(token.substring(10));
                            if (nv.camp_siege != null && i < nv.camp_siege.length) {
                                text = nv.camp_siege[i];
                            }
                        }
                        if (text != null) {
                            nv.notice(text);
                        }
                    });
                }
                break;
            }
            case G_HINT: {
                String token = m.str();
                double time = m.f64();
                if (Net.isClient() && token.equals("go_airdrop")) {
                    Coop.runSim(() -> {
                        if (MyPanel.WordAll != null && MyPanel.WordAll.go_airdrop != null) {
                            MyPanel.WordAll.hintREAL(MyPanel.WordAll.go_airdrop, time);
                        }
                    });
                }
                break;
            }
            case G_GUIDE: {
                String map = m.str();
                int mapid = m.i32();
                int x = m.i32();
                int y = m.i32();
                String branch = m.bool() ? m.str() : null;
                if (Net.isClient()) {
                    Coop.runSim(() -> {
                        if (MyPanel.Direction != null) {
                            MyPanel.Direction.newguidance(map, mapid, x, y, branch);
                        }
                    });
                }
                break;
            }
            case G_GUIDE_DIR: {
                String option = m.str();
                String branch = m.bool() ? m.str() : null;
                if (Net.isClient()) {
                    Coop.runSim(() -> {
                        if (MyPanel.MapThing != null) {
                            MyPanel.MapThing.direction(option, branch);
                        }
                    });
                }
                break;
            }
            case G_JUMP_CAMP: {
                if (Net.isHost()) {
                    Coop.runSim(() -> {
                        if (MyPanel.Mission != null) {
                            MyPanel.Mission.jump_camp();
                            Coop.message(T.s("{0} starts the siege now.", Net.peerName));
                        }
                    });
                }
                break;
            }
            default:
                break;
        }
    }

    public static void reset() {
        areaSeen = null;
        boxSeen = null;
    }
}
