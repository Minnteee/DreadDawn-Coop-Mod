package coop;

import Game.MyPanel;
import coop.net.Msg;
import coop.net.Net;
import coop.net.Proto;
import coop.sync.WorldTransfer;

/** Handshake, keep-alive and simple text messages. */
final class Session {
    private Session() {}

    private static long lastPing;

    static void tick() {
        long now = System.currentTimeMillis();
        if (Net.connected() && now - lastPing > 1000) {
            lastPing = now;
            Net.send(new Msg(Proto.PING).i64(System.nanoTime()));
        }
    }

    static void handle(Msg m) {
        switch (m.type) {
            case Proto.HELLO: {
                if (!Net.isHost()) {
                    return;
                }
                int magic = m.i32();
                int proto = m.i32();
                String modVer = m.str();
                String gameVer = m.str();
                String name = Config.clean(m.str());
                String err = null;
                if (magic != Proto.PROTOCOL_MAGIC || proto != Net.PROTOCOL) {
                    err = "Unterschiedliche Coop-Mod-Version (Host " + Coop.VERSION + " #" + Net.PROTOCOL + ", du " + modVer
                            + " #" + proto + "). Bitte beide dieselbe coop-mod.jar benutzen.";
                } else if (!MyPanel.verision.equals(gameVer)) {
                    err = "Unterschiedliche Spielversion (Host " + MyPanel.verision + ", du " + gameVer + ").";
                }
                if (err != null) {
                    Net.send(new Msg(Proto.REJECT).str(err));
                    Log.info("Client abgewiesen: " + err);
                    Coop.message("Mitspieler abgewiesen: " + err);
                    return;
                }
                Net.send(new Msg(Proto.WELCOME).str(Config.name).i32(MyPanel.te));
                Net.handshakeDone(name);
                Coop.message(name + " ist beigetreten");
                WorldTransfer.requestSend("Mitspieler beigetreten", true);
                break;
            }
            case Proto.WELCOME: {
                if (!Net.isClient()) {
                    return;
                }
                String name = m.str();
                int te = m.i32();
                Coop.forcedTe = te;
                Net.handshakeDone(name);
                Net.status = "Verbunden mit " + name + " - warte auf die Welt des Hosts ...";
                Coop.message("Verbunden mit " + name);
                break;
            }
            case Proto.REJECT: {
                String why = m.str();
                Coop.message("Abgewiesen: " + why);
                Net.stop("abgewiesen: " + why);
                Net.status = "Abgewiesen: " + why;
                break;
            }
            case Proto.PING:
                Net.send(new Msg(Proto.PONG).i64(m.i64()));
                break;
            case Proto.PONG:
                Net.rttMs = (System.nanoTime() - m.i64()) / 1_000_000L;
                break;
            case Proto.BYE: {
                String why = m.str();
                if (Net.isClient()) {
                    Coop.message("Host hat das Spiel beendet (" + why + ")");
                    Net.stop("Host beendet: " + why);
                } else if (Net.conn != null) {
                    Net.conn.close("Mitspieler hat verlassen");
                }
                break;
            }
            case Proto.MESSAGE:
                Coop.message(m.str());
                break;
            default:
                break;
        }
    }
}
