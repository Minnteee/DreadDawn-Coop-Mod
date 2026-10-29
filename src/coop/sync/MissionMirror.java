package coop.sync;

import Game.MyPanel;
import coop.Coop;
import coop.Log;

/** Client: shows the host's current mission goal (the host alone advances the story). */
final class MissionMirror {
    private MissionMirror() {}

    static void refresh(boolean newMission) {
        Coop.runSim(() -> {
            try {
                if (MyPanel.Direction == null) {
                    return;
                }
                if (newMission) {
                    MyPanel.Direction.getmissionoption = false; // load the options of the new mission
                }
                MyPanel.Direction.getmission();
                MyPanel.Direction.findmissionhead();
                if (MyPanel.Direction.targetnow > -1) {
                    MyPanel.Direction.getpoint();
                }
            } catch (Throwable t) {
                Log.error("MissionMirror", t);
            }
        });
    }
}
