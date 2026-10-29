package coop.ui;

import Game.MyPanel;
import coop.Config;
import coop.Coop;
import coop.Log;
import coop.net.Net;
import java.util.Arrays;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/** Two extra main menu entries ("Coop hosten", "Coop beitreten") and the small input dialog. */
public final class MenuUI {
    private MenuUI() {}

    static final int ROW_HOST = 6;
    static final int ROW_JOIN = 7;

    private static VBox dialog;
    private static TextField nameField;
    private static TextField ipField;
    private static TextField portField;
    private static Label info;
    private static boolean dialogHost;

    /** After the game (re)loaded its menu texts: add our two rows. */
    public static void afterWord() {
        String[] o = MyPanel.pageoption0;
        if (o == null) {
            return;
        }
        if (o.length < 8) {
            o = Arrays.copyOf(o, 8);
        }
        MyPanel.pageoption0 = o;
        updateLabels();
    }

    static void updateLabels() {
        String[] o = MyPanel.pageoption0;
        if (o == null || o.length < 8) {
            return;
        }
        o[ROW_HOST] = Net.isHost() ? "Coop: Hosten beenden" : "Coop hosten";
        o[ROW_JOIN] = Net.isClient() ? "Coop: Trennen" : "Coop beitreten";
    }

    private static int hoverRow() {
        MyPanel p = Coop.panel;
        int mx = MyPanel.moux;
        int my = MyPanel.mouy;
        if (mx <= p.menux || mx >= p.menux + p.menulengthx) {
            return -1;
        }
        for (int x = 0; x < MyPanel.pageoption0.length; x++) {
            if (my > p.menuy + p.menublocky * (x - 1) && my < p.menuy + p.menublocky * x) {
                return x;
            }
        }
        return -1;
    }

    /** Before page0() handles the click. */
    public static void page0Head() {
        if (MyPanel.pageoption0 == null || MyPanel.pageoption0.length < 8) {
            afterWord();
        }
        updateLabels();
        if (dialog != null) {
            MyPanel.click = false; // the dialog has the mouse
            return;
        }
        if (!MyPanel.click || MyPanel.confirmviewed) {
            return;
        }
        int row = hoverRow();
        if (row == ROW_HOST) {
            MyPanel.click = false;
            if (Net.isHost()) {
                Net.stop("Host beendet");
            } else {
                openDialog(true);
            }
        } else if (row == ROW_JOIN) {
            MyPanel.click = false;
            if (Net.isClient()) {
                Net.stop("Verbindung getrennt");
            } else {
                openDialog(false);
            }
        }
    }

    /** FX thread, every frame. */
    public static void fxFrame() {
        if (dialog == null) {
            return;
        }
        if (MyPanel.page != 0) {
            closeDialog();
            return;
        }
        if (Coop.panel != null && Coop.panel.ESC) {
            Coop.panel.ESC = false;
            closeDialog();
            return;
        }
        if (info != null && dialogHost) {
            info.setText(hostInfo());
        }
    }

    private static String hostInfo() {
        StringBuilder sb = new StringBuilder("Deine IP für den Freund: ");
        java.util.List<String> ips = Net.localAddresses();
        sb.append(ips.isEmpty() ? "?" : String.join("  /  ", ips));
        sb.append("\n(Radmin-VPN-Adressen beginnen mit 26.)");
        return sb.toString();
    }

    static void openDialog(boolean host) {
        closeDialog();
        dialogHost = host;
        double fs = Math.max(12, MyPanel.fovyscreen * 0.022);
        Font font = Font.font(MyPanel.languageFONT[MyPanel.language], FontWeight.BOLD, fs);
        String style = "-fx-background-color: rgba(10,10,10,0.88); -fx-background-radius: 10; -fx-border-color: rgba(150,150,150,0.6);"
                + " -fx-border-radius: 10; -fx-border-width: 2;";
        String fieldStyle = "-fx-background-color: rgba(40,40,40,0.95); -fx-text-fill: #dddddd; -fx-background-radius: 6;"
                + " -fx-border-color: rgba(120,120,120,0.7); -fx-border-radius: 6;";
        String btnStyle = "-fx-background-color: rgba(70,25,25,0.95); -fx-text-fill: #eeeeee; -fx-background-radius: 6;";

        Label title = new Label(host ? "Coop-Spiel hosten" : "Coop-Spiel beitreten");
        title.setFont(Font.font(MyPanel.languageFONT[MyPanel.language], FontWeight.BOLD, fs * 1.3));
        title.setStyle("-fx-text-fill: #e0e0e0;");

        nameField = field(Config.name, fieldStyle, font);
        portField = field(String.valueOf(Config.port), fieldStyle, font);
        VBox box = new VBox(fs * 0.6);
        box.getChildren().add(title);
        box.getChildren().add(row("Dein Name:", nameField, font));
        if (!host) {
            ipField = field(Config.lastIp, fieldStyle, font);
            ipField.setPromptText("z.B. 26.123.45.67");
            box.getChildren().add(row("IP des Hosts:", ipField, font));
        } else {
            ipField = null;
        }
        box.getChildren().add(row("Port:", portField, font));
        info = new Label(host ? hostInfo() : "Beide brauchen Radmin VPN im selben Netzwerk.\nDer Host muss zuerst \"Coop hosten\" wählen.");
        info.setFont(Font.font(MyPanel.languageFONT[MyPanel.language], FontWeight.NORMAL, fs * 0.8));
        info.setStyle("-fx-text-fill: #aaaaaa;");
        info.setWrapText(true);
        box.getChildren().add(info);

        Button ok = new Button(host ? "Hosten" : "Verbinden");
        Button cancel = new Button("Abbrechen");
        for (Button b : new Button[] {ok, cancel}) {
            b.setFont(font);
            b.setStyle(btnStyle);
            b.setMinWidth(fs * 7);
        }
        ok.setOnAction(e -> confirm());
        cancel.setOnAction(e -> closeDialog());
        HBox buttons = new HBox(fs, ok, cancel);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        box.getChildren().add(buttons);

        box.setPadding(new Insets(fs));
        box.setStyle(style);
        double w = Math.max(320, MyPanel.fovxscreen * 0.36);
        box.setPrefWidth(w);
        box.setMaxWidth(w);
        box.setLayoutX((MyPanel.fovxscreen - w) / 2.0);
        box.setLayoutY(MyPanel.fovyscreen * 0.30);
        box.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> e.consume());
        box.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                confirm();
            }
        });
        dialog = box;
        MyPanel.root.getChildren().add(box);
        (host ? nameField : (ipField.getText().isEmpty() ? ipField : nameField)).requestFocus();
    }

    private static TextField field(String text, String style, Font font) {
        TextField f = new TextField(text);
        f.setFont(font);
        f.setStyle(style);
        HBoxGrow(f);
        return f;
    }

    private static void HBoxGrow(TextField f) {
        HBox.setHgrow(f, javafx.scene.layout.Priority.ALWAYS);
    }

    private static HBox row(String label, TextField f, Font font) {
        Label l = new Label(label);
        l.setFont(font);
        l.setStyle("-fx-text-fill: #cccccc;");
        l.setMinWidth(font.getSize() * 7.5);
        HBox h = new HBox(font.getSize() * 0.5, l, f);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private static void confirm() {
        if (dialog == null) {
            return;
        }
        Config.name = Config.clean(nameField.getText());
        try {
            Config.port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            Config.port = 7777;
        }
        if (dialogHost) {
            Config.save();
            Net.host(Config.port);
            Coop.message("Coop-Host gestartet. Wähle jetzt Continue, New Game oder Load.");
            Log.info("Host gestartet von " + Config.name);
        } else {
            String ip = ipField.getText().trim();
            if (ip.isEmpty()) {
                info.setText("Bitte die IP des Hosts eingeben.");
                return;
            }
            Config.lastIp = ip;
            Config.save();
            Net.join(ip, Config.port);
            Log.info("Verbinde mit " + ip + ":" + Config.port + " als " + Config.name);
        }
        closeDialog();
    }

    static void closeDialog() {
        if (dialog != null) {
            MyPanel.root.getChildren().remove(dialog);
            dialog = null;
        }
    }

    public static boolean dialogOpen() {
        return dialog != null;
    }
}
