package coop;

import java.util.HashMap;
import java.util.Map;

/**
 * Texts the players see. The code uses the English text as the key; while the German language pack
 * is active (see coop.Lang) the German version is shown instead. {0}, {1} ... are replaced by the arguments.
 */
public final class T {
    private T() {}

    /** true while the German language pack is active */
    public static volatile boolean german;

    private static final Map<String, String> DE = new HashMap<>();

    public static String s(String en, Object... args) {
        String t = en;
        if (german) {
            String d = DE.get(en);
            if (d != null) {
                t = d;
            }
        }
        for (int i = 0; i < args.length; i++) {
            t = t.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return t;
    }

    private static void de(String en, String de) {
        DE.put(en, de);
    }

    static {
        // connection and menu
        de("Starting server on port {0} ...", "Starte Server auf Port {0} ...");
        de("Waiting for your partner ... ({0} : {1})", "Warte auf Mitspieler ... ({0} : {1})");
        de("A partner is already connected.", "Es ist bereits ein Mitspieler verbunden.");
        de("Your partner is connecting ...", "Mitspieler verbindet sich ...");
        de("Server error: {0} (is port {1} in use?)", "Server-Fehler: {0} (Port {1} belegt?)");
        de("Connecting to {0}:{1} ...", "Verbinde mit {0}:{1} ...");
        de("Connected, logging in ...", "Verbunden, melde an ...");
        de("Connected to {0}", "Verbunden mit {0}");
        de("Partner disconnected ({0}). Waiting for your partner ...", "Mitspieler getrennt ({0}). Warte auf Mitspieler ...");
        de("Connection to the host lost: {0}", "Verbindung zum Host verloren: {0}");
        de("Different co-op mod version (host {0} #{1}, you {2} #{3}). Both players need the same coop-mod.jar.",
                "Unterschiedliche Coop-Mod-Version (Host {0} #{1}, du {2} #{3}). Bitte beide dieselbe coop-mod.jar benutzen.");
        de("Different game version (host {0}, you {1}).", "Unterschiedliche Spielversion (Host {0}, du {1}).");
        de("Partner rejected: {0}", "Mitspieler abgewiesen: {0}");
        de("{0} joined", "{0} ist beigetreten");
        de("Connected to {0} - waiting for the host's world ...", "Verbunden mit {0} - warte auf die Welt des Hosts ...");
        de("Rejected: {0}", "Abgewiesen: {0}");
        de("The host ended the game ({0})", "Der Host hat das Spiel beendet ({0})");
        de("Your partner left the game ({0})", "Dein Mitspieler hat das Spiel verlassen ({0})");
        de("World sent to {0}, waiting until it is loaded ...", "Welt an {0} gesendet, warte bis sie geladen ist ...");
        de("Receiving the host's world ...", "Empfange die Welt des Hosts ...");
        de("{0} is in the game", "{0} ist im Spiel");
        de("Error unpacking the world: {0}", "Fehler beim Entpacken der Welt: {0}");
        de("Loading the host's world ...", "Lade die Welt des Hosts ...");
        de("Co-op: stop hosting", "Coop: Hosten beenden");
        de("Host co-op", "Coop hosten");
        de("Co-op: disconnect", "Coop: Trennen");
        de("Join co-op", "Coop beitreten");
        de("Your IP for your friend: ", "Deine IP für den Freund: ");
        de("(Radmin VPN addresses start with 26.)", "(Radmin-VPN-Adressen beginnen mit 26.)");
        de("Host a co-op game", "Coop-Spiel hosten");
        de("Join a co-op game", "Coop-Spiel beitreten");
        de("Your name:", "Dein Name:");
        de("e.g. 26.123.45.67", "z. B. 26.123.45.67");
        de("Host IP:", "IP des Hosts:");
        de("Both players need Radmin VPN in the same network.\nThe host has to choose \"Host co-op\" first.",
                "Beide brauchen Radmin VPN im selben Netzwerk.\nDer Host muss zuerst \"Coop hosten\" wählen.");
        de("Host", "Hosten");
        de("Connect", "Verbinden");
        de("Cancel", "Abbrechen");
        de("Co-op host started. Now choose Continue, New Game or Load.",
                "Coop-Host gestartet. Wähle jetzt Fortsetzen, Neues Spiel oder Laden.");
        de("Please enter the host's IP.", "Bitte die IP des Hosts eingeben.");
        // in game
        de("Co-op: ", "Coop: ");
        de(" (dead)", " (tot)");
        de("Waiting for the host ...", "Warte auf den Host ...");
        de("   - spectating", "   - Zuschauermodus");
        de("Waiting for {0} (Space) ...", "Warte auf {0} (Leertaste) ...");
        de("{0} is ready - press Space", "{0} ist bereit - Leertaste drücken");
        de("The host starts the trip ...", "Die Reise wird beim Host gestartet ...");
        de("{0} goes to {1} - you travel together.", "{0} geht nach {1} - ihr reist gemeinsam.");
        de("{0} starts the siege now.", "{0} ruft die Belagerung jetzt.");
        de("Time only passes when {0} sleeps too.", "Die Zeit vergeht erst, wenn auch {0} schläft.");
        de("{0} is sleeping - time passes when you sleep too.", "{0} schläft - die Zeit vergeht, wenn du auch schläfst.");
        de("Mini-games are not available in co-op.", "Minispiele gehen im Coop nicht.");
        // down, revive, respawn
        de("You are down! {0} can revive you with E.", "Du liegst am Boden! {0} kann dich mit E wiederbeleben.");
        de("You are down! {0} can revive you.", "Du liegst am Boden! {0} kann dich wiederbeleben.");
        de("You are dead and watch {0}. You come back at the next map change or cutscene.",
                "Du bist tot und schaust {0} zu. Du kommst beim nächsten Kartenwechsel oder bei der nächsten Zwischensequenz zurück.");
        de("You are back in the game.", "Du bist wieder im Spiel.");
        de("{0} is down! Go to him and press E to revive him.", "{0} liegt am Boden! Lauf hin und drück E, um ihn wiederzubeleben.");
        de("{0} is back in the game.", "{0} ist wieder im Spiel.");
        de("{0} is dead and watches you. He comes back at the next map change or cutscene.",
                "{0} ist tot und schaut dir zu. Er kommt beim nächsten Kartenwechsel oder bei der nächsten Zwischensequenz zurück.");
        de("{0} revived you.", "{0} hat dich wiederbelebt.");
        de("You revived {0}.", "Du hast {0} wiederbelebt.");
        de("[E] Revive", "[E] Wiederbeleben");
        // saving
        de("The game is saved on the host ...", "Das Spiel wird beim Host gespeichert ...");
        de("The host saved the game (slot {0}).", "Der Host hat das Spiel gespeichert (Slot {0}).");
        de("The host saved the game (autosave).", "Der Host hat das Spiel gespeichert (Autosave).");
        de("The host cannot save right now (a mission or event is running).",
                "Der Host kann gerade nicht speichern (eine Mission oder ein Ereignis läuft).");
        de("{0} saved the game (slot {1}).", "{0} hat das Spiel gespeichert (Slot {1}).");
        de("{0} saved the game (autosave).", "{0} hat das Spiel gespeichert (Autosave).");
    }
}
