package coop.patcher;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads the game JAR that exe4j stores inside the launcher EXE.
 * exe4j appends its payload after the PE sections and XORs the archives with 0x88.
 */
public final class Exe4j {
    private Exe4j() {}

    /** Returns the bytes of the embedded archive that contains Game/MyPanel.class. */
    public static byte[] extractGameJar(Path exe) throws IOException {
        byte[] data = Files.readAllBytes(exe);
        int overlay = overlayStart(data);
        byte[] x = new byte[data.length - overlay];
        for (int i = 0; i < x.length; i++) {
            x[i] = (byte) (data[overlay + i] ^ 0x88);
        }
        ByteBuffer bb = ByteBuffer.wrap(x).order(ByteOrder.LITTLE_ENDIAN);
        byte[] best = null;
        for (int i = 0; i + 22 <= x.length; i++) {
            if (x[i] == 'P' && x[i + 1] == 'K' && x[i + 2] == 5 && x[i + 3] == 6) {
                long cdSize = bb.getInt(i + 12) & 0xffffffffL;
                long cdOff = bb.getInt(i + 16) & 0xffffffffL;
                int clen = bb.getShort(i + 20) & 0xffff;
                long start = i - cdSize - cdOff;
                long end = i + 22L + clen;
                if (start < 0 || end > x.length) {
                    continue;
                }
                byte[] blob = new byte[(int) (end - start)];
                System.arraycopy(x, (int) start, blob, 0, blob.length);
                if (containsEntry(blob, "Game/MyPanel.class")) {
                    best = blob;
                }
            }
        }
        if (best == null) {
            throw new IOException("Kein Spiel-JAR in " + exe + " gefunden");
        }
        return best;
    }

    private static boolean containsEntry(byte[] zip, String name) {
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.getName().equals(name)) {
                    return true;
                }
            }
        } catch (IOException ignored) {
            return false;
        }
        return false;
    }

    private static int overlayStart(byte[] d) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        int pe = bb.getInt(0x3c);
        if (pe <= 0 || pe + 24 > d.length || d[pe] != 'P' || d[pe + 1] != 'E') {
            throw new IOException("Keine gueltige EXE-Datei");
        }
        int nsec = bb.getShort(pe + 6) & 0xffff;
        int optSize = bb.getShort(pe + 20) & 0xffff;
        int sec = pe + 24 + optSize;
        int end = 0;
        for (int i = 0; i < nsec; i++) {
            int rawSize = bb.getInt(sec + i * 40 + 16);
            int rawPtr = bb.getInt(sec + i * 40 + 20);
            end = Math.max(end, rawPtr + rawSize);
        }
        return end;
    }
}
