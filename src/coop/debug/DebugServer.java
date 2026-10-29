package coop.debug;

import coop.Coop;
import coop.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javax.imageio.ImageIO;

/**
 * Test remote control (only started with -Dcoop.debugPort=NNNN, bound to 127.0.0.1).
 * Lets the developer drive the game window without touching the real mouse/keyboard.
 *
 * Commands (one per line):
 *   key CODE [down|up|tap]      e.g. "key SPACE tap", "key W down"
 *   move X Y                    mouse move in window coordinates
 *   click X Y [left|right]      press+release
 *   press X Y [left|right] / release X Y [left|right]
 *   scroll up|down
 *   shot FILE.png               snapshot of the window content
 *   get NAME[.idx[.idx]]        read a static field of Game.MyPanel (or Class.field)
 *   set NAME[.idx] VALUE        write a static field (int/double/boolean/String)
 *   eval js-like is not supported
 */
public final class DebugServer extends Thread {
    private final int port;

    private DebugServer(int port) {
        super("coop-debug");
        this.port = port;
        setDaemon(true);
    }

    public static void startIfEnabled() {
        String p = System.getProperty("coop.debugPort");
        if (p == null) {
            return;
        }
        try {
            new DebugServer(Integer.parseInt(p.trim())).start();
        } catch (Exception e) {
            Log.error("DebugServer", e);
        }
    }

    @Override
    public void run() {
        try (ServerSocket ss = new ServerSocket(port, 4, InetAddress.getLoopbackAddress())) {
            Log.info("Debug-Steuerung auf 127.0.0.1:" + port);
            while (true) {
                Socket s = ss.accept();
                Thread t = new Thread(() -> handle(s), "coop-debug-client");
                t.setDaemon(true);
                t.start();
            }
        } catch (Exception e) {
            Log.error("DebugServer.run", e);
        }
    }

    private void handle(Socket s) {
        try (s; BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(s.getOutputStream(), true, StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                String res;
                try {
                    res = exec(line);
                } catch (Throwable t) {
                    res = "ERR " + t;
                }
                out.println(res);
                out.println(".");
            }
        } catch (Exception ignored) {
            // client closed
        }
    }

    private static Scene scene() {
        return Coop.stage.getScene();
    }

    private static String fx(Runnable r) throws Exception {
        CountDownLatch l = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                err.set(t);
            } finally {
                l.countDown();
            }
        });
        if (!l.await(20, TimeUnit.SECONDS)) {
            return "TIMEOUT";
        }
        if (err.get() != null) {
            return "ERR " + err.get();
        }
        return "OK";
    }

    private static MouseButton button(String s) {
        if (s == null) {
            return MouseButton.PRIMARY;
        }
        switch (s) {
            case "right":
                return MouseButton.SECONDARY;
            case "middle":
                return MouseButton.MIDDLE;
            default:
                return MouseButton.PRIMARY;
        }
    }

    /** topmost visible node under the scene point (like a real mouse click would hit) */
    private static javafx.scene.Node pick(javafx.scene.Node n, double x, double y) {
        if (n == null || !n.isVisible() || n.isMouseTransparent()) {
            return null;
        }
        if (n instanceof javafx.scene.Parent) {
            java.util.List<javafx.scene.Node> kids = ((javafx.scene.Parent) n).getChildrenUnmodifiable();
            for (int i = kids.size() - 1; i >= 0; i--) {
                javafx.scene.Node hit = pick(kids.get(i), x, y);
                if (hit != null) {
                    return hit;
                }
            }
        }
        javafx.geometry.Point2D p = n.sceneToLocal(x, y);
        if (p != null && n.contains(p) && !(n instanceof javafx.scene.Group) && !(n instanceof javafx.scene.canvas.Canvas)) {
            return n;
        }
        return null;
    }

    private static void mouse(javafx.event.EventType<MouseEvent> type, double x, double y, MouseButton b) {
        Scene sc = scene();
        double sx = Coop.stage.getX() + x;
        double sy = Coop.stage.getY() + y;
        boolean down = type == MouseEvent.MOUSE_PRESSED || type == MouseEvent.MOUSE_DRAGGED;
        javafx.scene.Node target = pick(sc.getRoot(), x, y);
        MouseEvent ev = new MouseEvent(type, x, y, sx, sy, b, 1, false, false, false, false,
                down && b == MouseButton.PRIMARY, false, down && b == MouseButton.SECONDARY, false, false, true, null);
        if (target != null) {
            Event.fireEvent(target, ev.copyFor(target, target));
            if (type == MouseEvent.MOUSE_RELEASED) {
                MouseEvent click = new MouseEvent(MouseEvent.MOUSE_CLICKED, x, y, sx, sy, b, 1, false, false, false, false,
                        false, false, false, false, false, true, null);
                Event.fireEvent(target, click.copyFor(target, target));
            }
        } else {
            Event.fireEvent(sc, ev);
        }
    }

    private static void key(KeyCode code, boolean down) {
        Scene sc = scene();
        KeyEvent ev = new KeyEvent(down ? KeyEvent.KEY_PRESSED : KeyEvent.KEY_RELEASED, "", "", code,
                code == KeyCode.SHIFT && down, code == KeyCode.CONTROL && down, code == KeyCode.ALT && down, false);
        javafx.scene.Node f = sc.getFocusOwner();
        if (f != null) {
            Event.fireEvent(f, ev.copyFor(f, f));
        } else {
            Event.fireEvent(sc, ev);
        }
    }

    private static void typeText(String text) {
        Scene sc = scene();
        javafx.scene.Node f = sc.getFocusOwner();
        if (f instanceof javafx.scene.control.TextInputControl) {
            ((javafx.scene.control.TextInputControl) f).replaceSelection(text);
        }
    }

    static String exec(String line) throws Exception {
        String[] a = line.split("\\s+");
        switch (a[0]) {
            case "ping":
                return "pong";
            case "key": {
                KeyCode code = KeyCode.valueOf(a[1].toUpperCase());
                String mode = a.length > 2 ? a[2] : "tap";
                return fx(() -> {
                    if (!mode.equals("up")) {
                        key(code, true);
                    }
                    if (!mode.equals("down")) {
                        key(code, false);
                    }
                });
            }
            case "type": {
                String text = line.substring(5);
                return fx(() -> typeText(text));
            }
            case "selectall":
                return fx(() -> {
                    javafx.scene.Node f = scene().getFocusOwner();
                    if (f instanceof javafx.scene.control.TextInputControl) {
                        ((javafx.scene.control.TextInputControl) f).selectAll();
                    }
                });
            case "move": {
                double x = Double.parseDouble(a[1]);
                double y = Double.parseDouble(a[2]);
                return fx(() -> mouse(MouseEvent.MOUSE_MOVED, x, y, MouseButton.NONE));
            }
            case "press":
            case "release":
            case "click": {
                double x = Double.parseDouble(a[1]);
                double y = Double.parseDouble(a[2]);
                MouseButton b = button(a.length > 3 ? a[3] : null);
                return fx(() -> {
                    mouse(MouseEvent.MOUSE_MOVED, x, y, MouseButton.NONE);
                    if (!a[0].equals("release")) {
                        mouse(MouseEvent.MOUSE_PRESSED, x, y, b);
                    }
                    if (!a[0].equals("press")) {
                        mouse(MouseEvent.MOUSE_RELEASED, x, y, b);
                    }
                });
            }
            case "scroll": {
                double dy = a[1].equals("up") ? 40 : -40;
                return fx(() -> {
                    ScrollEvent ev = new ScrollEvent(ScrollEvent.SCROLL, 400, 300, 400, 300, false, false, false, false,
                            false, false, 0, dy, 0, dy, ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                            ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null);
                    Event.fireEvent(scene(), ev);
                });
            }
            case "shot": {
                File f = new File(a[1]);
                return fx(() -> {
                    try {
                        WritableImage img = scene().snapshot(null);
                        f.getParentFile().mkdirs();
                        ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", f);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }
            case "get":
                return get(a[1]);
            case "set":
                return set(a[1], line.substring(line.indexOf(a[1]) + a[1].length()).trim());
            case "fields": {
                StringBuilder sb = new StringBuilder();
                Class<?> c = cls(a.length > 1 ? a[1] : "MyPanel");
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) && (a.length < 3 || f.getName().toLowerCase().contains(a[2].toLowerCase()))) {
                        sb.append(f.getName()).append(' ');
                    }
                }
                return sb.toString();
            }
            case "status":
                return coop.debug.Status.text();
            default:
                return coop.Coop.debugCommand(a, line);
        }
    }

    static Class<?> cls(String n) throws ClassNotFoundException {
        if (n.contains(".")) {
            return Class.forName(n);
        }
        return Class.forName("Game." + n);
    }

    /** NAME may be Class.field, field.idx or field.idx.idx; default class MyPanel. Instance fields use Coop.panel. */
    static String get(String spec) throws Exception {
        Object v = resolve(spec, null, false);
        return format(v);
    }

    static String set(String spec, String value) throws Exception {
        resolve(spec, value, true);
        return "OK";
    }

    private static Object resolve(String spec, String value, boolean write) throws Exception {
        String[] p = spec.split("\\.");
        int i = 0;
        Class<?> c = Class.forName("Game.MyPanel");
        if (Character.isUpperCase(p[0].charAt(0)) && p.length > 1) {
            try {
                Class<?> cc = Class.forName("Game." + p[0]);
                Field test = null;
                try {
                    test = Class.forName("Game.MyPanel").getDeclaredField(p[0]);
                } catch (NoSuchFieldException ignored) {
                    // not a MyPanel field
                }
                if (test == null) {
                    c = cc;
                    i = 1;
                }
            } catch (ClassNotFoundException ignored) {
                // field of MyPanel
            }
        }
        Field f = findField(c, p[i]);
        f.setAccessible(true);
        Object owner = Modifier.isStatic(f.getModifiers()) ? null : (c.getName().equals("Game.MyPanel") ? Coop.panel : null);
        Object v = f.get(owner);
        i++;
        // walk into objects: MyPanel.Animation.play -> field "play" of the Animation instance
        while (i < p.length && v != null && !v.getClass().isArray() && !p[i].matches("-?\\d+")) {
            Field g = findField(v.getClass(), p[i]);
            g.setAccessible(true);
            owner = Modifier.isStatic(g.getModifiers()) ? null : v;
            f = g;
            v = g.get(owner);
            i++;
        }
        if (i >= p.length) {
            if (write) {
                f.set(owner, convert(f.getType(), value));
            }
            return v;
        }
        // array indices
        while (i < p.length - 1) {
            v = Array.get(v, Integer.parseInt(p[i]));
            i++;
        }
        int idx = Integer.parseInt(p[i]);
        if (write) {
            Array.set(v, idx, convert(v.getClass().getComponentType(), value));
        }
        return Array.get(v, idx);
    }

    private static Field findField(Class<?> c, String name) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                return k.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // try super class
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object convert(Class<?> t, String s) {
        if (t == int.class) {
            return Integer.parseInt(s);
        }
        if (t == double.class) {
            return Double.parseDouble(s);
        }
        if (t == float.class) {
            return Float.parseFloat(s);
        }
        if (t == boolean.class) {
            return Boolean.parseBoolean(s);
        }
        if (t == byte.class) {
            return Byte.parseByte(s);
        }
        if (t == short.class) {
            return Short.parseShort(s);
        }
        if (t == long.class) {
            return Long.parseLong(s);
        }
        return s;
    }

    private static String format(Object v) {
        if (v == null) {
            return "null";
        }
        if (v.getClass().isArray()) {
            int n = Array.getLength(v);
            StringBuilder sb = new StringBuilder("[" + n + "] ");
            for (int k = 0; k < Math.min(n, 40); k++) {
                Object e = Array.get(v, k);
                sb.append(e != null && e.getClass().isArray() ? "[..]" : String.valueOf(e)).append(' ');
            }
            return sb.toString();
        }
        return String.valueOf(v);
    }
}
