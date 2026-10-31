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
import coop.T;

/**
 * Hosting and joining. Exactly two players: the host (owns the world) and one client.
 */
public final class Net {
    private Net() {}

    public static final int PROTOCOL = 6;

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
        stop("new");
        role = Role.HOST;
        state = State.LISTENING;
        status = T.s("Starting server on port {0} ...", port);
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
            status = T.s("Waiting for your partner ... ({0} : {1})", String.join(" / ", localAddresses()), port);
            Log.info("Host listening on port " + port + " " + localAddresses());
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
                        c.send(new Msg(Proto.REJECT).str(T.s("A partner is already connected.")));
                        Thread.sleep(300);
                        c.close("rejected");
                    } catch (Exception ignored) {
                        // ignore
                    }
                    continue;
                }
                state = State.HANDSHAKE;
                conn = new Connection(s);
                Log.info("Connection from " + conn.remoteAddress());
                status = T.s("Your partner is connecting ...");
            }
        } catch (Exception e) {
            if (role == Role.HOST) {
                state = State.FAILED;
                status = T.s("Server error: {0} (is port {1} in use?)", e.getMessage(), port);
                Log.error("acceptLoop", e);
            }
        } finally {
            server = null;
        }
    }

    // ------------------------------------------------------------------ client

    public static synchronized void join(String ip, int port) {
        stop("new");
        role = Role.CLIENT;
        state = State.CONNECTING;
        status = T.s("Connecting to {0}:{1} ...", ip, port);
        Thread t = new Thread(() -> {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(ip, port), 8000);
                conn = new Connection(s);
                state = State.HANDSHAKE;
                status = T.s("Connected, logging in ...");
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
        Clock.reset();
        peerName = name;
        state = State.CONNECTED;
        status = T.s("Connected to {0}", name);
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
                status = T.s("Partner disconnected ({0}). Waiting for your partner ...", why);
                Coop.onPartnerLost(why);
            } else {
                stop("Verbindung verloren: " + why);
                Coop.message(T.s("Connection to the host lost: {0}", why));
            }
            return false;
        }
        if (System.currentTimeMillis() - c.lastReceive > 15000) {
            c.close("timeout");
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
