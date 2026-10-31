package coop.sync;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Array;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Writes / reads (nested) arrays of primitives and Strings; used to mirror game state arrays. */
final class ArrayCodec {
    private ArrayCodec() {}

    static void write(DataOutputStream o, Object a) throws IOException {
        if (a == null) {
            o.writeByte(0);
            return;
        }
        Class<?> c = a.getClass();
        if (!c.isArray()) {
            o.writeByte(9);
            writeScalar(o, a);
            return;
        }
        int n = Array.getLength(a);
        Class<?> ct = c.getComponentType();
        if (ct.isArray()) {
            o.writeByte(8);
            o.writeInt(n);
            for (int i = 0; i < n; i++) {
                write(o, Array.get(a, i));
            }
        } else if (ct == int.class) {
            o.writeByte(1);
            o.writeInt(n);
            for (int v : (int[]) a) {
                o.writeInt(v);
            }
        } else if (ct == double.class) {
            o.writeByte(2);
            o.writeInt(n);
            for (double v : (double[]) a) {
                o.writeDouble(v);
            }
        } else if (ct == float.class) {
            o.writeByte(3);
            o.writeInt(n);
            for (float v : (float[]) a) {
                o.writeFloat(v);
            }
        } else if (ct == byte.class) {
            o.writeByte(4);
            o.writeInt(n);
            o.write((byte[]) a);
        } else if (ct == boolean.class) {
            o.writeByte(5);
            o.writeInt(n);
            for (boolean v : (boolean[]) a) {
                o.writeBoolean(v);
            }
        } else if (ct == short.class) {
            o.writeByte(6);
            o.writeInt(n);
            for (short v : (short[]) a) {
                o.writeShort(v);
            }
        } else if (ct == String.class) {
            o.writeByte(7);
            o.writeInt(n);
            for (String v : (String[]) a) {
                o.writeBoolean(v != null);
                if (v != null) {
                    o.writeUTF(v);
                }
            }
        } else if (ct == long.class) {
            o.writeByte(10);
            o.writeInt(n);
            for (long v : (long[]) a) {
                o.writeLong(v);
            }
        } else {
            throw new IOException("type not supported: " + c);
        }
    }

    static void writeScalar(DataOutputStream o, Object v) throws IOException {
        if (v instanceof Integer) {
            o.writeByte(1);
            o.writeInt((Integer) v);
        } else if (v instanceof Double) {
            o.writeByte(2);
            o.writeDouble((Double) v);
        } else if (v instanceof Float) {
            o.writeByte(3);
            o.writeFloat((Float) v);
        } else if (v instanceof Byte) {
            o.writeByte(4);
            o.writeByte((Byte) v);
        } else if (v instanceof Boolean) {
            o.writeByte(5);
            o.writeBoolean((Boolean) v);
        } else if (v instanceof Short) {
            o.writeByte(6);
            o.writeShort((Short) v);
        } else if (v instanceof String) {
            o.writeByte(7);
            o.writeUTF((String) v);
        } else if (v instanceof Long) {
            o.writeByte(10);
            o.writeLong((Long) v);
        } else {
            throw new IOException("scalar not supported: " + v);
        }
    }

    static Object readScalar(DataInputStream in) throws IOException {
        switch (in.readByte()) {
            case 1:
                return in.readInt();
            case 2:
                return in.readDouble();
            case 3:
                return in.readFloat();
            case 4:
                return in.readByte();
            case 5:
                return in.readBoolean();
            case 6:
                return in.readShort();
            case 7:
                return in.readUTF();
            case 10:
                return in.readLong();
            default:
                throw new IOException("unknown scalar type");
        }
    }

    /**
     * Reads an array written by write(). If target has the same shape the values are copied into it
     * (keeps the game's array references); the returned object is the array to use.
     */
    static Object read(DataInputStream in, Object target) throws IOException {
        int t = in.readByte();
        if (t == 0) {
            return null;
        }
        if (t == 9) {
            return readScalar(in);
        }
        int n = in.readInt();
        switch (t) {
            case 8: {
                Object arr = target != null && target.getClass().isArray() && Array.getLength(target) == n ? target : null;
                Object[] parts = new Object[n];
                for (int i = 0; i < n; i++) {
                    parts[i] = read(in, arr != null ? Array.get(arr, i) : null);
                }
                if (arr == null) {
                    if (n == 0) {
                        return target;
                    }
                    arr = Array.newInstance(parts[0] == null ? Object.class : parts[0].getClass(), n);
                }
                for (int i = 0; i < n; i++) {
                    Array.set(arr, i, parts[i]);
                }
                return arr;
            }
            case 1: {
                int[] a = target instanceof int[] && ((int[]) target).length == n ? (int[]) target : new int[n];
                for (int i = 0; i < n; i++) {
                    a[i] = in.readInt();
                }
                return a;
            }
            case 2: {
                double[] a = target instanceof double[] && ((double[]) target).length == n ? (double[]) target : new double[n];
                for (int i = 0; i < n; i++) {
                    a[i] = in.readDouble();
                }
                return a;
            }
            case 3: {
                float[] a = target instanceof float[] && ((float[]) target).length == n ? (float[]) target : new float[n];
                for (int i = 0; i < n; i++) {
                    a[i] = in.readFloat();
                }
                return a;
            }
            case 4: {
                byte[] a = target instanceof byte[] && ((byte[]) target).length == n ? (byte[]) target : new byte[n];
                in.readFully(a);
                return a;
            }
            case 5: {
                boolean[] a = target instanceof boolean[] && ((boolean[]) target).length == n ? (boolean[]) target : new boolean[n];
                for (int i = 0; i < n; i++) {
                    a[i] = in.readBoolean();
                }
                return a;
            }
            case 6: {
                short[] a = target instanceof short[] && ((short[]) target).length == n ? (short[]) target : new short[n];
                for (int i = 0; i < n; i++) {
                    a[i] = in.readShort();
                }
                return a;
            }
            case 7: {
                String[] a = target instanceof String[] && ((String[]) target).length == n ? (String[]) target : new String[n];
                for (int i = 0; i < n; i++) {
                    a[i] = in.readBoolean() ? in.readUTF() : null;
                }
                return a;
            }
            case 10: {
                long[] a = target instanceof long[] && ((long[]) target).length == n ? (long[]) target : new long[n];
                for (int i = 0; i < n; i++) {
                    a[i] = in.readLong();
                }
                return a;
            }
            default:
                throw new IOException("unknown array type: " + t);
        }
    }

    static byte[] deflate(byte[] data) {
        Deflater d = new Deflater(Deflater.BEST_SPEED);
        d.setInput(data);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 8));
        byte[] buf = new byte[65536];
        while (!d.finished()) {
            out.write(buf, 0, d.deflate(buf));
        }
        d.end();
        return out.toByteArray();
    }

    static byte[] inflate(byte[] data, int size) throws IOException {
        Inflater inf = new Inflater();
        inf.setInput(data);
        byte[] out = new byte[size];
        try {
            int n = 0;
            while (n < size && !inf.finished()) {
                n += inf.inflate(out, n, size - n);
            }
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException(e);
        } finally {
            inf.end();
        }
        return out;
    }
}
