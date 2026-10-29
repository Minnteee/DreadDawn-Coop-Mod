package coop.sync;

import Game.MyPanel;
import coop.Config;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The client's own character (inventory, equipment, health, level) is stored in the host's
 * save (Save\auto\coop\NAME.profile), so it is saved and reloaded together with the world.
 */
public final class Profile {
    private Profile() {}

    private static final int MAGIC = 0x44445046;
    private static final Map<String, byte[]> hostProfiles = new ConcurrentHashMap<>();
    private static long lastSend;

    // ------------------------------------------------------------------ in-memory pack (de)serialization

    /** Same text format the game uses for pack files ("size@slot,kind@item*stats@..."), unencrypted. */
    public static String packToString(String code) {
        if (code == null) {
            return null;
        }
        int num = MyPanel.gethumpacknameid(code);
        if (num < 0) {
            return null;
        }
        StringBuilder s = new StringBuilder();
        s.append(MyPanel.humanpackstocknuming[num]).append('@');
        for (int x = 0; x < MyPanel.humanpackstocknuming[num]; x++) {
            int kind = MyPanel.humanpackstockind[x][num];
            if (kind != 0) {
                s.append(x).append(',').append(kind).append('@');
                s.append(MyPanel.pack.saveobject(kind, MyPanel.humanpackid[x][num], false)).append('@');
            }
        }
        return s.toString();
    }

    /** Replaces the pack "code" with the given contents. Returns the pack index. */
    public static int packFromString(String code, String data) {
        MyPanel.clearpack(code);
        String[] parts = data.split("@");
        int num = MyPanel.packcreater(code, Integer.parseInt(parts[0]));
        int slot = -1;
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].isEmpty()) {
                continue;
            }
            if (slot < 0) {
                String[] h = parts[i].split(",");
                slot = Integer.parseInt(h[0]);
                MyPanel.humanpackstockind[slot][num] = Integer.parseInt(h[1]);
            } else {
                int id = MyPanel.pack.getobject(parts[i].split("\\*"), MyPanel.humanpackstockind[slot][num]);
                if (id > -1) {
                    MyPanel.humanpackid[slot][num] = id;
                } else {
                    MyPanel.humanpackstockind[slot][num] = 0;
                    MyPanel.humanpackid[slot][num] = 0;
                }
                slot = -1;
            }
        }
        return num;
    }

    // ------------------------------------------------------------------ client

    /** Client worker thread: send the character to the host every few seconds. */
    public static void clientTick() {
        long now = System.currentTimeMillis();
        if (now - lastSend < 5000 || DeathSync.localDead()) {
            return;
        }
        lastSend = now;
        try {
            Net.send(new Msg(Proto.PROFILE).str(Config.name).blob(capture()));
        } catch (Throwable t) {
            Log.error("Profile.capture", t);
        }
    }

    static byte[] capture() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(b);
        o.writeInt(MAGIC);
        o.writeInt(3);
        o.writeDouble(MyPanel.healplayer > 0 ? MyPanel.healplayer : MyPanel.ctheal[300]);
        o.writeDouble(MyPanel.ctheal[300]);
        o.writeDouble(MyPanel.kevlarplayer);
        o.writeDouble(MyPanel.ctkevlar[300]);
        o.writeFloat(MyPanel.cthunger[300]);
        o.writeDouble(MyPanel.ctthirst[300]);
        o.writeDouble(MyPanel.ctstrength[300]);
        o.writeInt(MyPanel.LV);
        o.writeInt(MyPanel.EXP);
        o.writeInt(MyPanel.skillpoint);
        o.writeInt(MyPanel.producepoint);
        o.writeInt(MyPanel.selection);
        String pack = packToString(MyPanel.ctcode[300]);
        o.writeUTF(pack == null ? "" : pack);
        o.writeInt(MyPanel.bodypackid.length);
        for (int i = 0; i < MyPanel.bodypackid.length; i++) {
            int kind = MyPanel.bodypackstockind[i];
            o.writeInt(kind);
            o.writeUTF(kind != 0 ? MyPanel.pack.saveobject(kind, MyPanel.bodypackid[i], false) : "");
        }
        o.writeInt(ownShape);
        // v3: what the map loader would otherwise take from the host's save
        o.writeInt(MyPanel.heal);
        o.writeInt(MyPanel.kevlar);
        o.writeInt(MyPanel.hungered);
        o.writeInt(MyPanel.thirsted);
        o.writeDouble(MyPanel.sleep);
        o.writeInt(MyPanel.NUM_OF_KILL);
        o.writeInt(MyPanel.NUM_OF_HIT);
        o.writeDouble(MyPanel.anglockwidth);
        o.writeInt(MyPanel.MapThing.gunlevel);
        o.writeInt(MyPanel.MapThing.meleelevel);
        writeInts(o, MyPanel.MapThing.bodylevel);
        writeInts(o, MyPanel.Craft.unlock_level);
        writeBools(o, MyPanel.ProduceView.produceaccess);
        writeBools(o, MyPanel.ProduceView.produce_crafted);
        o.flush();
        return b.toByteArray();
    }

    private static void writeInts(DataOutputStream o, int[] a) throws IOException {
        o.writeInt(a == null ? 0 : a.length);
        if (a != null) {
            for (int v : a) {
                o.writeInt(v);
            }
        }
    }

    private static void writeBools(DataOutputStream o, boolean[] a) throws IOException {
        o.writeInt(a == null ? 0 : a.length);
        if (a != null) {
            for (boolean v : a) {
                o.writeBoolean(v);
            }
        }
    }

    private static void readInts(DataInputStream in, int[] into) throws IOException {
        int n = in.readInt();
        for (int i = 0; i < n; i++) {
            int v = in.readInt();
            if (into != null && i < into.length) {
                into[i] = v;
            }
        }
    }

    private static void readBools(DataInputStream in, boolean[] into) throws IOException {
        int n = in.readInt();
        for (int i = 0; i < n; i++) {
            boolean v = in.readBoolean();
            if (into != null && i < into.length) {
                into[i] = v;
            }
        }
    }

    /** a new character: the values of a new single player game, not the host's upgrades */
    static void newCharacterValues() {
        try {
            MyPanel.heal = 80;
            MyPanel.kevlar = 80;
            MyPanel.hungered = 80;
            MyPanel.thirsted = 80;
            MyPanel.sleep = MyPanel.sleeped;
            MyPanel.NUM_OF_KILL = 0;
            MyPanel.NUM_OF_HIT = 0;
            MyPanel.anglockwidth = MyPanel.anglockwidthMAX;
            MyPanel.MapThing.gunlevel = 0;
            MyPanel.MapThing.meleelevel = 0;
            java.util.Arrays.fill(MyPanel.MapThing.bodylevel, 0);
            java.util.Arrays.fill(MyPanel.Craft.unlock_level, 0);
            for (int x = 0; x < MyPanel.ProduceView.producenum; x++) {
                MyPanel.ProduceView.produceaccess[x] = MyPanel.ProduceView.produce_skillpoint[x] == 0;
                MyPanel.ProduceView.produce_crafted[x] = false;
            }
        } catch (Throwable t) {
            Log.error("Startwerte", t);
        }
        refreshDerived();
    }

    /** recipes and the armour based values follow the loaded values */
    static void refreshDerived() {
        try {
            MyPanel.Craft.unlockcheck();
        } catch (Throwable t) {
            Log.error("unlockcheck", t);
        }
        try {
            MyPanel.Packview.getplayerstatistic();
        } catch (Throwable t) {
            Log.error("getplayerstatistic", t);
        }
    }

    /** the client's own character look (index into Bone.counterflesh), -1 = not chosen yet */
    public static volatile int ownShape = -1;

    /** a look different from the host's, picked once for a new co-op character */
    static int randomShape() {
        try {
            int n = MyPanel.Bone.counterflesh.length;
            int host = MyPanel.playershape;
            int s;
            do {
                s = (int) (Math.random() * n);
            } while (n > 1 && s == host);
            return s;
        } catch (Throwable t) {
            return 1;
        }
    }

    /** client: the own character captured right before a map change */
    static volatile byte[] travelCarry;

    /** Client, after the host world was loaded: our own character instead of the host's. */
    public static void clientApplyAfterLoad() {
        Path f = WorldTransfer.gamePath(WorldTransfer.COOP_DIR + "\\coop\\" + fileName(Config.name));
        byte[] data = null;
        try {
            if (Files.exists(f)) {
                data = Files.readAllBytes(f);
            }
        } catch (IOException e) {
            Log.error("Profil lesen", e);
        }
        String code = MyPanel.getcode();
        MyPanel.ctcode[300] = code;
        byte[] carry = travelCarry;
        travelCarry = null;
        if (data != null && carry != null) {
            data = carry; // own, newest state through a map change
        }
        if (data != null && apply(data, code)) {
            Log.info("Eigener Charakter geladen (" + data.length + " Bytes)");
        } else {
            starterKit(code);
            Log.info("Neuer Coop-Charakter mit Startausruestung");
        }
        MyPanel.selection = Math.max(0, Math.min(7, MyPanel.selection));
        refreshHand();
        lastSend = 0;
    }

    static boolean apply(byte[] data, String code) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            if (in.readInt() != MAGIC) {
                return false;
            }
            int version = in.readInt();
            double healplayer = in.readDouble();
            double heal = in.readDouble();
            double kevlarplayer = in.readDouble();
            double kevlar = in.readDouble();
            float hunger = in.readFloat();
            double thirst = in.readDouble();
            double strength = in.readDouble();
            int lv = in.readInt();
            int exp = in.readInt();
            int skill = in.readInt();
            int produce = in.readInt();
            int selection = in.readInt();
            String pack = in.readUTF();
            int nb = in.readInt();
            String[] body = new String[nb];
            int[] bodyKind = new int[nb];
            for (int i = 0; i < nb; i++) {
                bodyKind[i] = in.readInt();
                body[i] = in.readUTF();
            }
            ownShape = version >= 2 ? in.readInt() : -1;
            if (ownShape < 0) {
                ownShape = randomShape();
            }
            if (version >= 3) {
                MyPanel.heal = in.readInt();
                MyPanel.kevlar = in.readInt();
                MyPanel.hungered = in.readInt();
                MyPanel.thirsted = in.readInt();
                MyPanel.sleep = in.readDouble();
                MyPanel.NUM_OF_KILL = in.readInt();
                MyPanel.NUM_OF_HIT = in.readInt();
                MyPanel.anglockwidth = in.readDouble();
                MyPanel.MapThing.gunlevel = in.readInt();
                MyPanel.MapThing.meleelevel = in.readInt();
                readInts(in, MyPanel.MapThing.bodylevel);
                readInts(in, MyPanel.Craft.unlock_level);
                readBools(in, MyPanel.ProduceView.produceaccess);
                readBools(in, MyPanel.ProduceView.produce_crafted);
            }
            if (pack.isEmpty()) {
                MyPanel.packcreater(code, 24);
            } else {
                packFromString(code, pack);
            }
            for (int i = 0; i < Math.min(nb, MyPanel.bodypackid.length); i++) {
                if (bodyKind[i] != 0 && !body[i].isEmpty()) {
                    int id = MyPanel.pack.getobject(body[i].split("\\*"), bodyKind[i]);
                    MyPanel.bodypackstockind[i] = id > -1 ? bodyKind[i] : 0;
                    MyPanel.bodypackid[i] = Math.max(0, id);
                } else {
                    MyPanel.bodypackstockind[i] = 0;
                    MyPanel.bodypackid[i] = 0;
                }
            }
            heal = Math.max(heal, 1);
            MyPanel.ctheal[300] = heal;
            MyPanel.healplayer = Math.max(healplayer, heal);
            MyPanel.kevlarplayer = kevlarplayer;
            MyPanel.ctkevlar[300] = kevlar;
            MyPanel.cthunger[300] = hunger;
            MyPanel.ctthirst[300] = thirst;
            MyPanel.ctstrength[300] = strength;
            MyPanel.LV = lv;
            MyPanel.EXP = exp;
            MyPanel.skillpoint = skill;
            MyPanel.producepoint = produce;
            MyPanel.selection = selection;
            refreshDerived();
            return true;
        } catch (Throwable t) {
            Log.error("Profil anwenden", t);
            return false;
        }
    }

    /** basic kit for story / director mode: baseball bat, M-1911 + 24 rounds, flashlight, 2 bread, soda */
    static final int[][] BASIC_KIT = {{3006, 1}, {18, 1}, {6002, 1}, {1002, 24}, {2001, 2}, {2002, 1}};

    /**
     * What a new co-op character gets, like a new single player character of the host's game mode:
     * survival = one of the survival melee weapons + flashlight, L4D = the L4D loadout + first aid kit
     * + two magazines. In the story Jack starts empty-handed and gets his weapons through the story,
     * which only runs for the host, so the guest gets the basic kit there.
     */
    static int[][] kitForMode() {
        try {
            if (MyPanel.modeKIND == 2) {
                int[] list = {3009, 3010, 3011, 3012, 3020, 3028};
                if (MyPanel.SurvivalMode != null && MyPanel.SurvivalMode.humanweaponlist != null
                        && MyPanel.SurvivalMode.humanweaponlist[0] > 0) {
                    int n = 0;
                    while (n < MyPanel.SurvivalMode.humanweaponlist.length && MyPanel.SurvivalMode.humanweaponlist[n] > 0) {
                        n++;
                    }
                    list = java.util.Arrays.copyOf(MyPanel.SurvivalMode.humanweaponlist, n);
                }
                return new int[][] {{list[(int) (Math.random() * list.length)], 1}, {6002, 1}};
            }
            if (MyPanel.modeKIND == 3 && MyPanel.L4DMode != null) {
                Game.L4DMode l = MyPanel.L4DMode;
                java.util.List<int[]> kit = new java.util.ArrayList<>();
                for (int x = 0; x < l.human_item_list.length; x++) {
                    int pick = Math.max(0, Math.min(l.human_item_list[x].length - 1, l.human_item_now[x]));
                    kit.add(new int[] {l.human_item_list[x][pick], 1});
                }
                int gun = l.human_item_list[0][Math.max(0, Math.min(l.human_item_list[0].length - 1, l.human_item_now[0]))];
                kit.add(new int[] {2007, 1});
                kit.add(new int[] {MyPanel.pack.getammokind(gun), MyPanel.pack.getammobygun(gun) * 2});
                return kit.toArray(new int[0][]);
            }
        } catch (Throwable t) {
            Log.error("Startausruestung fuer Modus " + MyPanel.modeKIND, t);
        }
        return BASIC_KIT;
    }

    static void starterKit(String code) {
        int num = MyPanel.packcreater(code, 24);
        int stock = 0;
        int[][] items = kitForMode();
        Log.info("Startausruestung (Modus " + MyPanel.modeKIND + "): " + java.util.Arrays.deepToString(items));
        for (int[] it : items) {
            try {
                MyPanel.stuffcreater.unitynumcreater(it[0], it[1], stock++, num);
            } catch (Throwable t) {
                Log.error("Startausruestung " + it[0], t);
            }
        }
        for (int i = 0; i < MyPanel.bodypackid.length; i++) {
            MyPanel.bodypackstockind[i] = 0;
            MyPanel.bodypackid[i] = 0;
        }
        newCharacterValues(); // before the stats: they use the new maxima
        MyPanel.healplayer = MyPanel.heal;
        MyPanel.ctheal[300] = MyPanel.heal;
        MyPanel.cthunger[300] = MyPanel.heal;
        MyPanel.ctstrength[300] = MyPanel.heal;
        MyPanel.ctthirst[300] = MyPanel.thirsted;
        MyPanel.kevlarplayer = 0;
        MyPanel.ctkevlar[300] = 0;
        MyPanel.LV = 1;
        MyPanel.EXP = 0;
        MyPanel.selection = 0;
        ownShape = randomShape();
    }

    /** the held item follows the selected hotbar slot */
    static void refreshHand() {
        try {
            int id = MyPanel.gethumpacknameid(MyPanel.ctcode[300]);
            if (id > -1) {
                MyPanel.cthandlekind[300] = MyPanel.humanpackstockind[MyPanel.selection][id];
                MyPanel.cthandleid[300] = MyPanel.humanpackid[MyPanel.selection][id];
                MyPanel.pack.getstatistic(MyPanel.cthandlekind[300], MyPanel.cthandleid[300], 0);
                MyPanel.cthandlename[300] = (int) MyPanel.goodname[0];
            }
            MyPanel.Use.packexist();
        } catch (Throwable t) {
            Log.error("refreshHand", t);
        }
    }

    // ------------------------------------------------------------------ host

    public static void handle(Msg m) {
        String name = Config.clean(m.str());
        byte[] data = m.blob();
        if (Net.isHost()) {
            hostProfiles.put(name, data);
        }
    }

    /** Host: write the clients' characters into Save\auto\coop before the world is saved / sent. */
    public static void hostWriteProfiles() {
        if (!Net.isHost()) {
            return;
        }
        for (Map.Entry<String, byte[]> e : hostProfiles.entrySet()) {
            try {
                Path dir = WorldTransfer.gamePath("Save\\auto\\coop");
                Files.createDirectories(dir);
                Files.write(dir.resolve(fileName(e.getKey())), e.getValue());
                TeamSync.save(dir, fileName(e.getKey()).replace(".profile", ""));
            } catch (IOException ex) {
                Log.error("Profil schreiben", ex);
            }
        }
    }

    /** after a reload the saved characters are the valid ones; newer captures are from before the reload */
    public static void forgetHostProfiles() {
        hostProfiles.clear();
    }

    public static String fileName(String name) {
        return name.replaceAll("[^A-Za-z0-9_.-]", "_") + ".profile";
    }

    public static void onSaveGame() {
        if (Net.isHost() && Coop.inGame()) {
            hostWriteProfiles();
        }
    }
}
