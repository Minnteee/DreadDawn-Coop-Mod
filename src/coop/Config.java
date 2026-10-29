package coop;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** coop\coop.properties: player name, last IP, port. */
public final class Config {
    private Config() {}

    public static String name = "Spieler";
    public static String lastIp = "";
    public static int port = 7777;
    /** maximum distance (world units) the two players may be apart; 0 = no limit */
    public static int leash = 0;
    public static boolean showOwnName = false;

    static Path file() {
        String root = System.getProperty("coop.root");
        if (root == null) {
            root = new java.io.File("").getAbsolutePath().replace("Word\\Etc\\doot\\effc\\MyPanel", "");
        }
        return Path.of(root, "coop", System.getProperty("coop.config", "coop.properties"));
    }

    public static void load() {
        Properties p = new Properties();
        try {
            Path f = file();
            if (Files.exists(f)) {
                try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    p.load(r);
                }
            }
        } catch (Exception e) {
            Log.error("Config.load", e);
        }
        name = clean(p.getProperty("name", System.getProperty("coop.name", defaultName())));
        lastIp = p.getProperty("lastIp", "").trim();
        try {
            port = Integer.parseInt(p.getProperty("port", "7777").trim());
        } catch (NumberFormatException e) {
            port = 7777;
        }
        try {
            leash = Integer.parseInt(p.getProperty("leash", "0").trim());
        } catch (NumberFormatException e) {
            leash = 0;
        }
        showOwnName = Boolean.parseBoolean(p.getProperty("showOwnName", "false").trim());
        if (System.getProperty("coop.name") != null) {
            name = clean(System.getProperty("coop.name"));
        }
    }

    public static void save() {
        Properties p = new Properties();
        p.setProperty("name", name);
        p.setProperty("lastIp", lastIp);
        p.setProperty("port", String.valueOf(port));
        p.setProperty("leash", String.valueOf(leash));
        p.setProperty("showOwnName", String.valueOf(showOwnName));
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
                p.store(w, "Dread Dawn Coop");
            }
        } catch (Exception e) {
            Log.error("Config.save", e);
        }
    }

    static String defaultName() {
        String u = System.getProperty("user.name", "Spieler");
        return u.isBlank() ? "Spieler" : u;
    }

    public static String clean(String s) {
        if (s == null) {
            return "Spieler";
        }
        s = s.replaceAll("[\\r\\n\\t]", " ").trim();
        if (s.length() > 20) {
            s = s.substring(0, 20);
        }
        return s.isEmpty() ? "Spieler" : s;
    }
}
