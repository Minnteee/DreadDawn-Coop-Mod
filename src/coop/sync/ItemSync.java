package coop.sync;

import Game.MyPanel;
import coop.Coop;
import coop.Log;
import coop.net.Msg;
import coop.net.Net;
import coop.net.Proto;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Items. Item instances live in per-machine pools, so items always travel in the game's own text
 * format ("name*stat*stat...").
 * - Ground items: the host sends the full list when it changes; the client reports what its player
 *   picked up or dropped.
 * - Containers (boxes, corpses, bonfires, air drops ...): while the client has one open, its
 *   contents are kept equal on both machines. Loot is only ever rolled by the host.
 */
public final class ItemSync {
    private ItemSync() {}

    static final int EV_TAKE = 20;
    static final int EV_DROPITEM = 21;
    static final int EV_CASE_OPEN = 22;
    static final int EV_CASE_READY = 23;
    static final int EV_CORPSE_OPEN = 24;
    static final int EV_CORPSE_READY = 25;
    static final int EV_DROP_OPEN = 26;
    static final int EV_DROP_READY = 27;
    static final int EV_CONTAINER_CLOSE = 28;

    /** one ground item */
    static final class G {
        int x;
        int y;
        int kind;
        boolean always;
        String item;
        long time;

        String key() {
            return x + "," + y + "," + kind + "," + item;
        }
    }

    // ------------------------------------------------------------------ ground items: host

    private static long lastMapackHash = Long.MIN_VALUE;

    static List<G> readMapack() {
        List<G> out = new ArrayList<>();
        int n = MyPanel.mapacknuming;
        for (int i = 0; i < n; i++) {
            G g = new G();
            g.x = MyPanel.mapack[0][i];
            g.y = MyPanel.mapack[1][i];
            g.kind = MyPanel.mapackstockind[i];
            g.always = MyPanel.mapackALWAYS[i];
            try {
                g.item = MyPanel.pack.saveobject(g.kind, MyPanel.mapackid[i], false);
            } catch (Throwable t) {
                g.item = "";
            }
            out.add(g);
        }
        return out;
    }

    private static long mapackHash() {
        long h = MyPanel.mapacknuming;
        int n = MyPanel.mapacknuming;
        for (int i = 0; i < n; i++) {
            h = h * 31 + MyPanel.mapack[0][i];
            h = h * 31 + MyPanel.mapack[1][i];
            h = h * 31 + MyPanel.mapackstockind[i];
            h = h * 31 + MyPanel.mapackid[i];
        }
        return h;
    }

    static void hostTick() {
        try {
            long h = mapackHash();
            if (h != lastMapackHash) {
                lastMapackHash = h;
                List<G> list = readMapack();
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                DataOutputStream o = new DataOutputStream(b);
                o.writeInt(list.size());
                for (G g : list) {
                    o.writeInt(g.x);
                    o.writeInt(g.y);
                    o.writeInt(g.kind);
                    o.writeBoolean(g.always);
                    o.writeUTF(g.item);
                }
                o.flush();
                byte[] raw = b.toByteArray();
                Net.send(new Msg(Proto.MAPACK, raw.length / 4 + 16).i32(raw.length).blob(ArrayCodec.deflate(raw)));
            }
            hostContainerTick();
        } catch (Throwable t) {
            Log.error("ItemSync.hostTick", t);
        }
    }

    private static void hostTake(int x, int y, int kind, String item) {
        int best = -1;
        double bestD = 120 * 120;
        for (int i = 0; i < MyPanel.mapacknuming; i++) {
            if (MyPanel.mapackstockind[i] != kind) {
                continue;
            }
            String s = MyPanel.pack.saveobject(kind, MyPanel.mapackid[i], false);
            if (!Objects.equals(s, item)) {
                continue;
            }
            double d = Math.pow(MyPanel.mapack[0][i] - x, 2) + Math.pow(MyPanel.mapack[1][i] - y, 2);
            if (d <= bestD) {
                bestD = d;
                best = i;
            }
        }
        if (best < 0) {
            return;
        }
        try {
            MyPanel.pack.changename(kind, MyPanel.mapackid[best], 0);
        } catch (Throwable t) {
            // pool slot stays used; harmless
        }
        MyPanel.mapacktaker.mapackremove(best);
    }

    private static void hostDrop(int x, int y, int kind, boolean always, String item) {
        int id = MyPanel.pack.getobject(item.split("\\*"), kind);
        if (id > -1) {
            MyPanel.mapacktaker.mapackcreater(kind, id, x, y, always);
        }
    }

    // ------------------------------------------------------------------ ground items: client

    private static volatile List<G> pendingList;
    private static List<G> applied;
    private static final List<G> pendingTakes = new ArrayList<>();
    private static final List<G> pendingDrops = new ArrayList<>();

    private static Map<String, Integer> multiset(List<G> l) {
        Map<String, Integer> m = new HashMap<>();
        for (G g : l) {
            m.merge(g.key(), 1, Integer::sum);
        }
        return m;
    }

    /** @param full also detect the player's own pick-ups / drops */
    static void clientTick(boolean full) {
        try {
            long now = System.currentTimeMillis();
            if (full && applied != null) {
                List<G> cur = readMapack();
                Map<String, Integer> was = multiset(applied);
                Map<String, Integer> is = multiset(cur);
                // picked up by the player
                for (G g : applied) {
                    String k = g.key();
                    int have = is.getOrDefault(k, 0);
                    int had = was.getOrDefault(k, 0);
                    if (have < had) {
                        Net.send(new Msg(Proto.WORLD_EVENT).u8(EV_TAKE).i32(g.x).i32(g.y).i32(g.kind).str(g.item));
                        g.time = now;
                        pendingTakes.add(g);
                        was.put(k, had - 1);
                    }
                }
                // dropped by the player
                was = multiset(applied);
                for (G g : cur) {
                    String k = g.key();
                    int had = was.getOrDefault(k, 0);
                    if (had > 0) {
                        was.put(k, had - 1);
                    } else {
                        Net.send(new Msg(Proto.WORLD_EVENT).u8(EV_DROPITEM).i32(g.x).i32(g.y).i32(g.kind).bool(g.always).str(g.item));
                        g.time = now;
                        pendingDrops.add(g);
                    }
                }
                applied = cur;
            }
            pendingTakes.removeIf(g -> now - g.time > 3000);
            pendingDrops.removeIf(g -> now - g.time > 3000);
            List<G> target = pendingList;
            if (target != null && full) {
                pendingList = null;
                applyList(target);
                applied = readMapack();
            }
            clientContainerTick();
            if (reopen != null) {
                Runnable r = reopen;
                reopen = null;
                r.run();
            }
        } catch (Throwable t) {
            Log.error("ItemSync.clientTick", t);
        }
    }

    private static void applyList(List<G> target) {
        List<G> want = new ArrayList<>(target);
        // items the player just picked up stay picked up until the host confirms
        for (G t : pendingTakes) {
            for (Iterator<G> it = want.iterator(); it.hasNext();) {
                if (it.next().key().equals(t.key())) {
                    it.remove();
                    break;
                }
            }
        }
        // items the player just dropped stay on the ground until the host lists them
        for (G d : pendingDrops) {
            boolean listed = false;
            for (G g : want) {
                if (g.key().equals(d.key())) {
                    listed = true;
                    break;
                }
            }
            if (!listed) {
                want.add(d);
            }
        }
        Map<String, Integer> need = multiset(want);
        // remove what the host does not have
        for (int i = MyPanel.mapacknuming - 1; i >= 0; i--) {
            String k;
            int kind = MyPanel.mapackstockind[i];
            try {
                k = MyPanel.mapack[0][i] + "," + MyPanel.mapack[1][i] + "," + kind + ","
                        + MyPanel.pack.saveobject(kind, MyPanel.mapackid[i], false);
            } catch (Throwable t) {
                k = "?";
            }
            int c = need.getOrDefault(k, 0);
            if (c > 0) {
                need.put(k, c - 1);
            } else {
                try {
                    MyPanel.pack.changename(kind, MyPanel.mapackid[i], 0);
                } catch (Throwable t) {
                    // ignore
                }
                MyPanel.mapacktaker.mapackremove(i);
            }
        }
        // add what is missing
        for (G g : want) {
            int c = need.getOrDefault(g.key(), 0);
            if (c > 0) {
                need.put(g.key(), c - 1);
                int id = MyPanel.pack.getobject(g.item.split("\\*"), g.kind);
                if (id > -1) {
                    MyPanel.mapacktaker.mapackcreater(g.kind, id, g.x, g.y, g.always);
                }
            }
        }
    }

    // ------------------------------------------------------------------ containers

    /** client: container currently open in the inventory screen */
    private static String clientOpen;
    private static String clientSig;
    /** host: container the client has open */
    private static volatile String hostOpen;
    private static String hostSig;
    private static Runnable reopen;

    private static String openContainerCode() {
        String c = MyPanel.packleft;
        if (MyPanel.packnum != 0 || c == null || c.isEmpty() || c.equals(MyPanel.ctcode[300])) {
            return null;
        }
        return c;
    }

    private static void clientContainerTick() {
        String c = openContainerCode();
        if (!Objects.equals(c, clientOpen)) {
            if (clientOpen != null) {
                String s = Profile.packToString(clientOpen);
                Net.send(new Msg(Proto.CONTAINER_DATA).str(clientOpen).str(s == null ? "" : s).bool(true));
            }
            clientOpen = c;
            clientSig = null;
            if (c != null) {
                Net.send(new Msg(Proto.CONTAINER_REQ).str(c));
            }
            return;
        }
        if (c == null || clientSig == null) {
            return; // wait for the host's contents first
        }
        String s = Profile.packToString(c);
        if (s != null && !s.equals(clientSig)) {
            clientSig = s;
            Net.send(new Msg(Proto.CONTAINER_DATA).str(c).str(s).bool(false));
        }
    }

    private static String hostPackString(String code) {
        String s = Profile.packToString(code);
        if (s == null) {
            // boxes are read from disk the first time they are opened
            for (int i = 0; i < MyPanel.boxcode.length; i++) {
                if (code.equals(MyPanel.boxcode[i]) && MyPanel.boxexist[i]) {
                    try {
                        MyPanel.readbox(i);
                    } catch (Throwable t) {
                        // ignore
                    }
                    s = Profile.packToString(code);
                    break;
                }
            }
        }
        return s;
    }

    private static void hostContainerTick() {
        String c = hostOpen;
        if (c == null) {
            return;
        }
        String s = Profile.packToString(c);
        if (s != null && !s.equals(hostSig)) {
            hostSig = s;
            Net.send(new Msg(Proto.CONTAINER_DATA).str(c).str(s).bool(false));
        }
    }

    // ------------------------------------------------------------------ messages

    static void handle(Msg m) {
        switch (m.type) {
            case Proto.MAPACK: {
                int size = m.i32();
                byte[] z = m.blob();
                if (!Net.isClient()) {
                    return;
                }
                try {
                    DataInputStream in = new DataInputStream(new ByteArrayInputStream(ArrayCodec.inflate(z, size)));
                    int n = in.readInt();
                    List<G> l = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        G g = new G();
                        g.x = in.readInt();
                        g.y = in.readInt();
                        g.kind = in.readInt();
                        g.always = in.readBoolean();
                        g.item = in.readUTF();
                        l.add(g);
                    }
                    pendingList = l;
                    if (applied == null) {
                        applied = l;
                    }
                } catch (IOException e) {
                    Log.error("MAPACK", e);
                }
                break;
            }
            case Proto.CONTAINER_REQ: {
                String code = m.str();
                if (!Net.isHost()) {
                    return;
                }
                Coop.runSim(() -> {
                    hostOpen = code;
                    String s = hostPackString(code);
                    hostSig = s;
                    Net.send(new Msg(Proto.CONTAINER_DATA).str(code).str(s == null ? "" : s).bool(false));
                });
                break;
            }
            case Proto.CONTAINER_DATA: {
                String code = m.str();
                String data = m.str();
                boolean closed = m.bool();
                Coop.runSim(() -> {
                    if (Net.isHost()) {
                        if (!data.isEmpty()) {
                            Profile.packFromString(code, data);
                            hostSig = data;
                        }
                        if (closed && code.equals(hostOpen)) {
                            hostOpen = null;
                        }
                    } else {
                        if (!data.isEmpty()) {
                            Profile.packFromString(code, data);
                        }
                        if (code.equals(clientOpen)) {
                            String s = Profile.packToString(code);
                            clientSig = s == null ? "" : s;
                        }
                    }
                });
                break;
            }
            default:
                break;
        }
    }

    /** WORLD_EVENT sub-types handled here */
    static void handleEvent(int ev, Msg m) {
        switch (ev) {
            case EV_TAKE: {
                int x = m.i32();
                int y = m.i32();
                int kind = m.i32();
                String item = m.str();
                if (Net.isHost()) {
                    Coop.runSim(() -> hostTake(x, y, kind, item));
                }
                break;
            }
            case EV_DROPITEM: {
                int x = m.i32();
                int y = m.i32();
                int kind = m.i32();
                boolean always = m.bool();
                String item = m.str();
                if (Net.isHost()) {
                    Coop.runSim(() -> hostDrop(x, y, kind, always, item));
                }
                break;
            }
            case EV_CASE_OPEN: {
                int x = m.i32();
                if (Net.isHost()) {
                    Coop.runSim(() -> {
                        try {
                            if (MyPanel.ObjectCase.casekind[x] == 0) {
                                MyPanel.ObjectCase.createbox(x);
                            }
                        } catch (Throwable t) {
                            Log.error("createbox fuer Client", t);
                        }
                        WorldSync.hostTick();
                        Net.send(new Msg(Proto.WORLD_EVENT).u8(EV_CASE_READY).i32(x));
                    });
                }
                break;
            }
            case EV_CASE_READY: {
                int x = m.i32();
                // the next client tick applies the new box state first, then opens it again
                reopen = () -> {
                    waitingCase = -1;
                    Coop.panel.caseDialog(x);
                };
                break;
            }
            case EV_CORPSE_OPEN: {
                int x = m.i32();
                if (Net.isHost()) {
                    Coop.runSim(() -> {
                        try {
                            if (MyPanel.tcode[x] == null) {
                                MyPanel.Zombierespawn.createzombiepack(x);
                            }
                            String code = MyPanel.tcode[x];
                            String s = code == null ? null : Profile.packToString(code);
                            Net.send(new Msg(Proto.WORLD_EVENT).u8(EV_CORPSE_READY).i32(x).str(code == null ? "" : code)
                                    .str(s == null ? "" : s));
                        } catch (Throwable t) {
                            Log.error("createzombiepack fuer Client", t);
                        }
                    });
                }
                break;
            }
            case EV_CORPSE_READY: {
                int x = m.i32();
                String code = m.str();
                String data = m.str();
                reopen = () -> {
                    waitingCorpse = -1;
                    if (!code.isEmpty()) {
                        MyPanel.tcode[x] = code;
                        if (!data.isEmpty()) {
                            Profile.packFromString(code, data);
                        }
                        Coop.panel.terroristDialog(x);
                    }
                };
                break;
            }
            case EV_DROP_OPEN: {
                int x = m.i32();
                if (Net.isHost()) {
                    Coop.runSim(() -> {
                        try {
                            if (MyPanel.dropcode[x] == null) {
                                MyPanel.MapThing.createdrop(x);
                            }
                        } catch (Throwable t) {
                            Log.error("createdrop fuer Client", t);
                        }
                        WorldSync.hostTick();
                        String code = MyPanel.dropcode[x];
                        String s = code == null ? null : Profile.packToString(code);
                        Net.send(new Msg(Proto.WORLD_EVENT).u8(EV_DROP_READY).i32(x).str(code == null ? "" : code)
                                .str(s == null ? "" : s));
                    });
                }
                break;
            }
            case EV_DROP_READY: {
                int x = m.i32();
                String code = m.str();
                String data = m.str();
                reopen = () -> {
                    waitingDrop = -1;
                    if (!code.isEmpty()) {
                        MyPanel.dropcode[x] = code;
                        if (!data.isEmpty()) {
                            Profile.packFromString(code, data);
                        }
                        Coop.panel.dropDialog(x);
                    }
                };
                break;
            }
            default:
                break;
        }
    }

    static volatile int waitingCase = -1;
    static volatile int waitingCorpse = -1;
    static volatile int waitingDrop = -1;

    static void reset() {
        lastMapackHash = Long.MIN_VALUE;
        pendingList = null;
        applied = null;
        pendingTakes.clear();
        pendingDrops.clear();
        clientOpen = null;
        clientSig = null;
        hostOpen = null;
        hostSig = null;
        reopen = null;
        waitingCase = -1;
        waitingCorpse = -1;
        waitingDrop = -1;
    }

    // ------------------------------------------------------------------ client hooks

    /** caseDialog(x) on the client: an unopened piece of furniture is opened by the host (it rolls the loot). */
    public static boolean clientCaseDialog(int x) {
        if (!Net.isClient() || !Coop.playing() || MyPanel.ObjectCase == null) {
            return false;
        }
        if (MyPanel.ObjectCase.casekind[x] != 0) {
            return false;
        }
        if (waitingCase != x) {
            waitingCase = x;
            Net.send(new Msg(Proto.WORLD_EVENT).u8(EV_CASE_OPEN).i32(x));
        }
        return true;
    }

    public static boolean clientTerroristDialog(int x) {
        if (!Net.isClient() || !Coop.playing() || MyPanel.tcode[x] != null) {
            return false;
        }
        if (waitingCorpse != x) {
            waitingCorpse = x;
            Net.send(new Msg(Proto.WORLD_EVENT).u8(EV_CORPSE_OPEN).i32(x));
        }
        return true;
    }

    public static boolean clientDropDialog(int x) {
        if (!Net.isClient() || !Coop.playing() || MyPanel.dropcode[x] != null) {
            return false;
        }
        if (waitingDrop != x) {
            waitingDrop = x;
            Net.send(new Msg(Proto.WORLD_EVENT).u8(EV_DROP_OPEN).i32(x));
        }
        return true;
    }
}
