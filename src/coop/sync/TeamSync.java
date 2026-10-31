package coop.sync;

import Game.MyPanel;
import coop.Coop;
import coop.Log;
import coop.net.Msg;
import coop.net.Net;
import coop.net.Proto;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Companions and NPC dialogs for the guest. NPCs are simulated by the host, so what the guest does with
 * them is done on the host:
 * - commands in the command mode (Q: charge, go to, follow, hold, fire at will) for the selected companions;
 * - dialog results (hire, dismiss, girlfriend, buddy, ...) and the companion menu (C: call, search);
 * - missions started in a dialog (tower defence, air drop).
 * Companions belong to the player who hired or last commanded them. "Follow" means follow that player:
 * the host leads the guest's companions to the guest.
 */
public final class TeamSync {
    private TeamSync() {}

    static final int EV_CMD = 1;
    static final int EV_EDIT = 2;
    static final int EV_MISSION = 3;

    static final int F_TEAM = 1;
    static final int F_CONTROL = 2;
    static final int F_FIGHT = 4;
    static final int F_FIRE = 8;
    static final int F_POS = 16;
    static final int F_GONE = 32;
    static final int F_NAME = 64;
    static final int F_ACTION = 128;
    static final int F_CALL = 256;
    static final int F_HIDE = 512;
    static final int F_CODE = 1024;

    // ------------------------------------------------------------------ host: owners

    /** host: companion slot -> belongs to the guest */
    private static final boolean[] guestOwned = new boolean[300];
    /** host: the guest's companion follows the guest */
    private static final boolean[] guestFollow = new boolean[300];
    private static final String[] ownedCode = new String[300];

    public static boolean followsGuest(int x) {
        return x >= 0 && x < Puppet.K && guestOwned[x] && guestFollow[x] && sameCode(x);
    }

    private static boolean sameCode(int x) {
        return ownedCode[x] != null && ownedCode[x].equals(MyPanel.ctcode[x]);
    }

    private static void setOwner(int x, boolean guest, boolean follow) {
        guestOwned[x] = guest;
        guestFollow[x] = guest && follow;
        ownedCode[x] = guest ? MyPanel.ctcode[x] : null;
    }

    private static long lastLead;

    /** Host worker, every frame: the guest's following companions walk to the guest. */
    public static void hostTick() {
        long now = System.currentTimeMillis();
        if (now - lastLead < 250) {
            return;
        }
        lastLead = now;
        boolean guestHere = AiAssist.active() && MyPanel.ctheal[Puppet.K] > 0;
        double gx = MyPanel.doucount[0][Puppet.K];
        double gy = MyPanel.doucount[1][Puppet.K];
        int n = 0;
        int[] out = new int[2];
        for (int x = 0; x < Puppet.K; x++) {
            if (!guestOwned[x]) {
                continue;
            }
            if (!sameCode(x) || MyPanel.ctheal[x] <= 0 || !MyPanel.isteamate(x)) {
                if (!sameCode(x)) {
                    setOwner(x, false, false);
                }
                continue;
            }
            if (!guestFollow[x]) {
                continue;
            }
            int kind = MyPanel.ctcontrolkind[x];
            if (kind != 2 && kind != 3) {
                guestFollow[x] = false; // something else happened (story, search ...)
                continue;
            }
            if (!guestHere) {
                MyPanel.ctcontrolkind[x] = 3; // guest away or dead: follow the host meanwhile
                continue;
            }
            double ang = (n++ % 8) * Math.PI / 4;
            double tx = gx + Math.cos(ang) * 70;
            double ty = gy + Math.sin(ang) * 70;
            double dx = MyPanel.doucount[0][x] - gx;
            double dy = MyPanel.doucount[1][x] - gy;
            MyPanel.ctcontrolkind[x] = 2;
            if (dx * dx + dy * dy > 170 * 170) {
                if (!MyPanel.position.findposition((int) tx, (int) ty, out)) {
                    tx = gx;
                    ty = gy;
                    MyPanel.position.findposition((int) tx, (int) ty, out);
                }
                MyPanel.ctposition[0][x] = out[0];
                MyPanel.ctposition[1][x] = out[1];
                MyPanel.ctpositionreal[0][x] = (int) tx;
                MyPanel.ctpositionreal[1][x] = (int) ty;
            } else {
                MyPanel.ctpositionreal[0][x] = (int) MyPanel.doucount[0][x];
                MyPanel.ctpositionreal[1][x] = (int) MyPanel.doucount[1][x];
                MyPanel.ctposition[0][x] = MyPanel.count[0][x];
                MyPanel.ctposition[1][x] = MyPanel.count[1][x];
            }
        }
    }

    /** what HumanSync shows the guest as command of companion x (following the guest = "follow") */
    public static int shownControl(int x) {
        return followsGuest(x) ? 3 : MyPanel.ctcontrolkind[x];
    }

    // ------------------------------------------------------------------ client: command mode

    /** HEAD Command.command(): a command given by the guest goes to the host too. */
    public static void clientCommand() {
        if (!Net.isClient() || !Coop.playing() || !MyPanel.clickrelease_page3 || MyPanel.craftmode) {
            return;
        }
        MyPanel.screentomap(MyPanel.moux, MyPanel.mouy);
        double mx = MyPanel.mapx;
        double my = MyPanel.mapy;
        int kind = Game.Command.commandkind;
        List<Integer> ids = new ArrayList<>();
        for (int y = 0; y < Puppet.K; y++) {
            if (MyPanel.ctcontrol[y] && MyPanel.ctheal[y] > 0 && MyPanel.isteamateCONTROL(y) && MyPanel.ctcontrolkind[y] != 23) {
                ids.add(y);
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        Msg m = new Msg(Proto.TEAM, 64 + ids.size() * 32).u8(EV_CMD).u8(kind).f64(mx).f64(my).u8(ids.size());
        for (int y : ids) {
            m.i16(y).str(MyPanel.ctcode[y]);
        }
        Net.send(m);
    }

    private static void hostCommand(int kind, double mx, double my, int[] ids, String[] codes) {
        int[] out = new int[2];
        MyPanel.position.findposition((int) mx, (int) my, out);
        boolean charge = false;
        int chargeId = 0;
        if (kind == 1 && MyPanel.ctchargenum < MyPanel.chargenum - 1) {
            chargeId = MyPanel.ctchargeiding++;
            int c = MyPanel.ctchargenum;
            MyPanel.ctchargetime[c] = 3;
            MyPanel.ctchargeposition[0][c] = out[0];
            MyPanel.ctchargeposition[1][c] = out[1];
            MyPanel.ctchargepositionid[c] = chargeId;
            MyPanel.ctchargenum++;
            charge = true;
        }
        for (int i = 0; i < ids.length; i++) {
            int y = ids[i];
            if (y < 0 || y >= Puppet.K || MyPanel.ctheal[y] <= 0 || codes[i] == null || !codes[i].equals(MyPanel.ctcode[y])
                    || !MyPanel.isteamateCONTROL(y)) {
                continue;
            }
            switch (kind) {
                case 1:
                    if (charge) {
                        MyPanel.ctcontrolkind[y] = 1;
                        MyPanel.ctchargeid[y] = chargeId;
                    }
                    setOwner(y, true, false);
                    break;
                case 2:
                    MyPanel.ctcontrolkind[y] = 2;
                    MyPanel.ctposition[0][y] = out[0];
                    MyPanel.ctposition[1][y] = out[1];
                    MyPanel.ctpositionreal[0][y] = (int) mx;
                    MyPanel.ctpositionreal[1][y] = (int) my;
                    setOwner(y, true, false);
                    break;
                case 3:
                    MyPanel.ctcontrolkind[y] = 3;
                    setOwner(y, true, true);
                    break;
                case 4:
                    MyPanel.ctcontrolkind[y] = 4;
                    MyPanel.ctfireatwill[y] = false;
                    setOwner(y, true, false);
                    break;
                case 6:
                    MyPanel.ctfireatwill[y] = true;
                    break;
                case 7:
                    MyPanel.ctfireatwill[y] = false;
                    break;
                default:
                    break;
            }
        }
        Log.info("command " + kind + " from the guest to " + ids.length + " companions");
    }

    /** host: the host commands a companion itself -> it is the host's again */
    public static void hostCommandHead() {
        if (!Net.isHost() || !MyPanel.clickrelease_page3) {
            return;
        }
        for (int y = 0; y < Puppet.K; y++) {
            if (MyPanel.ctcontrol[y] && guestOwned[y]) {
                setOwner(y, false, false);
            }
        }
    }

    // ------------------------------------------------------------------ client: dialogs and the companion menu

    /** NPC values before a dialog / the companion menu ran (one set per caller: they run on different threads) */
    static final class Snap {
        final int[] team = new int[300];
        final int[] control = new int[300];
        final boolean[] fight = new boolean[300];
        final boolean[] fire = new boolean[300];
        final int[] posX = new int[300];
        final int[] posY = new int[300];
        final double[] heal = new double[300];
        final String[] code = new String[300];
        final String[] name = new String[300];
        final int[] action = new int[300];
        final double[] x = new double[300];
        int mission;
        boolean taken;
    }

    public static final int DIALOG = 0;
    public static final int MENU = 1;
    private static final Snap[] snaps = {new Snap(), new Snap()};

    /** HEAD of Dialogview.dialog() / Teamate.teamate(g) on the client */
    public static void clientSnapshot(int which) {
        Snap s = snaps[which];
        s.taken = false;
        if (!Net.isClient() || !Coop.playing() || MyPanel.Humanrespawn == null) {
            return;
        }
        for (int x = 0; x < Puppet.K; x++) {
            s.team[x] = MyPanel.cteamatekind[x];
            s.control[x] = MyPanel.ctcontrolkind[x];
            s.fight[x] = MyPanel.ctfight[x];
            s.fire[x] = MyPanel.ctfireatwill[x];
            s.posX[x] = MyPanel.ctpositionreal[0][x];
            s.posY[x] = MyPanel.ctpositionreal[1][x];
            s.heal[x] = MyPanel.ctheal[x];
            s.code[x] = MyPanel.ctcode[x];
            s.name[x] = MyPanel.ctname[x];
            s.action[x] = MyPanel.Humanrespawn.action[x];
            s.x[x] = MyPanel.doucount[0][x];
        }
        s.mission = MyPanel.missionmodekind;
        s.taken = true;
    }

    /** TAIL of Dialogview.dialog() / Teamate.teamate(g) on the client: what changed goes to the host */
    public static void clientDiff(int which) {
        Snap s = snaps[which];
        if (!s.taken || !Net.isClient() || !Coop.playing()) {
            return;
        }
        s.taken = false;
        for (int x = 0; x < Puppet.K; x++) {
            if (s.code[x] == null) {
                continue;
            }
            int f = 0;
            if (MyPanel.cteamatekind[x] != s.team[x]) {
                f |= F_TEAM;
            }
            if (MyPanel.ctcontrolkind[x] != s.control[x]) {
                f |= F_CONTROL;
            }
            if (MyPanel.ctfight[x] != s.fight[x]) {
                f |= F_FIGHT;
            }
            if (MyPanel.ctfireatwill[x] != s.fire[x]) {
                f |= F_FIRE;
            }
            if (MyPanel.ctpositionreal[0][x] != s.posX[x] || MyPanel.ctpositionreal[1][x] != s.posY[x]) {
                f |= F_POS;
            }
            if (MyPanel.ctheal[x] <= 0 && s.heal[x] > 0 && MyPanel.ctcode[x] == null) {
                f |= F_GONE;
            }
            if (MyPanel.ctname[x] != null && !MyPanel.ctname[x].equals(s.name[x])) {
                f |= F_NAME;
            }
            if (MyPanel.Humanrespawn.action[x] != s.action[x]) {
                f |= F_ACTION;
            }
            if (MyPanel.ctcode[x] != null && !MyPanel.ctcode[x].equals(s.code[x])) {
                f |= F_CODE;
            }
            double nx = MyPanel.doucount[0][x];
            if (nx != s.x[x]) {
                if (nx == MyPanel.doucount[0][300]) {
                    f |= F_CALL;
                } else if (nx <= -23456) {
                    f |= F_HIDE;
                }
            }
            // the menu runs while HumanSync writes host values on the other thread: only its own
            // actions count there (call = to the player, search = away), they always move the figure
            if (which == MENU && (f & (F_CALL | F_HIDE)) == 0) {
                continue;
            }
            if (f == 0) {
                continue;
            }
            Net.send(new Msg(Proto.TEAM, 96).u8(EV_EDIT).i16(x).str(s.code[x]).i32(f).u8(MyPanel.cteamatekind[x])
                    .u8(MyPanel.ctcontrolkind[x]).bool(MyPanel.ctfight[x]).bool(MyPanel.ctfireatwill[x])
                    .i32(MyPanel.ctpositionreal[0][x]).i32(MyPanel.ctpositionreal[1][x]).str(MyPanel.ctname[x])
                    .i32(MyPanel.Humanrespawn.action[x]).str(MyPanel.ctcode[x]));
            Log.info("NPC " + x + " changed by the guest (" + Integer.toBinaryString(f) + ")");
        }
        if (which == DIALOG && MyPanel.missionmodekind != s.mission && MyPanel.missionmodekind == 1) {
            int page = MyPanel.zombieareaAIRDROP ? 14 : 7;
            Net.send(new Msg(Proto.TEAM, 32).u8(EV_MISSION).u8(page).i32(MyPanel.towerid).i32(MyPanel.airdropid));
            Log.info("mission started in a dialog (page " + page + ") -> Host");
        }
    }

    private static void hostEdit(int x, String code, int f, int team, int control, boolean fight, boolean fire, int px, int py,
            String name, int action, String newCode) {
        if (x < 0 || x >= Puppet.K || code == null || !code.equals(MyPanel.ctcode[x])) {
            Log.info("NPC change from the guest dropped (" + x + ")");
            return;
        }
        if ((f & F_GONE) != 0) {
            MyPanel.ctheal[x] = 0;
            MyPanel.ctcode[x] = null;
            MyPanel.doucount[0][x] = -100000;
            MyPanel.doucount[1][x] = -100000;
            setOwner(x, false, false);
            return;
        }
        if ((f & F_TEAM) != 0) {
            MyPanel.cteamatekind[x] = (byte) team;
        }
        if ((f & F_CONTROL) != 0) {
            MyPanel.ctcontrolkind[x] = (byte) control;
        }
        if ((f & F_FIGHT) != 0) {
            MyPanel.ctfight[x] = fight;
        }
        if ((f & F_FIRE) != 0) {
            MyPanel.ctfireatwill[x] = fire;
        }
        if ((f & F_POS) != 0) {
            int[] out = new int[2];
            MyPanel.position.findposition(px, py, out);
            MyPanel.ctposition[0][x] = out[0];
            MyPanel.ctposition[1][x] = out[1];
            MyPanel.ctpositionreal[0][x] = px;
            MyPanel.ctpositionreal[1][x] = py;
        }
        if ((f & F_NAME) != 0 && name != null) {
            MyPanel.ctname[x] = name;
        }
        if ((f & F_ACTION) != 0) {
            MyPanel.Humanrespawn.action[x] = action;
        }
        if ((f & F_CODE) != 0 && newCode != null) {
            MyPanel.ctcode[x] = newCode;
        }
        if ((f & F_CALL) != 0 && Puppet.active()) {
            MyPanel.doucount[0][x] = MyPanel.doucount[0][Puppet.K];
            MyPanel.doucount[1][x] = MyPanel.doucount[1][Puppet.K];
        }
        if ((f & F_HIDE) != 0) {
            MyPanel.doucount[0][x] = -23456.0;
            MyPanel.doucount[1][x] = -23456.0;
            MyPanel.count[0][x] = 0;
            MyPanel.count[1][x] = 0;
            MyPanel.aimcounted[x] = Game.Command.search_time + Math.random() * 20.0;
        }
        boolean teammate = MyPanel.isteamate(x);
        if (teammate) {
            setOwner(x, true, MyPanel.ctcontrolkind[x] == 3);
        } else {
            setOwner(x, false, false);
        }
        Log.info("NPC " + x + " from the guest: team " + MyPanel.cteamatekind[x] + " command " + MyPanel.ctcontrolkind[x]);
    }

    private static void hostMission(int page, int tower, int airdrop) {
        if (MyPanel.missionmodekind == 1 || MyPanel.Dialogview == null) {
            return;
        }
        MyPanel.towerid = tower;
        MyPanel.airdropid = airdrop;
        Game.Dialogview d = MyPanel.Dialogview;
        d.page = page;
        d.option = 0;
        d.PressNUMBER = true;
        try {
            d.dialog();
        } finally {
            d.PressNUMBER = false;
        }
        Log.info("mission started by the guest (page " + page + ")");
    }

    // ------------------------------------------------------------------ messages

    public static void handle(Msg m) {
        if (!Net.isHost()) {
            return;
        }
        int ev = m.u8();
        switch (ev) {
            case EV_CMD: {
                int kind = m.u8();
                double mx = m.f64();
                double my = m.f64();
                int n = m.u8();
                int[] ids = new int[n];
                String[] codes = new String[n];
                for (int i = 0; i < n; i++) {
                    ids[i] = m.i16();
                    codes[i] = m.str();
                }
                Coop.runSim(() -> hostCommand(kind, mx, my, ids, codes));
                break;
            }
            case EV_EDIT: {
                int x = m.i16();
                String code = m.str();
                int f = m.i32();
                int team = m.s8();
                int control = m.s8();
                boolean fight = m.bool();
                boolean fire = m.bool();
                int px = m.i32();
                int py = m.i32();
                String name = m.str();
                int action = m.i32();
                String newCode = m.str();
                Coop.runSim(() -> hostEdit(x, code, f, team, control, fight, fire, px, py, name, action, newCode));
                break;
            }
            case EV_MISSION: {
                int page = m.u8();
                int tower = m.i32();
                int airdrop = m.i32();
                Coop.runSim(() -> hostMission(page, tower, airdrop));
                break;
            }
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ keeping the guest's companions

    /** host: the guest's companions are remembered in Save\auto\coop\NAME.team (codes) */
    public static void save(Path dir, String guestName) {
        StringBuilder sb = new StringBuilder();
        for (int x = 0; x < Puppet.K; x++) {
            if (guestOwned[x] && sameCode(x)) {
                sb.append(MyPanel.ctcode[x]).append(guestFollow[x] ? ",1" : ",0").append('\n');
            }
        }
        try {
            Files.write(dir.resolve(guestName + ".team"), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.error("saving companions", e);
        }
    }

    /** host, after a load: the guest's companions belong to the guest again */
    public static void load(Path dir, String guestName) {
        for (int x = 0; x < 300; x++) {
            setOwner(x, false, false);
        }
        try {
            Path f = dir.resolve(guestName + ".team");
            if (!Files.exists(f)) {
                return;
            }
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String[] p = line.trim().split(",");
                if (p.length < 1 || p[0].isEmpty()) {
                    continue;
                }
                for (int x = 0; x < Puppet.K; x++) {
                    if (p[0].equals(MyPanel.ctcode[x]) && MyPanel.ctheal[x] > 0) {
                        setOwner(x, true, p.length > 1 && p[1].equals("1"));
                    }
                }
            }
        } catch (Exception e) {
            Log.error("loading companions", e);
        }
    }

    public static void reset() {
        for (int x = 0; x < 300; x++) {
            setOwner(x, false, false);
        }
    }

    /** host, before a map change: companions walking to the guest (command 2) would stay behind; they follow (3) */
    public static void beforeHostTravel() {
        for (int x = 0; x < Puppet.K; x++) {
            if (guestOwned[x] && guestFollow[x] && sameCode(x) && MyPanel.ctcontrolkind[x] == 2) {
                MyPanel.ctcontrolkind[x] = 3;
            }
        }
    }
}
