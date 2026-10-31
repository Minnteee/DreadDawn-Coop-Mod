package coop.sync;

import Game.MyPanel;
import coop.Coop;
import coop.Log;
import coop.net.Msg;
import coop.net.Net;
import coop.net.Proto;
import coop.T;

/**
 * Everyday things the guest does that change the shared world:
 * - cooking at a bonfire and adding fuel (the host's bonfire burns and cooks);
 * - feed / water thrown by the guest (crops, chickens, putting out fire) lands on the host too;
 * - drinking from / filling at a rain collector takes the water on the host;
 * - sleeping: time only jumps when both players slept (within a minute of each other);
 * - minigames and the girlfriend scene (they switch to a private map) are not available in co-op.
 */
public final class LifeSync {
    private LifeSync() {}

    static final int L_BONFIRE_BURN = 1;
    static final int L_BONFIRE_TIME = 2;
    static final int L_FEED = 3;
    static final int L_COLLECT_TAKE = 4;
    static final int L_SLEEP = 5;

    private static final ThreadLocal<Boolean> applying = ThreadLocal.withInitial(() -> false);

    private static boolean guest() {
        return Net.isClient() && Coop.playing() && Coop.clientSimOff() && !applying.get();
    }

    private static int bonfireAt(int x, int y) {
        for (int i = 0; i < MyPanel.bonfirexist.length; i++) {
            if (MyPanel.bonfirexist[i] && Math.abs(MyPanel.bonfire[0][i] - x) < 3 && Math.abs(MyPanel.bonfire[1][i] - y) < 3) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ bonfires

    /** HEAD_CANCEL MyPanel.bonfireburn(I)V: the guest's cooking happens in the host's bonfire */
    public static boolean bonfireBurn(int i) {
        if (!guest()) {
            return false;
        }
        Net.send(new Msg(Proto.LIFE, 16).u8(L_BONFIRE_BURN).i32(MyPanel.bonfire[0][i]).i32(MyPanel.bonfire[1][i]));
        return true;
    }

    /** TAIL MyPanel.getbonfiretime(I)V: fuel the guest added keeps the host's fire burning */
    public static void bonfireTime(int i) {
        if (!guest()) {
            return;
        }
        Net.send(new Msg(Proto.LIFE, 24).u8(L_BONFIRE_TIME).i32(MyPanel.bonfire[0][i]).i32(MyPanel.bonfire[1][i])
                .i32(MyPanel.bonfirestartime[i]).i32(MyPanel.bonfiretime[i]));
    }

    // ------------------------------------------------------------------ feed and water

    /** HEAD MyPanel.cockfoodcreater(IIB)V: what the guest throws also lands on the host */
    public static void feed(int x, int y, byte kind) {
        if (!guest()) {
            return;
        }
        Net.send(new Msg(Proto.LIFE, 16).u8(L_FEED).i32(x).i32(y).u8(kind));
    }

    // ------------------------------------------------------------------ rain collectors

    private static byte[] collectSeen = new byte[0];

    /** client: after the host's collector state was applied */
    static void collectApplied() {
        collectSeen = MyPanel.collecthave.clone();
    }

    /** client worker, a few times per second: water the guest took */
    public static void clientTick() {
        if (!guest() || MyPanel.collecthave == null) {
            return;
        }
        int n = Math.min(MyPanel.collectnuming, Math.min(collectSeen.length, MyPanel.collecthave.length));
        for (int i = 0; i < n; i++) {
            int took = collectSeen[i] - MyPanel.collecthave[i];
            if (took > 0) {
                Net.send(new Msg(Proto.LIFE, 16).u8(L_COLLECT_TAKE).i32(MyPanel.collect[0][i]).i32(MyPanel.collect[1][i]).u8(took));
                collectSeen[i] = MyPanel.collecthave[i];
            }
        }
    }

    // ------------------------------------------------------------------ sleeping

    private static volatile long hostSleptAt;
    private static volatile long guestSleptAt;
    private static long lastNotice;

    /** REDIRECT of addhour(I) inside Dialogview.unity(): the time jump of sleeping */
    public static void sleepAddHour(int h) {
        if (!Coop.playing()) {
            MyPanel.addhour(h);
            return;
        }
        long now = System.currentTimeMillis();
        if (Net.isClient()) {
            Net.send(new Msg(Proto.LIFE, 8).u8(L_SLEEP).u8(h));
            MyPanel.realtime -= h * 60; // cancels the "+= 420" after it, the host decides the clock
            note(T.s("Time only passes when {0} sleeps too.", Net.peerName));
            return;
        }
        hostSleptAt = now;
        if (now - guestSleptAt < 60_000) {
            guestSleptAt = 0;
            hostSleptAt = 0;
            MyPanel.addhour(h); // both slept: the night passes
            Log.info("both slept: " + h + " hours pass");
        } else {
            MyPanel.realtime -= h * 60;
            note(T.s("Time only passes when {0} sleeps too.", Net.peerName));
        }
    }

    private static void note(String text) {
        long now = System.currentTimeMillis();
        if (now - lastNotice > 5000) {
            lastNotice = now;
            Coop.message(text);
        }
    }

    private static void hostGuestSlept(int h) {
        long now = System.currentTimeMillis();
        guestSleptAt = now;
        if (now - hostSleptAt < 60_000) {
            guestSleptAt = 0;
            hostSleptAt = 0;
            MyPanel.addhour(h);
            MyPanel.realtime += h * 60;
            Log.info("both slept: " + h + " hours pass");
        } else {
            Coop.message(T.s("{0} is sleeping - time passes when you sleep too.", Net.peerName));
        }
    }

    // ------------------------------------------------------------------ minigames

    private static long lastBlock;

    /** HEAD_CANCEL of MyPanel.pcgame/football/cargame/lover: not in co-op (they switch to a private map) */
    public static boolean minigameBlock() {
        if (!Coop.playing()) {
            return false;
        }
        MyPanel.lover = false;
        long now = System.currentTimeMillis();
        if (now - lastBlock > 3000) {
            lastBlock = now;
            Coop.message(T.s("Mini-games are not available in co-op."));
        }
        return true;
    }

    // ------------------------------------------------------------------ messages

    public static void handle(Msg m) {
        if (!Net.isHost()) {
            return;
        }
        int kind = m.u8();
        switch (kind) {
            case L_BONFIRE_BURN: {
                int x = m.i32();
                int y = m.i32();
                Coop.runSim(() -> {
                    int i = bonfireAt(x, y);
                    if (i >= 0 && Coop.panel != null) {
                        Coop.panel.bonfireburn(i);
                    }
                });
                break;
            }
            case L_BONFIRE_TIME: {
                int x = m.i32();
                int y = m.i32();
                int start = m.i32();
                int time = m.i32();
                Coop.runSim(() -> {
                    int i = bonfireAt(x, y);
                    if (i >= 0) {
                        MyPanel.bonfirestartime[i] = start;
                        MyPanel.bonfiretime[i] = time;
                    }
                });
                break;
            }
            case L_FEED: {
                int x = m.i32();
                int y = m.i32();
                byte k = (byte) m.u8();
                Coop.runSim(() -> {
                    applying.set(true);
                    try {
                        if (Coop.panel != null) {
                            Coop.panel.cockfoodcreater(x, y, k);
                        }
                    } finally {
                        applying.set(false);
                    }
                });
                break;
            }
            case L_COLLECT_TAKE: {
                int x = m.i32();
                int y = m.i32();
                int took = m.u8();
                Coop.runSim(() -> {
                    for (int i = 0; i < MyPanel.collectnuming; i++) {
                        if (Math.abs(MyPanel.collect[0][i] - x) < 3 && Math.abs(MyPanel.collect[1][i] - y) < 3) {
                            MyPanel.collecthave[i] = (byte) Math.max(0, MyPanel.collecthave[i] - took);
                            break;
                        }
                    }
                });
                break;
            }
            case L_SLEEP: {
                int h = m.u8();
                Coop.runSim(() -> hostGuestSlept(h));
                break;
            }
            default:
                break;
        }
    }
}
