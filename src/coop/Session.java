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
                    err = T.s("Different co-op mod version (host {0} #{1}, you {2} #{3}). Both players need the same coop-mod.jar.",
                            Coop.VERSION, Net.PROTOCOL, modVer, proto);
                } else if (!MyPanel.verision.equals(gameVer)) {
                    err = T.s("Different game version (host {0}, you {1}).", MyPanel.verision, gameVer);
                }
                if (err != null) {
                    Net.send(new Msg(Proto.REJECT).str(err));
                    Log.info("Client rejected: " + err);
                    Coop.message(T.s("Partner rejected: {0}", err));
                    return;
                }
                Net.send(new Msg(Proto.WELCOME).str(Config.name).i32(MyPanel.te));
                Net.handshakeDone(name);
                Coop.message(T.s("{0} joined", name));
                WorldTransfer.requestSend("partner joined", true);
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
                Net.status = T.s("Connected to {0} - waiting for the host's world ...", name);
                Coop.message(T.s("Connected to {0}", name));
                break;
            }
            case Proto.REJECT: {
                String why = m.str();
                Coop.message(T.s("Rejected: {0}", why));
                Net.stop("rejected: " + why);
                Net.status = T.s("Rejected: {0}", why);
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
                    Coop.message(T.s("The host ended the game ({0})", why));
                    Net.stop("host ended: " + why);
                } else if (Net.conn != null) {
                    Net.conn.close("partner left");
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
