package coop.net;

import coop.Coop;
import coop.Log;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Hosting and joining. Exactly two players: the host (owns the world) and one client.
 */
public final class Net {
    private Net() {}

    public static final int PROTOCOL = 5;

    public enum Role { NONE, HOST, CLIENT }

    public enum State { IDLE, LISTENING, CONNECTING, HANDSHAKE, CONNECTED, FAILED }

    public static volatile Role role = Role.NONE;
    public static volatile State state = State.IDLE;
    public static volatile String status = "";
    public static volatile String peerName = "";
    public static volatile Connection conn;
    private static volatile ServerSocket server;
    public static volatile long rttMs = -1;

    public static boolean isHost() {
        return role == Role.HOST;
    }

    public static boolean isClient() {
        return role == Role.CLIENT;
    }

    /** true when a partner is connected and the handshake is done */
    public static boolean connected() {
        return state == State.CONNECTED && conn != null && conn.isOpen();
    }

    public static boolean active() {
        return role != Role.NONE;
    }

    // ------------------------------------------------------------------ host

    public static synchronized void host(int port) {
        stop("neu");
        role = Role.HOST;
        state = State.LISTENING;
        status = "Starte Server auf Port " + port + " ...";
        Thread t = new Thread(() -> acceptLoop(port), "coop-accept");
        t.setDaemon(true);
        t.start();
    }

    private static void acceptLoop(int port) {
        try (ServerSocket ss = new ServerSocket()) {
            ss.setReuseAddress(true);
            String bind = System.getProperty("coop.bind");
            ss.bind(bind != null ? new InetSocketAddress(bind, port) : new InetSocketAddress(port));
            server = ss;
            status = "Warte auf Mitspieler ... (" + String.join(" / ", localAddresses()) + " : " + port + ")";
            Log.info("Host lauscht auf Port " + port + " " + localAddresses());
            while (role == Role.HOST) {
                Socket s;
                try {
                    ss.setSoTimeout(1000);
                    s = ss.accept();
                } catch (SocketTimeoutException e) {
                    continue;
                }
                if (connected() || state == State.HANDSHAKE) {
                    // only one partner
                    try (s) {
                        Connection c = new Connection(s);
                        c.send(new Msg(Proto.REJECT).str("Es ist bereits ein Mitspieler verbunden."));
                        Thread.sleep(300);
                        c.close("abgewiesen");
                    } catch (Exception ignored) {
                        // ignore
                    }
                    continue;
                }
                state = State.HANDSHAKE;
                conn = new Connection(s);
                Log.info("Verbindung von " + conn.remoteAddress());
                status = "Mitspieler verbindet sich ...";
            }
        } catch (Exception e) {
            if (role == Role.HOST) {
                state = State.FAILED;
                status = "Server-Fehler: " + e.getMessage() + " (Port " + port + " belegt?)";
                Log.error("acceptLoop", e);
            }
        } finally {
            server = null;
        }
    }

    // ------------------------------------------------------------------ client

    public static synchronized void join(String ip, int port) {
        stop("neu");
        role = Role.CLIENT;
        state = State.CONNECTING;
        status = "Verbinde mit " + ip + ":" + port + " ...";
        Thread t = new Thread(() -> {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(ip, port), 8000);
                conn = new Connection(s);
                state = State.HANDSHAKE;
                status = "Verbunden, melde an ...";
                conn.send(Proto.hello());
            } catch (Exception e) {
                state = State.FAILED;
                status = "Verbindung fehlgeschlagen: " + e.getMessage();
                Log.info("join fehlgeschlagen: " + e);
            }
        }, "coop-connect");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ common

    public static synchronized void stop(String reason) {
        Role old = role;
        role = Role.NONE;
        state = State.IDLE;
        Connection c = conn;
        conn = null;
        if (c != null) {
            try {
                c.send(new Msg(Proto.BYE).str(reason));
                Thread.sleep(100);
            } catch (Exception ignored) {
                // ignore
            }
            c.close(reason);
        }
        ServerSocket ss = server;
        if (ss != null) {
            try {
                ss.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
        peerName = "";
        rttMs = -1;
        if (old != Role.NONE) {
            Log.info("Coop beendet: " + reason);
            Coop.onSessionEnded(old, reason);
        }
        status = "";
    }

    public static void send(Msg m) {
        Connection c = conn;
        if (c != null && c.isOpen()) {
            c.send(m);
        }
    }

    /** Called by the handshake code once both sides agreed. */
    public static void handshakeDone(String name) {
        peerName = name;
        state = State.CONNECTED;
        status = "Verbunden mit " + name;
        Log.info("Handshake ok, Partner: " + name);
    }

    /** Detects dropped connections; returns true while connected. */
    public static boolean checkAlive() {
        Connection c = conn;
        if (c == null) {
            return false;
        }
        if (!c.isOpen()) {
            Role r = role;
            String why = c.closeReason();
            if (r == Role.HOST) {
                // keep hosting: partner may come back
                conn = null;
                peerName = "";
                state = State.LISTENING;
                status = "Mitspieler getrennt (" + why + "). Warte auf Mitspieler ...";
                Coop.onPartnerLost(why);
            } else {
                stop("Verbindung verloren: " + why);
                Coop.message("Verbindung zum Host verloren: " + why);
            }
            return false;
        }
        if (System.currentTimeMillis() - c.lastReceive > 15000) {
            c.close("Zeitüberschreitung");
        }
        return state == State.CONNECTED;
    }

    public static List<String> localAddresses() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a.getAddress().length != 4) {
                        continue;
                    }
                    String ip = a.getHostAddress();
                    // Radmin VPN uses 26.x.x.x; list it first
                    if (ip.startsWith("26.")) {
                        out.add(0, ip);
                    } else if (!ip.startsWith("169.254.")) {
                        out.add(ip);
                    }
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        return out.size() > 3 ? out.subList(0, 3) : out;
    }
}
