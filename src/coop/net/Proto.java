package coop.net;

import Game.MyPanel;
import coop.Config;
import coop.Coop;

/** Message type ids. */
public final class Proto {
    private Proto() {}

    // session
    public static final int HELLO = 1;
    public static final int WELCOME = 2;
    public static final int REJECT = 3;
    public static final int PING = 4;
    public static final int PONG = 5;
    public static final int BYE = 6;
    public static final int MESSAGE = 7;

    // world transfer (host -> client)
    public static final int WORLD_BEGIN = 10;
    public static final int WORLD_CHUNK = 11;
    public static final int WORLD_END = 12;
    public static final int WORLD_LOADED = 13; // client -> host
    public static final int WORLD_REQUEST = 14; // client -> host

    // continuous state
    public static final int PLAYER_STATE = 20;
    public static final int ENTITIES = 21;
    public static final int ENV = 22;
    public static final int HUMANS = 23;

    // events
    public static final int SHOT = 30;
    public static final int HIT_ZOMBIE = 31;
    public static final int HIT_HUMAN = 32;
    public static final int DAMAGE_PLAYER = 33;
    public static final int ZOMBIE_EVENT = 34;
    public static final int PLAYER_DEAD = 35;
    public static final int GAME_OVER = 36;
    public static final int TRAVEL_REQUEST = 37;
    public static final int MELEE = 38;
    public static final int GRENADE = 39;
    public static final int EXPLOSION = 40;
    public static final int SOUND = 41;
    public static final int WORLD_EVENT = 42;
    public static final int INVENTORY = 43;
    public static final int CONTAINER_REQ = 44;
    public static final int CONTAINER_DATA = 45;
    public static final int MAPACK = 46;
    public static final int PROFILE = 47;
    public static final int REVIVE = 48;
    public static final int SPEAK = 49;
    public static final int CUT_START = 50;
    public static final int CUT_TIME = 51;
    public static final int CUT_END = 52;
    public static final int DIALOG_START = 53;
    public static final int DIALOG_STEP = 54;
    public static final int STORY_VOTE = 55;
    public static final int PHOTO = 56;
    public static final int STORY_HINT = 57;
    public static final int STORY_SOUND = 58;
    public static final int VEH_STATE = 59;
    public static final int VEH_CLIENT = 60;
    public static final int VEH_EVENT = 61;
    public static final int TEAM = 62;
    public static final int HAZARD = 63;
    public static final int STORY2 = 64;
    public static final int LIFE = 65;
    public static final int GUEST = 66;

    public static Msg hello() {
        return new Msg(HELLO).i32(PROTOCOL_MAGIC).i32(Net.PROTOCOL).str(Coop.VERSION).str(MyPanel.verision).str(Config.name);
    }

    public static final int PROTOCOL_MAGIC = 0x44444350; // "DDCP"
}
