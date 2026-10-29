package coop.sync;

import Game.MyPanel;
import coop.Coop;
import coop.Log;
import coop.net.Msg;
import coop.net.Net;
import coop.net.Proto;
import java.util.ArrayList;
import java.util.List;

/**
 * Speech bubbles over figures (Speak.speak(id, text)): NPC lines and story lines from the host,
 * and the players' own lines, are shown on the other machine too.
 */
public final class SpeechSync {
    private SpeechSync() {}

    private static final ThreadLocal<Boolean> applying = ThreadLocal.withInitial(() -> false);
    private static final List<Object[]> pending = new ArrayList<>();

    /** Hook at the start of Speak.speak(int, String). */
    public static void onSpeak(int id, String word) {
        if (word == null || applying.get() || !Coop.playing()) {
            return;
        }
        boolean send = id == 300 || (Net.isHost() && id >= 0 && id < Puppet.K);
        if (!send) {
            return;
        }
        synchronized (pending) {
            if (pending.size() < 64) {
                pending.add(new Object[] {id, word});
            }
        }
    }

    /** End of the frame: the game sets the bubble's time and size right after Speak.speak(). */
    public static void flush() {
        List<Object[]> out;
        synchronized (pending) {
            if (pending.isEmpty()) {
                return;
            }
            out = new ArrayList<>(pending);
            pending.clear();
        }
        for (Object[] p : out) {
            int id = (Integer) p[0];
            Net.send(new Msg(Proto.SPEAK, 64).i16(id).str((String) p[1]).f32(MyPanel.speaktime[id])
                    .f64(MyPanel.speaksize[id]));
        }
    }

    public static void handle(Msg m) {
        int id = m.i16();
        String word = m.str();
        float time = m.f32();
        double size = m.f64();
        int target;
        if (id == 300) {
            target = Puppet.K; // the other player's own line: over their figure
        } else if (Net.isClient() && id >= 0 && id < Puppet.K) {
            target = id;
        } else {
            return;
        }
        Coop.runSim(() -> {
            if (MyPanel.Speak == null || (target == Puppet.K && !Puppet.active())) {
                return;
            }
            applying.set(true);
            try {
                MyPanel.Speak.speak(target, word);
                MyPanel.speaktime[target] = time > 0 ? time : 300f;
                MyPanel.speaksize[target] = size > 0 ? size : MyPanel.speaksizemax;
            } catch (Throwable t) {
                Log.error("Sprechblase", t);
            } finally {
                applying.set(false);
            }
        });
    }
}
