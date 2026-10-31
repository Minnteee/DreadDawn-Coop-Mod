package coop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * German language pack: when the folder "deutsch" (with the translated "Word" texts) is in the game
 * folder, every English text file the game opens is read from the German copy instead (same relative
 * path below "deutsch"). Files without a German copy stay English. The original files are never changed.
 * Start option -Dcoop.lang=en turns the pack off.
 */
public final class Lang {
    private Lang() {}

    public static final String DIR = "deutsch";

    private static volatile boolean checked;
    private static volatile boolean active;
    private static Path root;
    private static Path packRoot;
    private static final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    /** true when the German pack is installed and not switched off */
    public static boolean active() {
        if (!checked) {
            init();
        }
        return active;
    }

    private static synchronized void init() {
        if (checked) {
            return;
        }
        try {
            String r = System.getProperty("coop.root");
            if (r == null || r.isBlank()) {
                r = Path.of("").toAbsolutePath().toString().replace("Word\\Etc\\doot\\effc\\MyPanel", "");
            }
            root = Path.of(r).toAbsolutePath().normalize();
            packRoot = root.resolve(DIR);
            active = !"en".equalsIgnoreCase(System.getProperty("coop.lang", ""))
                    && Files.isDirectory(packRoot.resolve("Word"));
        } catch (Throwable t) {
            active = false;
        }
        T.german = active;
        checked = true;
        System.out.println("[lang] German pack " + (active ? "active: " + packRoot : "not active"));
    }

    /** ARG_FILTER hook: called with every path right before the game opens a file */
    public static String map(String path) {
        if (path == null || !active()) {
            return path;
        }
        String c = cache.get(path);
        if (c != null) {
            return c;
        }
        String result = path;
        try {
            if (isEnglishText(path)) {
                Path abs = Path.of(path);
                if (!abs.isAbsolute()) {
                    abs = Path.of("").toAbsolutePath().resolve(abs);
                }
                abs = abs.normalize();
                if (abs.startsWith(root)) {
                    Path rel = root.relativize(abs);
                    if (rel.getNameCount() > 1 && rel.getName(0).toString().equalsIgnoreCase("Word")) {
                        Path de = packRoot.resolve(rel);
                        if (Files.isRegularFile(de)) {
                            result = de.toString();
                        }
                    }
                }
            }
        } catch (Throwable t) {
            result = path;
        }
        cache.put(path, result);
        return result;
    }

    /** LDC_STRING hook: the few player-visible texts that are written in the code, not in the text files */
    public static String ldc(String s) {
        if (s == null || !active()) {
            return s;
        }
        switch (s) {
            case "English":
                return "Deutsch"; // the language list shows the language the pack replaces English with
            case " (Day ":
                return " (Tag ";
            case "ms\nLOADING...":
                return "ms\nLADE ...";
            default:
                return s;
        }
    }

    /** the English variant of a language dependent text file: ...\E.txt or ...-E.txt */
    static boolean isEnglishText(String p) {
        int n = p.length();
        if (n < 6 || !p.regionMatches(true, n - 4, ".txt", 0, 4)) {
            return false;
        }
        char l = p.charAt(n - 5);
        char before = p.charAt(n - 6);
        return (l == 'E' || l == 'e') && (before == '-' || before == '\\' || before == '/' || before == '_');
    }
}
