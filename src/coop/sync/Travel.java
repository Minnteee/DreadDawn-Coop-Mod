package coop.sync;

import Game.MyPanel;
import coop.Coop;
import coop.Log;
import coop.net.Msg;
import coop.net.Net;
import coop.net.Proto;

/**
 * Both players always stay on the same map. Map changes are done by the host; when the client
 * walks into an exit, the host travels (with the host's figure) and sends the new map.
 */
public final class Travel {
    private Travel() {}

    private static long lastRequest;
    /** host: executing a travel the client asked for */
    private static volatile boolean remoteTravel;
    private static boolean savedDeathed;

    static boolean busy() {
        return remoteTravel;
    }

    /** Hook at the start of MyPanel.travel(map, id, x, y). true = skip. */
    public static boolean travel(String map, int id, int x, int y) {
        if (!Coop.playing()) {
            return false;
        }
        if (Net.isClient()) {
            long now = System.currentTimeMillis();
            if (now - lastRequest > 3000) {
                lastRequest = now;
                Net.send(new Msg(Proto.TRAVEL_REQUEST).str(map).i32(id).i32(x).i32(y));
                Coop.message("Reise wird beim Host gestartet ...");
                Log.info("Reise angefragt: " + map + id);
            }
            return true;
        }
        return false;
    }

    /**
     * Hook at the start of MyPanel.traveling(map, x, y, id): the actual map switch. Walking into a
     * map exit or using a door/stair/hole does not go through travel(), only through this.
     */
    public static boolean travelingHead(String map, int x, int y, int id) {
        if (!Net.isClient() || !Coop.clientInWorld || !Net.connected()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastRequest > 3000) {
            lastRequest = now;
            Net.send(new Msg(Proto.TRAVEL_REQUEST).str(map).i32(id).i32(x).i32(y));
            Coop.message("Reise wird beim Host gestartet ...");
            Log.info("Kartenwechsel angefragt: " + map + id + " (" + x + "," + y + ")");
        }
        return true;
    }

    public static void handle(Msg m) {
        String map = m.str();
        int id = m.i32();
        int x = m.i32();
        int y = m.i32();
        if (!Net.isHost()) {
            return;
        }
        Coop.runSim(() -> {
            if (!Coop.inGame()) {
                return;
            }
            Log.info("Client moechte reisen: " + map + id + " -> Host reist mit");
            Coop.message(Net.peerName + " geht nach " + map + " - ihr reist gemeinsam.");
            remoteTravel = true;
            // a dead host would block the map change; the dead state comes back afterwards
            savedDeathed = MyPanel.deathed;
            MyPanel.deathed = false;
            MyPanel.travel(map, id, x, y);
        });
    }

    /** Host, end of traveling(): the client needs the new map. */
    public static void afterTraveling() {
        if (remoteTravel) {
            remoteTravel = false;
            if (savedDeathed) {
                MyPanel.deathed = true;
            }
        }
        if (Net.isHost() && Net.connected()) {
            WorldTransfer.requestSend("Kartenwechsel", true);
        }
    }
}
