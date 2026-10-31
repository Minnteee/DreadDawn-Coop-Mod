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
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * World objects: everything players build, destroy, open or move.
 *
 * Host -> client: for every "system" (walls, roofs, bonfires, boxes, trees, crops, doors, cars ...)
 * the complete state is sent whenever it changes (checked 4x per second).
 * Client -> host: the client compares its world with the last state it got from the host; things
 * it added (built) or removed (chopped, harvested, demolished) are sent to the host as edits.
 *
 * Ground items and container contents are handled by ItemSync (items differ per machine).
 */
public final class WorldSync {
    private WorldSync() {}

    static final int EV_STATE = 1;
    static final int EV_ADD = 2;
    static final int EV_REMOVE = 3;
    static final int EV_DOOR = 4;
    static final int EV_KEYS = 5;

    /** One mirrored group of arrays. */
    static final class Sys {
        final int id;
        final String name;
        final Supplier<Object> owner;
        final Class<?> cls;
        String countName;
        String existName;
        final List<String> fields = new ArrayList<>();
        final List<Integer> axis = new ArrayList<>();
        String gridName;
        String kindName;
        String codeName;
        String remover;
        boolean removerHasFlag;
        boolean clientEdits;
        boolean clientRemoveOnly;
        int post; // 0 none, 1 planted (walls/roofs), 2 nearwalline (doors/trees)

        // resolved
        final Map<String, Field> f = new HashMap<>();
        long lastHash = Long.MIN_VALUE;
        // client: element keys of the last applied host state
        List<String> appliedKeys;
        volatile byte[] pending;

        Sys(int id, String name, Class<?> cls, Supplier<Object> owner) {
            this.id = id;
            this.name = name;
            this.cls = cls;
            this.owner = owner;
        }

        Sys count(String c) {
            countName = c;
            return this;
        }

        Sys exist(String e) {
            existName = e;
            return this;
        }

        /** field whose LAST index is the element (e.g. wall[2][n]) */
        Sys col(String... names) {
            for (String n : names) {
                fields.add(n);
                axis.add(1);
            }
            return this;
        }

        /** field whose FIRST index is the element (e.g. wallkind[n], leaves[n][a][b]) */
        Sys row(String... names) {
            for (String n : names) {
                fields.add(n);
                axis.add(0);
            }
            return this;
        }

        Sys grid(String g) {
            gridName = g;
            return this;
        }

        Sys kind(String k) {
            kindName = k;
            return this;
        }

        Sys code(String c) {
            codeName = c;
            return this;
        }

        Sys remover(String m, boolean flag) {
            remover = m;
            removerHasFlag = flag;
            return this;
        }

        Sys edits(boolean removeOnly) {
            clientEdits = true;
            clientRemoveOnly = removeOnly;
            return this;
        }

        Sys post(int p) {
            post = p;
            return this;
        }

        Object own() {
            return owner.get();
        }

        Object get(String name) throws Exception {
            Field fl = f.get(name);
            return fl.get(Modifier.isStatic(fl.getModifiers()) ? null : own());
        }

        void set(String name, Object v) throws Exception {
            Field fl = f.get(name);
            fl.set(Modifier.isStatic(fl.getModifiers()) ? null : own(), v);
        }

        int size() throws Exception {
            if (countName != null) {
                return ((Number) get(countName)).intValue();
            }
            return Array.getLength(get(existName != null ? existName : fields.get(0)));
        }

        boolean present(int i) throws Exception {
            if (existName != null) {
                return ((boolean[]) get(existName))[i];
            }
            return true;
        }
    }

    private static final List<Sys> systems = new ArrayList<>();
    private static boolean resolved;

    private static Sys sys(String name, Class<?> cls, Supplier<Object> owner) {
        Sys s = new Sys(systems.size(), name, cls, owner);
        systems.add(s);
        return s;
    }

    static {
        Class<?> mp = MyPanel.class;
        Supplier<Object> none = () -> null;
        sys("wall", mp, none).count("wallnuming").col("wall").row("wallkind").grid("wallheal").kind("wallkind")
                .remover("removewall", false).edits(false).post(1);
        sys("roof", mp, none).count("roofnuming").col("roof").row("roofkind").grid("roofheal").kind("roofkind")
                .remover("removeroof", false).edits(false).post(1);
        sys("torch", mp, none).count("torchnuming").col("torch").row("torchkind").kind("torchkind")
                .remover("removetorch", false).edits(false).post(1);
        sys("sleepingbag", mp, none).count("sleepingbagnuming").col("sleepingbag").row("sleepingbagclose")
                .remover("removesleepingbag", false).edits(false);
        sys("bonfire", mp, none).exist("bonfirexist").col("bonfire")
                .row("bonfirexist", "bonfirekind", "bonfirecode", "bonfiretime", "bonfirestartime").kind("bonfirekind")
                .code("bonfirecode").edits(false);
        sys("box", mp, none).exist("boxexist").col("box")
                .row("boxexist", "boxread", "boxcode", "boxkind", "boxcaseid", "boxlocked", "boxcreatetime").kind("boxkind")
                .code("boxcode").edits(false);
        sys("drop", mp, none).count("dropnuming").col("drop").row("dropkind", "dropcode");
        sys("tree", mp, none).count("treenuming").col("tree").row("treesize", "treekind", "treeheal", "treeang", "treebug")
                .kind("treekind").remover("treeremove", false).edits(true).post(2);
        sys("talltree", mp, none).count("talltreenuming").col("talltree")
                .row("talltreesize", "talltreekind", "talltreeheal", "talltreeang", "talltreeleafkind", "talltreesmallbird")
                .kind("talltreekind").remover("talltreeremove", false).edits(true).post(2);
        sys("crop", mp, none).count("cropnuming").col("crop").row("cropkind", "cropsize", "cropwater", "cropfood", "crop_growth")
                .kind("cropkind").remover("removecrop", false).edits(false);
        sys("collect", mp, none).count("collectnuming").col("collect").row("collectlastime", "collecthave")
                .remover("removecollect", false).edits(false);
        sys("barrel", mp, none).count("barrelnuming").col("barrel").row("barrelkind");
        sys("nail", mp, none).count("nailnuming").col("nail").row("nailheal", "nailkind").kind("nailkind")
                .remover("removenail", false).edits(false);
        sys("macgun", mp, none).count("macgunuming").col("macgun", "macgunint")
                .row("macgunkind", "macgunheal", "macgunrange", "aimacgun").kind("macgunkind")
                .remover("clearmacgun", true).edits(false);
        sys("table", mp, none).count("tablenuming").col("table", "tableint").row("tableheal", "tablekind").kind("tablekind")
                .remover("cleartable", false).edits(false);
        sys("blade", mp, none).count("bladenuming").col("blade").row("bladeheal", "bladekind").kind("bladekind")
                .remover("clearblade", false).edits(false);
        sys("fireshooter", mp, none).count("fireshooternuming").col("fireshooter", "fireshooterINT")
                .row("fireshootertime", "fireshooternumber", "fireshooterkind").kind("fireshooterkind")
                .remover("removefireshooter", false).edits(false);
        sys("cock", mp, none).col("doucock").row("cockheal", "cockind", "angcock", "cockteamate", "cockinjury");
        // cars and skateboards: VehicleSync (smooth; the own car / board stays with its driver)
        Class<?> other;
        Class<?> objectCase;
        try {
            other = Class.forName("Game.Other");
            objectCase = Class.forName("Game.ObjectCase");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
        // things built with the crafting menu: traps, barricades, fences, chicken traps, field blocks
        Class<?> craft;
        try {
            craft = Class.forName("Game.Craft");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
        sys("hind", mp, none).count("hindnuming").col("hind").row("hindkind").kind("hindkind")
                .remover("removehinder", false).edits(false).post(1);
        sys("mine", craft, () -> MyPanel.Craft).count("minenuming").col("mine").row("minekind").kind("minekind")
                .remover("removemine", false).edits(false);
        sys("log", craft, () -> MyPanel.Craft).count("lognuming").col("log").row("logdirection", "logfallang")
                .remover("removelog", false).edits(false);
        sys("tallwall", craft, () -> MyPanel.Craft).count("tallwallnuming").col("tallwall")
                .row("tallwallheal", "tallwallhealed", "tallwallpiece", "tallwallkind").kind("tallwallkind")
                .remover("tallwallremover", false).edits(false).post(1);
        // key on the material already added, so adding material is sent as a change
        sys("wall_build", craft, () -> MyPanel.Craft).count("wall_buildnuming").col("wall_build")
                .row("wall_buildkind", "wall_buildneed", "wall_buildhave").kind("wall_buildhave")
                .remover("wall_buildremover", false).edits(false).post(4);
        sys("cocktrap", other, () -> MyPanel.Other).count("cocktrapnuming").col("cocktrap").row("cocktrap_get")
                .remover("removecocktrap", false).edits(false);
        sys("farmblock", other, () -> MyPanel.Other).count("farmblocknuming").col("farmblock")
                .remover("removefarmblock", false).edits(false);
        sys("door", other, () -> MyPanel.Other).count("doornuming").col("door")
                .row("doorkind", "doorlength", "doorheight", "doorang", "doorangstart", "dooropen", "doorkey", "doorother", "doorheal")
                .post(3);
        sys("case", objectCase, () -> MyPanel.ObjectCase).row("casekind", "caseboxid");
        // locked exits / areas (the guest's unlocks go to the host through GuestEdits)
        sys("area", mp, none).count("areanuminging").row("areanameing", "areaiding", "areakinding", "areamoneying");
        // story keys taken / used (shared by both players)
        Class<?> keyCls;
        try {
            keyCls = Class.forName("Game.Key");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
        sys("key", keyCls, () -> MyPanel.Key).count("keytakelistnuming").row("keytakelist", "keytaked");
        // power lines: switched / blown up on the host
        sys("power", other, () -> MyPanel.Other).count("powernuming")
                .row("power", "powerareanuming", "powerswitchnum", "powerswitch").post(2);
        // vents zombies came out of
        sys("pipetop", other, () -> MyPanel.Other).count("pipetopnuming").col("pipetop").row("pipetopcreate", "pipetopshow");
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        for (Sys s : systems) {
            List<String> all = new ArrayList<>(s.fields);
            if (s.countName != null) {
                all.add(s.countName);
            }
            if (s.existName != null) {
                all.add(s.existName);
            }
            if (s.gridName != null) {
                all.add(s.gridName);
            }
            for (String n : all) {
                try {
                    Field fl = findField(s.cls, n);
                    fl.setAccessible(true);
                    s.f.put(n, fl);
                } catch (Exception e) {
                    Log.info("WorldSync: field " + s.name + "." + n + " missing - system skipped");
                    s.f.clear();
                    break;
                }
            }
        }
    }

    static Field findField(Class<?> c, String n) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                return k.getDeclaredField(n);
            } catch (NoSuchFieldException ignored) {
                // super class
            }
        }
        throw new NoSuchFieldException(n);
    }

    private static boolean ok(Sys s) {
        return !s.f.isEmpty() && s.own() != null || (!s.f.isEmpty() && s.cls == MyPanel.class);
    }

    // ------------------------------------------------------------------ hashing / encoding

    private static long hash(Sys s) throws Exception {
        long h = 1469598103934665603L;
        if (s.countName != null) {
            h = h * 31 + ((Number) s.get(s.countName)).intValue();
        }
        for (String n : s.fields) {
            h = h * 31 + deepHash(s.get(n));
        }
        if (s.gridName != null) {
            h = h * 31 + deepHash(s.get(s.gridName));
        }
        return h;
    }

    private static int deepHash(Object a) {
        if (a == null) {
            return 0;
        }
        if (a instanceof int[]) {
            return java.util.Arrays.hashCode((int[]) a);
        }
        if (a instanceof double[]) {
            return java.util.Arrays.hashCode((double[]) a);
        }
        if (a instanceof byte[]) {
            return java.util.Arrays.hashCode((byte[]) a);
        }
        if (a instanceof boolean[]) {
            return java.util.Arrays.hashCode((boolean[]) a);
        }
        if (a instanceof float[]) {
            return java.util.Arrays.hashCode((float[]) a);
        }
        if (a instanceof short[]) {
            return java.util.Arrays.hashCode((short[]) a);
        }
        if (a instanceof Object[]) {
            int h = 1;
            for (Object o : (Object[]) a) {
                h = 31 * h + deepHash(o);
            }
            return h;
        }
        return a.hashCode();
    }

    private static byte[] encode(Sys s) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream(4096);
        DataOutputStream o = new DataOutputStream(b);
        o.writeInt(s.countName != null ? ((Number) s.get(s.countName)).intValue() : -1);
        for (String n : s.fields) {
            ArrayCodec.write(o, s.get(n));
        }
        if (s.gridName != null) {
            ArrayCodec.write(o, s.get(s.gridName));
        }
        o.flush();
        return b.toByteArray();
    }

    private static void decode(Sys s, byte[] data) throws Exception {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        int count = in.readInt();
        for (String n : s.fields) {
            Object cur = s.get(n);
            Object v = ArrayCodec.read(in, cur);
            if (v != cur) {
                s.set(n, v);
            }
        }
        if (s.gridName != null) {
            Object cur = s.get(s.gridName);
            Object v = ArrayCodec.read(in, cur);
            if (v != cur) {
                s.set(s.gridName, v);
            }
        }
        if (s.countName != null) {
            s.set(s.countName, count);
        }
    }

    /** element key: rounded position (+kind); used to find what the client added/removed */
    private static String key(Sys s, int i) throws Exception {
        Object first = s.get(s.fields.get(0));
        StringBuilder k = new StringBuilder();
        if (s.axis.get(0) == 1) {
            int dims = Array.getLength(first);
            for (int d = 0; d < Math.min(2, dims); d++) {
                k.append(Math.round(((Number) Array.get(Array.get(first, d), i)).doubleValue())).append(',');
            }
        } else {
            k.append(i).append(',');
        }
        if (s.kindName != null) {
            k.append(Array.get(s.get(s.kindName), i));
        }
        return k.toString();
    }

    private static List<String> keys(Sys s) throws Exception {
        List<String> out = new ArrayList<>();
        int n = s.size();
        for (int i = 0; i < n; i++) {
            if (s.present(i)) {
                out.add(key(s, i));
            } else {
                out.add(null);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ host

    private static boolean dirtyAll = true;

    /** Host worker thread, 4x per second. */
    public static void hostTick() {
        resolve();
        for (Sys s : systems) {
            if (!ok(s)) {
                continue;
            }
            try {
                long h = hash(s);
                if (h == s.lastHash && !dirtyAll) {
                    continue;
                }
                s.lastHash = h;
                byte[] raw = encode(s);
                byte[] z = ArrayCodec.deflate(raw);
                Net.send(new Msg(Proto.WORLD_EVENT, z.length + 16).u8(EV_STATE).u8(s.id).i32(raw.length).blob(z));
            } catch (Throwable t) {
                Log.error("WorldSync " + s.name, t);
            }
        }
        dirtyAll = false;
        ItemSync.hostTick();
    }

    // ------------------------------------------------------------------ client

    private static long lastClientCheck;

    /** Client worker thread, end of every simulation frame. */
    public static void clientTick() {
        resolve();
        long now = System.currentTimeMillis();
        boolean anyPending = false;
        for (Sys s : systems) {
            if (s.pending != null) {
                anyPending = true;
                break;
            }
        }
        if (!anyPending && now - lastClientCheck < 150) {
            ItemSync.clientTick(false);
            return;
        }
        lastClientCheck = now;
        // 1) what did the local player change since the last host state? -> tell the host
        for (Sys s : systems) {
            if (!s.clientEdits || s.appliedKeys == null || !ok(s)) {
                continue;
            }
            try {
                sendClientEdits(s);
            } catch (Throwable t) {
                Log.error("WorldSync edits " + s.name, t);
            }
        }
        try {
            GuestEdits.clientTick();
        } catch (Throwable t) {
            Log.error("GuestEdits", t);
        }
        ItemSync.clientTick(true);
        // 2) take the newest host state
        for (Sys s : systems) {
            byte[] p = s.pending;
            if (p == null || !ok(s)) {
                continue;
            }
            s.pending = null;
            try {
                Object[] before = snapshotStructure(s);
                decode(s, p);
                s.appliedKeys = keys(s);
                afterApply(s, before);
                if (s.name.equals("collect")) {
                    LifeSync.collectApplied();
                }
                GuestEdits.applied(s.name);
            } catch (Throwable t) {
                Log.error("WorldSync apply " + s.name, t);
            }
        }
    }

    private static Object[] snapshotStructure(Sys s) throws Exception {
        if (s.post != 3) {
            return null;
        }
        Object open = s.get("dooropen");
        Object key = s.get("doorkey");
        return new Object[] {s.get("doornuming"), ((boolean[]) open).clone(), ((int[]) key).clone()};
    }

    private static void afterApply(Sys s, Object[] before) {
        try {
            switch (s.post) {
                case 1:
                    MyPanel.planted = true;
                    break;
                case 2:
                    nearwalline();
                    break;
                case 4:
                    MyPanel.Craft.getwallpoint();
                    MyPanel.planted = true;
                    break;
                case 3: {
                    boolean changed = before == null || !before[0].equals(s.get("doornuming"))
                            || !java.util.Arrays.equals((boolean[]) before[1], (boolean[]) s.get("dooropen"))
                            || !java.util.Arrays.equals((int[]) before[2], (int[]) s.get("doorkey"));
                    if (changed) {
                        nearwalline();
                    }
                    break;
                }
                default:
                    break;
            }
        } catch (Throwable t) {
            Log.error("afterApply " + s.name, t);
        }
    }

    private static void nearwalline() {
        try {
            Method m = MyPanel.class.getDeclaredMethod("nearwalline");
            m.setAccessible(true);
            m.invoke(Modifier.isStatic(m.getModifiers()) ? null : Coop.panel);
        } catch (Throwable t) {
            MyPanel.planted = true;
        }
    }

    private static void sendClientEdits(Sys s) throws Exception {
        List<String> now = keys(s);
        Map<String, Integer> before = new HashMap<>();
        for (String k : s.appliedKeys) {
            if (k != null) {
                before.merge(k, 1, Integer::sum);
            }
        }
        Map<String, Integer> cur = new HashMap<>();
        for (int i = 0; i < now.size(); i++) {
            String k = now.get(i);
            if (k == null) {
                continue;
            }
            cur.merge(k, 1, Integer::sum);
        }
        boolean changed = false;
        // removed by the client
        for (Map.Entry<String, Integer> e : before.entrySet()) {
            int have = cur.getOrDefault(e.getKey(), 0);
            for (int r = have; r < e.getValue(); r++) {
                Net.send(new Msg(Proto.WORLD_EVENT).u8(EV_REMOVE).u8(s.id).str(e.getKey()));
                changed = true;
                Log.info("client removes " + s.name + " " + e.getKey());
            }
        }
        // added by the client
        if (!s.clientRemoveOnly) {
            Map<String, Integer> seen = new HashMap<>();
            for (int i = 0; i < now.size(); i++) {
                String k = now.get(i);
                if (k == null) {
                    continue;
                }
                int idx = seen.merge(k, 1, Integer::sum);
                if (idx > before.getOrDefault(k, 0)) {
                    Net.send(encodeAdd(s, i));
                    changed = true;
                    Log.info("client builds " + s.name + " " + k);
                }
            }
        }
        if (changed) {
            s.appliedKeys = now;
        }
    }

    private static Msg encodeAdd(Sys s, int i) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(b);
        for (int f = 0; f < s.fields.size(); f++) {
            Object arr = s.get(s.fields.get(f));
            if (s.axis.get(f) == 1) {
                int dims = Array.getLength(arr);
                o.writeInt(dims);
                for (int d = 0; d < dims; d++) {
                    ArrayCodec.writeScalar(o, Array.get(Array.get(arr, d), i));
                }
            } else {
                Object el = Array.get(arr, i);
                o.writeInt(-1);
                if (el != null && el.getClass().isArray()) {
                    ArrayCodec.write(o, el);
                } else {
                    o.writeBoolean(el != null);
                    if (el != null) {
                        ArrayCodec.writeScalar(o, el);
                    }
                }
            }
        }
        int[] cell = gridCell(s, i);
        Object grid = s.gridName != null ? s.get(s.gridName) : null;
        o.writeBoolean(grid != null && cell != null);
        if (grid != null && cell != null) {
            ArrayCodec.writeScalar(o, Array.get(Array.get(grid, cell[0]), cell[1]));
        }
        String code = s.codeName != null ? (String) Array.get(s.get(s.codeName), i) : null;
        String pack = code != null ? Profile.packToString(code) : null;
        o.writeBoolean(pack != null);
        if (pack != null) {
            o.writeUTF(pack);
        }
        o.flush();
        return new Msg(Proto.WORLD_EVENT).u8(EV_ADD).u8(s.id).blob(b.toByteArray());
    }

    private static int[] gridCell(Sys s, int i) throws Exception {
        if (s.gridName == null) {
            return null;
        }
        Object pos = s.get(s.fields.get(0));
        double x = ((Number) Array.get(Array.get(pos, 0), i)).doubleValue();
        double y = ((Number) Array.get(Array.get(pos, 1), i)).doubleValue();
        int cs = MyPanel.sizemp[2];
        Object grid = s.get(s.gridName);
        int gx = (int) (x / cs);
        int gy = (int) (y / cs);
        if (gx < 0 || gy < 0 || gx >= Array.getLength(grid) || gy >= Array.getLength(Array.get(grid, gx))) {
            return null;
        }
        return new int[] {gx, gy};
    }

    // ------------------------------------------------------------------ messages

    public static void handle(Msg m) {
        if (m.type == Proto.MAPACK || m.type == Proto.CONTAINER_REQ || m.type == Proto.CONTAINER_DATA) {
            ItemSync.handle(m);
            return;
        }
        int ev = m.u8();
        switch (ev) {
            case EV_STATE: {
                int id = m.u8();
                int size = m.i32();
                byte[] z = m.blob();
                if (id < systems.size() && Net.isClient()) {
                    try {
                        systems.get(id).pending = ArrayCodec.inflate(z, size);
                    } catch (IOException e) {
                        Log.error("WorldSync inflate", e);
                    }
                }
                break;
            }
            case EV_ADD: {
                int id = m.u8();
                byte[] data = m.blob();
                if (id < systems.size() && Net.isHost()) {
                    Sys s = systems.get(id);
                    Coop.runSim(() -> hostAdd(s, data));
                }
                break;
            }
            case EV_REMOVE: {
                int id = m.u8();
                String key = m.str();
                if (id < systems.size() && Net.isHost()) {
                    Sys s = systems.get(id);
                    Coop.runSim(() -> hostRemove(s, key));
                }
                break;
            }
            case EV_DOOR: {
                double hx = m.f64();
                double hy = m.f64();
                Coop.runSim(() -> hostToggleDoor(hx, hy));
                break;
            }
            default:
                ItemSync.handleEvent(ev, m);
        }
    }

    private static void hostAdd(Sys s, byte[] data) {
        resolve();
        if (!ok(s)) {
            return;
        }
        try {
            int i;
            if (s.countName != null) {
                i = s.size();
                Object first = s.get(s.fields.get(0));
                int cap = s.axis.get(0) == 1 ? Array.getLength(Array.get(first, 0)) : Array.getLength(first);
                if (i >= cap) {
                    return;
                }
            } else {
                boolean[] ex = (boolean[]) s.get(s.existName);
                i = -1;
                for (int k = 0; k < ex.length; k++) {
                    if (!ex[k]) {
                        i = k;
                        break;
                    }
                }
                if (i < 0) {
                    return;
                }
            }
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            for (int f = 0; f < s.fields.size(); f++) {
                Object arr = s.get(s.fields.get(f));
                int dims = in.readInt();
                if (dims >= 0) {
                    for (int d = 0; d < dims; d++) {
                        Array.set(Array.get(arr, d), i, ArrayCodec.readScalar(in));
                    }
                } else {
                    Object el = Array.get(arr, i);
                    if (el != null && el.getClass().isArray()) {
                        Object v = ArrayCodec.read(in, el);
                        if (v != el) {
                            Array.set(arr, i, v);
                        }
                    } else if (in.readBoolean()) {
                        Array.set(arr, i, ArrayCodec.readScalar(in));
                    } else {
                        Array.set(arr, i, null);
                    }
                }
            }
            boolean hasGrid = in.readBoolean();
            Object gv = hasGrid ? ArrayCodec.readScalar(in) : null;
            boolean hasPack = in.readBoolean();
            String pack = hasPack ? in.readUTF() : null;
            if (s.countName != null) {
                s.set(s.countName, i + 1);
            }
            if (hasGrid) {
                int[] cell = gridCell(s, i);
                if (cell != null) {
                    Array.set(Array.get(s.get(s.gridName), cell[0]), cell[1], gv);
                }
            }
            if (pack != null && s.codeName != null) {
                String code = (String) Array.get(s.get(s.codeName), i);
                if (code != null) {
                    Profile.packFromString(code, pack);
                }
            }
            if (s.name.equals("bonfire")) {
                try {
                    Method m = MyPanel.class.getDeclaredMethod("getbonfiretime", int.class);
                    m.setAccessible(true);
                    m.invoke(Modifier.isStatic(m.getModifiers()) ? null : Coop.panel, i);
                } catch (Throwable t) {
                    // ignore
                }
            }
            afterApply(s, null);
            Log.info("Host: " + Net.peerName + " built " + s.name);
        } catch (Throwable t) {
            Log.error("hostAdd " + s.name, t);
        }
    }

    private static Method findRemover(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Method mm : c.getDeclaredMethods()) {
                if (mm.getName().equals(name) && mm.getParameterCount() >= 1 && mm.getParameterTypes()[0] == int.class) {
                    return mm;
                }
            }
        }
        return null;
    }

    private static void hostRemove(Sys s, String key) {
        resolve();
        if (!ok(s)) {
            return;
        }
        try {
            int n = s.size();
            int found = -1;
            for (int i = 0; i < n; i++) {
                if (s.present(i) && key.equals(key(s, i))) {
                    found = i;
                    break;
                }
            }
            if (found < 0) {
                return;
            }
            if (s.remover != null) {
                Method m = findRemover(s.cls, s.remover);
                if (m == null && s.cls != MyPanel.class) {
                    m = findRemover(MyPanel.class, s.remover);
                }
                if (m == null) {
                    throw new NoSuchMethodException(s.remover);
                }
                m.setAccessible(true);
                Object[] args = new Object[m.getParameterCount()];
                Class<?>[] pt = m.getParameterTypes();
                args[0] = found;
                for (int i = 1; i < args.length; i++) {
                    args[i] = pt[i] == boolean.class ? Boolean.FALSE : pt[i] == double.class ? (Object) 0.0 : (Object) 0;
                }
                Object self = null;
                if (!Modifier.isStatic(m.getModifiers())) {
                    self = MyPanel.class.isAssignableFrom(m.getDeclaringClass()) && s.cls == MyPanel.class ? Coop.panel : s.own();
                }
                m.invoke(self, args);
            } else if (s.existName != null) {
                ((boolean[]) s.get(s.existName))[found] = false;
            }
            afterApply(s, null);
            Log.info("Host: " + Net.peerName + " removed " + s.name);
        } catch (Throwable t) {
            Log.error("hostRemove " + s.name, t);
        }
    }

    // ------------------------------------------------------------------ doors

    /** Client hook after Other.opendoor(x): tell the host which door the player used. */
    public static void clientOpenedDoor(int x) {
        if (!Net.isClient() || !Coop.playing() || MyPanel.Other == null || StorySync.clientScenePlaying()) {
            return; // in a cutscene from the host the host's scene opens the doors itself
        }
        double[][] d = MyPanel.Other.door;
        Net.send(new Msg(Proto.WORLD_EVENT).u8(EV_DOOR).f64(d[0][x]).f64(d[1][x]));
    }

    private static void hostToggleDoor(double hx, double hy) {
        if (MyPanel.Other == null) {
            return;
        }
        double[][] d = MyPanel.Other.door;
        for (int x = 0; x < MyPanel.Other.doornuming; x++) {
            if (Math.abs(d[0][x] - hx) < 2 && Math.abs(d[1][x] - hy) < 2) {
                applyingRemote = true;
                try {
                    MyPanel.Other.opendoor(x);
                } catch (Throwable t) {
                    Log.error("opendoor", t);
                } finally {
                    applyingRemote = false;
                }
                return;
            }
        }
    }

    static volatile boolean applyingRemote;

    public static void reset() {
        dirtyAll = true;
        for (Sys s : systems) {
            s.lastHash = Long.MIN_VALUE;
            s.appliedKeys = null;
            s.pending = null;
        }
        ItemSync.reset();
    }
}
