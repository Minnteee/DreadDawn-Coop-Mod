package coop.net;

import coop.Log;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One TCP connection to the other player. Frames: [int length][u8 type][payload].
 * Reading and writing happen on their own threads; the game polls received messages.
 */
public final class Connection {
    private static final Msg CLOSE = new Msg(-1, 16);
    private static final int MAX_FRAME = 64 * 1024 * 1024;

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final LinkedBlockingQueue<Msg> sendQueue = new LinkedBlockingQueue<>();
    private final ConcurrentLinkedQueue<Msg> received = new ConcurrentLinkedQueue<>();
    private volatile boolean open = true;
    private volatile String closeReason = "";
    public final AtomicLong bytesOut = new AtomicLong();
    public final AtomicLong bytesIn = new AtomicLong();
    public volatile long lastReceive = System.currentTimeMillis();

    public Connection(Socket s) throws IOException {
        this.socket = s;
        s.setTcpNoDelay(true);
        s.setKeepAlive(true);
        s.setSendBufferSize(1 << 20);
        s.setReceiveBufferSize(1 << 20);
        this.in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 1 << 16));
        this.out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream(), 1 << 16));
        Thread r = new Thread(this::readLoop, "coop-net-read");
        r.setDaemon(true);
        r.start();
        Thread w = new Thread(this::writeLoop, "coop-net-write");
        w.setDaemon(true);
        w.start();
    }

    public String remoteAddress() {
        return String.valueOf(socket.getRemoteSocketAddress());
    }

    public boolean isOpen() {
        return open;
    }

    public String closeReason() {
        return closeReason;
    }

    public void send(Msg m) {
        if (open) {
            sendQueue.offer(m);
        }
    }

    /** Next received message or null. */
    public Msg poll() {
        return received.poll();
    }

    public int pendingSend() {
        return sendQueue.size();
    }

    public void close(String reason) {
        if (!open) {
            return;
        }
        open = false;
        closeReason = reason;
        sendQueue.offer(CLOSE);
        try {
            socket.close();
        } catch (IOException ignored) {
            // closing anyway
        }
        Log.info("Verbindung getrennt: " + reason);
    }

    private void readLoop() {
        try {
            while (open) {
                int len = in.readInt();
                if (len < 1 || len > MAX_FRAME) {
                    throw new IOException("ungueltige Paketlaenge " + len);
                }
                int type = in.readUnsignedByte();
                byte[] data = new byte[len - 1];
                in.readFully(data);
                bytesIn.addAndGet(len + 4);
                lastReceive = System.currentTimeMillis();
                received.offer(new Msg(type, data, data.length));
            }
        } catch (Exception e) {
            close(open ? "Lesefehler: " + e.getMessage() : closeReason);
        }
    }

    private void writeLoop() {
        try {
            while (open) {
                Msg m = sendQueue.poll(1, TimeUnit.SECONDS);
                if (m == null) {
                    continue;
                }
                if (m == CLOSE) {
                    break;
                }
                write(m);
                // batch everything already queued, then flush once
                Msg more;
                while ((more = sendQueue.poll()) != null && more != CLOSE) {
                    write(more);
                }
                out.flush();
                if (more == CLOSE) {
                    break;
                }
            }
        } catch (Exception e) {
            close(open ? "Schreibfehler: " + e.getMessage() : closeReason);
        }
    }

    private void write(Msg m) throws IOException {
        out.writeInt(m.length() + 1);
        out.writeByte(m.type);
        out.write(m.array(), 0, m.length());
        bytesOut.addAndGet(m.length() + 5);
    }
}
