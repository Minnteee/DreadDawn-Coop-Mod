package coop.launcher;

import coop.patcher.Exe4j;
import coop.patcher.GamePatcher;
import coop.patcher.Patches;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.swing.JOptionPane;

/**
 * Starts Dread Dawn with the co-op mod.
 *
 * 1. Finds the game folder (the folder that contains "coop\coop-mod.jar").
 * 2. Reads the game JAR out of the original "Dread dawn.exe" and patches it (only when the EXE changed).
 * 3. Starts the game with the bundled Java runtime, exactly like the original launcher does.
 *
 * Arguments of the form -Dcoop.xxx=yyy are passed on to the game.
 */
public final class Launcher {
    private Launcher() {}

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable t) {
            t.printStackTrace();
            try {
                JOptionPane.showMessageDialog(null, "Dread Dawn Coop konnte nicht starten:\n" + t.getMessage(),
                        "Dread Dawn Coop", JOptionPane.ERROR_MESSAGE);
            } catch (Throwable ignored) {
                // headless
            }
            System.exit(1);
        }
    }

    static void run(String[] args) throws Exception {
        Path modJar = Path.of(Launcher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path coopDir = modJar.getParent();
        Path root = coopDir.getParent();
        Path panelDir = root.resolve("Word").resolve("Etc").resolve("doot").resolve("effc").resolve("MyPanel");
        Path exe = panelDir.resolve("Dread dawn.exe");
        if (!Files.isRegularFile(exe)) {
            throw new IOException("Spiel nicht gefunden: " + exe + "\nDer Ordner 'coop' muss im Dread-Dawn-Ordner liegen.");
        }
        Path cache = coopDir.resolve("cache");
        Files.createDirectories(cache);
        Path patched = cache.resolve("game-patched.jar");
        Path stamp = cache.resolve("game-patched.stamp");

        String exeHash = sha256(exe);
        String modHash = sha256(modJar);
        String want = exeHash + "\n" + modHash + "\n";
        boolean upToDate = Files.isRegularFile(patched) && Files.isRegularFile(stamp)
                && Files.readString(stamp, StandardCharsets.UTF_8).equals(want);
        boolean force = false;
        for (String a : args) {
            if (a.equals("--repatch")) {
                force = true;
            }
        }
        if (!upToDate || force) {
            System.out.println("[coop] Patche Spiel aus " + exe);
            byte[] jar = Exe4j.extractGameJar(exe);
            GamePatcher gp = Patches.create();
            byte[] out = gp.patchJar(jar, true);
            Path tmp = cache.resolve("game-patched.jar.tmp");
            Files.write(tmp, out);
            Files.move(tmp, patched, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(cache.resolve("patch-log.txt"), gp.log(), StandardCharsets.UTF_8);
            Files.writeString(stamp, want, StandardCharsets.UTF_8);
        }

        Path javaw = panelDir.resolve("jreing").resolve("bin").resolve("javaw.exe");
        Path java = panelDir.resolve("jreing").resolve("bin").resolve("java.exe");
        boolean console = false;
        List<String> pass = new ArrayList<>();
        for (String a : args) {
            if (a.startsWith("-D") || a.startsWith("-X")) {
                pass.add(a);
            }
            if (a.equals("--console")) {
                console = true;
            }
        }
        List<String> cmd = new ArrayList<>();
        cmd.add((console ? java : javaw).toString());
        // same VM options as the original exe4j launcher
        cmd.add("-Djava.library.path=./bin");
        cmd.add("-Dprism.targetvram=4000m");
        cmd.add("-Dprism.maxvram=6000m");
        cmd.add("-Xms2000m");
        cmd.add("-Xmx4000m");
        cmd.add("-Dprism.forceGPU=true");
        cmd.add("-Dsun.java2d.opengl=true");
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add("-Dcoop.root=" + root);
        cmd.addAll(pass);
        cmd.add("-cp");
        cmd.add(modJar + File.pathSeparator + patched);
        cmd.add("Game.MyPanel");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(panelDir.toFile());
        Path logDir = coopDir.resolve("logs");
        Files.createDirectories(logDir);
        String tag = System.getProperty("coop.logtag", "game");
        for (String a : pass) {
            if (a.startsWith("-Dcoop.logtag=")) {
                tag = a.substring("-Dcoop.logtag=".length());
            }
        }
        pb.redirectErrorStream(true);
        Process p = null;
        // a second game started the same way would find the log file in use: use another name
        for (int i = 1; i <= 9 && p == null; i++) {
            pb.redirectOutput(logDir.resolve(i == 1 ? tag + ".log" : tag + "-" + i + ".log").toFile());
            try {
                p = pb.start();
            } catch (IOException e) {
                if (i == 9) {
                    throw e;
                }
            }
        }
        System.out.println("[coop] Spiel gestartet (PID " + p.pid() + ")");
        for (String a : args) {
            if (a.equals("--wait")) {
                System.exit(p.waitFor());
            }
        }
    }

    static String sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(Files.readAllBytes(p));
        return HexFormat.of().formatHex(md.digest());
    }
}
