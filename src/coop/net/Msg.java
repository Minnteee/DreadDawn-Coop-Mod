package coop.net;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Growable binary message buffer (big endian) used for writing and reading network messages. */
public final class Msg {
    public final int type;
    private byte[] b;
    private int len;
    private int pos;

    public Msg(int type) {
        this(type, 256);
    }

    public Msg(int type, int capacity) {
        this.type = type;
        this.b = new byte[Math.max(16, capacity)];
    }

    /** Wraps received payload for reading. */
    public Msg(int type, byte[] data, int length) {
        this.type = type;
        this.b = data;
        this.len = length;
    }

    public int length() {
        return len;
    }

    public byte[] array() {
        return b;
    }

    public int remaining() {
        return len - pos;
    }

    private void ensure(int n) {
        if (len + n > b.length) {
            b = Arrays.copyOf(b, Math.max(b.length * 2, len + n));
        }
    }

    public Msg u8(int v) {
        ensure(1);
        b[len++] = (byte) v;
        return this;
    }

    public Msg bool(boolean v) {
        return u8(v ? 1 : 0);
    }

    public Msg i16(int v) {
        ensure(2);
        b[len++] = (byte) (v >>> 8);
        b[len++] = (byte) v;
        return this;
    }

    public Msg i32(int v) {
        ensure(4);
        b[len++] = (byte) (v >>> 24);
        b[len++] = (byte) (v >>> 16);
        b[len++] = (byte) (v >>> 8);
        b[len++] = (byte) v;
        return this;
    }

    public Msg i64(long v) {
        i32((int) (v >>> 32));
        return i32((int) v);
    }

    public Msg f32(float v) {
        return i32(Float.floatToRawIntBits(v));
    }

    public Msg f64(double v) {
        return i64(Double.doubleToRawLongBits(v));
    }

    public Msg str(String s) {
        if (s == null) {
            return i32(-1);
        }
        byte[] d = s.getBytes(StandardCharsets.UTF_8);
        i32(d.length);
        return bytes(d, 0, d.length);
    }

    public Msg bytes(byte[] d, int off, int n) {
        ensure(n);
        System.arraycopy(d, off, b, len, n);
        len += n;
        return this;
    }

    public Msg blob(byte[] d) {
        i32(d.length);
        return bytes(d, 0, d.length);
    }

    // ---- reading ----

    public int u8() {
        return b[pos++] & 0xff;
    }

    public byte s8() {
        return b[pos++];
    }

    public boolean bool() {
        return b[pos++] != 0;
    }

    public short i16() {
        int v = ((b[pos] & 0xff) << 8) | (b[pos + 1] & 0xff);
        pos += 2;
        return (short) v;
    }

    public int i32() {
        int v = ((b[pos] & 0xff) << 24) | ((b[pos + 1] & 0xff) << 16) | ((b[pos + 2] & 0xff) << 8) | (b[pos + 3] & 0xff);
        pos += 4;
        return v;
    }

    public long i64() {
        long hi = i32() & 0xffffffffL;
        long lo = i32() & 0xffffffffL;
        return (hi << 32) | lo;
    }

    public float f32() {
        return Float.intBitsToFloat(i32());
    }

    public double f64() {
        return Double.longBitsToDouble(i64());
    }

    public String str() {
        int n = i32();
        if (n < 0) {
            return null;
        }
        String s = new String(b, pos, n, StandardCharsets.UTF_8);
        pos += n;
        return s;
    }

    public byte[] blob() {
        int n = i32();
        byte[] d = Arrays.copyOfRange(b, pos, pos + n);
        pos += n;
        return d;
    }
}
