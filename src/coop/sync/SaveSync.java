package coop.sync;

import Game.MyPanel;
import coop.Config;
import coop.Coop;
import coop.Log;
import coop.T;
import coop.net.Msg;
import coop.net.Net;
import coop.net.Proto;

/**
 * The guest can save the game for the host (useful while the host is dead): F5 or "Save" in the guest's
 * pause menu asks the host, and the host saves into the slot he loaded or saved last (otherwise only the
 * autosave). A dead host may also open his own save menu again.
 */
public final class SaveSync {
    private SaveSync() {}

    static final int S_REQUEST = 0;
    static final int S_DONE = 1;
    static final int S_REFUSED = 2;

    /** host: the save slot that is played right now (loaded or saved last), -1 = none known */
    private static volatile int slot = -1;
    private static volatile boolean cloud;
    private static long lastAsk;

    // ------------------------------------------------------------------ which slot is played

    /** head of new SaveStock(source, target, insave): every slot load and slot save copies a folder */
    public static void slotCopy(String source, String target, boolean insave) {
        if (source == null || target == null) {
            return;
        }
        String s = source.replace('/', '\\');
        String t = target.replace('/', '\\');
        if (t.equals("auto")) {
            // a slot, an autosave history entry or a new game is loaded into the live save
            if (s.startsWith("Stock\\")) {
                remember(id(s.substring(6)), false);
            } else if (s.startsWith("Cloud\\")) {
                remember(id(s.substring(6)), true);
            } else {
                remember(-1, false);
            }
        } else if (s.equals("auto")) {
            if (t.startsWith("Stock\\")) {
                remember(id(t.substring(6)), false);
            } else if (t.startsWith("Cloud\\")) {
                remember(id(t.substring(6)), true);
            }
        }
    }

    private static int id(String folder) {
        try {
            int h = folder.indexOf('#');
            return Integer.parseInt(h > 0 ? folder.substring(0, h) : folder);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static void remember(int id, boolean inCloud) {
        if (id == slot && inCloud == cloud) {
            return;
        }
        slot = id;
        cloud = inCloud;
        Config.lastSlot = id < 0 ? -1 : (inCloud ? 1000 + id : id);
        Config.save();
        Log.info("save slot in play: " + (id < 0 ? "none" : (inCloud ? "cloud " : "") + (id + 1)));
    }

    /** host start: "Continue" loads the live save without a slot copy, so the slot of the last session counts */
    public static void restoreFromConfig() {
        int v = Config.lastSlot;
        cloud = v >= 1000;
        slot = v < 0 ? -1 : v % 1000;
    }

    // ------------------------------------------------------------------ the guest asks

    /** client: F5 or the save menu -> ask the host to save */
    public static void clientAsk() {
        long now = System.currentTimeMillis();
        if (now - lastAsk < 1500) {
            return;
        }
        lastAsk = now;
        Net.send(new Msg(Proto.SAVE, 8).u8(S_REQUEST));
        Coop.message(T.s("The game is saved on the host ..."));
    }

    /** MyPanel.backfire head on the client: F5 asks the host instead of saving the own copy of the world */
    public static void clientF5(MyPanel p) {
        if (p.fast_f5_save && Net.isClient() && Coop.clientInWorld) {
            p.fast_f5_save = false;
            clientAsk();
        }
    }

    /** page4 reads "deathed" only to refuse the save menu: in co-op a dead / downed player may save */
    public static boolean menuDeathed() {
        return MyPanel.deathed && !(Coop.playing() && DeathSync.localDead() && !DeathSync.gameOver());
    }

    // ------------------------------------------------------------------ messages

    public static void handle(Msg m) {
        int kind = m.u8();
        if (kind == S_REQUEST && Net.isHost()) {
            Coop.runSim(SaveSync::hostSave);
        } else if (kind == S_DONE) {
            int s = m.i32();
            Coop.message(s >= 0 ? T.s("The host saved the game (slot {0}).", s + 1) : T.s("The host saved the game (autosave)."));
        } else if (kind == S_REFUSED) {
            Coop.message(T.s("The host cannot save right now (a mission or event is running)."));
        }
    }

    /** host worker thread: save like the host's own menu would */
    private static void hostSave() {
        if (!Coop.inGame() || Coop.panel == null) {
            return;
        }
        if (MyPanel.missionmodekind != -1 || MyPanel.gamekind >= 0 || DeathSync.gameOver()) {
            Net.send(new Msg(Proto.SAVE, 8).u8(S_REFUSED));
            return;
        }
        int s = slot;
        try {
            if (s >= 0) {
                new Game.SaveCheck();
                if (cloud) {
                    new Game.SaveCloudCheck();
                    Coop.panel.savestock_cloud(s, false);
                } else {
                    Coop.panel.savestock(s, false);
                }
            } else {
                new Game.SaveGame();
            }
        } catch (Throwable t) {
            Log.error("save for the guest", t);
            Net.send(new Msg(Proto.SAVE, 8).u8(S_REFUSED));
            return;
        }
        Net.send(new Msg(Proto.SAVE, 8).u8(S_DONE).i32(s));
        Coop.message(s >= 0 ? T.s("{0} saved the game (slot {1}).", Net.peerName, s + 1)
                : T.s("{0} saved the game (autosave).", Net.peerName));
        Log.info("saved for the guest, slot " + s);
    }
}
