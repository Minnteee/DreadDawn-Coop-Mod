package coop.patcher;

import coop.patcher.GamePatcher.Kind;
import coop.patcher.GamePatcher.Patch;

/** The list of all code changes the co-op mod makes to the game. */
public final class Patches {
    private Patches() {}

    static final String P = "Game/MyPanel";
    static final String STAGE = "Ljavafx/stage/Stage;";
    static final String V = "()V";
    static final String Z = "()Z";

    /** calls that open a text file by a path String (the path is their last argument) */
    static final String[] LANG_FILE_OPEN = {
        "java/io/File.<init>(Ljava/lang/String;)V",
        "java/io/FileInputStream.<init>(Ljava/lang/String;)V",
        "java/io/FileReader.<init>(Ljava/lang/String;)V",
    };

    public static GamePatcher create() {
        return create(false);
    }

    /** langOnly: the German language pack started without the co-op mod (deutsch-mod.jar) */
    public static GamePatcher create(boolean langOnly) {
        GamePatcher g = new GamePatcher();

        // ---- German language pack (coop.Lang): English text files are read from the German copies ----
        for (String t : LANG_FILE_OPEN) {
            g.add(optional(new Patch("*", "*", null, Kind.ARG_FILTER, "coop/Lang.map", "(Ljava/lang/String;)Ljava/lang/String;",
                    false, false, t, false)));
        }
        String ldc = "(Ljava/lang/String;)Ljava/lang/String;";
        g.add(optional(new Patch(P, "<init>", null, Kind.LDC_STRING, "coop/Lang.ldc", ldc, false, false, "English", false)));
        g.add(optional(new Patch("Game/Packview", "*", null, Kind.LDC_STRING, "coop/Lang.ldc", ldc, false, false, " (Day ", false)));
        g.add(optional(new Patch(P, "*", null, Kind.LDC_STRING, "coop/Lang.ldc", ldc, false, false, "LOADING...", false)));
        if (langOnly) {
            return g;
        }

        // ---- window / test setup -------------------------------------------------------
        g.add(redirect(P, "<clinit>", null, "java/awt/Toolkit.getScreenSize()Ljava/awt/Dimension;",
                "screenSize", "(Ljava/awt/Toolkit;)Ljava/awt/Dimension;"));
        g.add(tail("Game/ReaderOption", "<init>", V, "afterReaderOption", V, false, false));
        g.add(head("Game/SaveOption", "<init>", V, "beforeSaveOption", V, false, false));
        g.add(tail("Game/SaveOption", "<init>", V, "afterSaveOption", V, false, false));
        g.add(head(P, "start", "(" + STAGE + ")V", "onStartHead", "(LGame/MyPanel;" + STAGE + ")V", true, true));
        g.add(redirect(P, "start", "(" + STAGE + ")V", "javafx/stage/Stage.setX(D)V", "stageSetX", "(" + STAGE + "D)V"));
        g.add(redirect(P, "start", "(" + STAGE + ")V", "javafx/stage/Stage.setY(D)V", "stageSetY", "(" + STAGE + "D)V"));
        g.add(tail(P, "start", "(" + STAGE + ")V", "onStartTail", "(LGame/MyPanel;" + STAGE + ")V", true, true));
        // never move the real mouse cursor (cutscenes do Robot.mouseMove with screen coordinates)
        g.add(optional(redirect("*", "*", null, "java/awt/Robot.mouseMove(II)V", "robotMouseMove",
                "(Ljava/awt/Robot;II)V")));
        // the "low fps -> use dedicated GPU" check closes the game window; skip it while coop/test is active
        g.add(cancel(P, "checkfps", "(D)V", "skipCheckfps", Z, false, false));

        // ---- safety net: an exception while drawing or simulating must not stop the game loop ----
        g.add(redirect("Game/MyPanel$1", "handle", "(J)V", "Game/MyPanel.draw()V", "safeDraw", "(LGame/MyPanel;)V"));
        g.add(redirect("Game/MyPanel$1", "handle", "(J)V", "Game/MyPanel.game()V", "safeGame", "(LGame/MyPanel;)V"));
        g.add(redirect("Game/MyPanel$2", "run", V, "Game/MyPanel.unity()V", "safeUnity", "(LGame/MyPanel;)V"));
        g.add(optional(redirect("Game/MyPanel$lowfps", "run", V, "Game/MyPanel.game()V", "safeGame", "(LGame/MyPanel;)V")));
        // the other player's figure must not show the "press E" (talk / recruit) prompt
        g.add(head(P, "hint", V, "hintHead", V, false, false));
        g.add(tail(P, "hint", V, "hintTail", V, false, false));

        // ---- frame hooks ------------------------------------------------------------------
        g.add(head(P, "draw", V, "drawHead", V, false, false));
        g.add(tail(P, "draw", V, "drawTail", V, false, false));
        g.add(head(P, "page0", V, "page0Head", V, false, false));
        g.add(tail("Game/Word", "<init>", V, "afterWord", V, false, false));
        g.add(head(P, "page3", V, "page3Head", V, false, false));
        g.add(before(P, "page3", V, "Game/Zone.drawcover()V", "drawWorldOverlay", V, false));
        g.add(head(P, "unity", V, "unityHead", V, false, false));
        g.add(fieldAfter(P, "unity", V, "Game/Bone.boneplay", "fixBoneplay", V));
        g.add(before(P, "collection", V, "Game/MyPanel.packcollection()V", "beforePackCollection", V, false));
        g.add(after(P, "collection", V, "Game/MyPanel.Move()V", "afterMove", V, false));
        g.add(tail(P, "collection", V, "collectionTail", V, false, false));
        g.add(cancel(P, "load", V, "loadHead", Z, false, false));
        g.add(tail(P, "load", V, "loadTail", V, false, false));
        g.add(tail("Game/SaveGame", "<init>", V, "afterSaveGame", V, false, false));

        // ---- remote player puppet (counter 299) is not driven by the AI ------------------------
        // loops "x < 300 + addplayer" skip slot 299 (in the command mode, Q, the own player 300 stays included)
        for (String[] m : new String[][] {{"Game/Function", "bot"}, {P, "Move"}, {P, "runaim"}, {P, "runmove"},
                {"Game/Command", "command"}, {P, "Mouse"}, {P, "clickdialog"}}) {
            g.add(new Patch(m[0], m[1], V, Kind.LOOP_SKIP_SLOT, "skipSlot", "(I)Z", false, false, null, false));
        }
        g.add(new Patch("Game/Humanrespawn", "selecthuman", "()I", Kind.REPLACE_300, "aiLimit", "()I", false, false, null, false));
        // the other player's figure is not knocked down here (kicks, slides): his own machine decides
        g.add(cancel(P, "counterfall", "(IDD)V", "puppetFall", "(IDD)Z", false, true));
        g.add(cancel(P, "counterfallback", "(IDD)V", "puppetFall", "(IDD)Z", false, true));
        // remote player looks like the remote player's character
        g.add(before("Game/Bone", "drawcounter", "(I)V", "Game/MyPanel.lookout_height(I)D", "boneSkin", "(LGame/Bone;)V", true));

        // ---- host: zombies / NPCs near the client stay active (not only near the host's screen) ----
        for (String owner : new String[] {"Game/Zombierespawn", "Game/Humanrespawn"}) {
            for (String target : new String[] {owner + ".inscreenREAL(DDD)Z", "Game/MyPanel.inscreenREAL(DDD)Z"}) {
                g.add(optional(redirect(owner, "fake", "(Z)V", target, "inscreenCoop", "(DDD)Z")));
            }
        }
        // ---- host: the area around the guest's screen is simulated like the host's own screen ----
        // knock-downs, hit effects, pushing apart, slot reuse: "on screen" means on either screen
        String[][] tinUsers = {{P, "gethumaninscreen", V}, {P, "shoot", V}, {P, "explodecreater", "(IIDDDZ)V"},
            {"Game/Zombierespawn", "run", V}, {"Game/Zombierespawn", "fake", "(Z)V"}, {"Game/Zombierespawn", "select", "()I"}};
        for (String[] m : tinUsers) {
            g.add(new Patch(m[0], m[1], m[2], Kind.FIELD_GET, "tinEither", "()[Z", false, false, "tin:[Z", false));
        }
        for (String[] m : new String[][] {{P, "gethumaninscreen", V}, {P, "shoot", V}, {P, "explodecreater", "(IIDDDZ)V"}}) {
            g.add(new Patch(m[0], m[1], m[2], Kind.FIELD_GET, "ctinEither", "()[Z", false, false, "ctin:[Z", false));
        }
        g.add(redirect(P, "gethumaninscreen", V, "Game/MyPanel.inscreenREAL(DDDD)Z", "inscreenCoop4", "(DDDD)Z"));
        // fire and electricity hurt near the guest too
        g.add(redirect("Game/Other", "move", V, "Game/MyPanel.inscreenREAL(DDD)Z", "inscreenCoop", "(DDD)Z"));
        // wandering people near the guest are not removed; new ones never appear in the guest's view
        g.add(redirect("Game/Humanrespawn", "unity", "(Z)V", "Game/Humanrespawn.inrespawnarea(DD)Z", "inrespawnCoop",
                "(LGame/Humanrespawn;DD)Z"));
        for (String owner : new String[] {"Game/Humanrespawn", P}) {
            g.add(optional(redirect("Game/Humanrespawn", "unity", "(Z)V", owner + ".inscreenREAL(DDD)Z", "inscreenCoop", "(DDD)Z")));
            g.add(optional(redirect("Game/Humanrespawn", "takepoint", "(Z)Z", owner + ".inscreenREAL(DDD)Z", "inscreenCoop",
                    "(DDD)Z")));
        }
        // the AI work area covers both screens; rush/mission zombies also run to the guest
        g.add(head("Game/Function", "bot", V, "widenSeeblock", V, false, false));
        g.add(head("Game/Zombierespawn", "unity", V, "widenSeeblock", V, false, false));
        g.add(head("Game/Humanrespawn", "unity", "(Z)V", "widenSeeblock", V, false, false));
        g.add(redirect("Game/Function", "zombielongrun", V, "Game/Function.fillmap(II[[[ZI)I", "longrunFill",
                "(LGame/Function;II[[[ZI)I"));

        // ---- companions and NPC dialogs of the guest (TeamSync) -----------------------------------
        g.add(head("Game/Command", "command", V, "teamCommandHead", V, false, false));
        g.add(head("Game/Dialogview", "dialog", V, "dialogActHead", V, false, false));
        g.add(tail("Game/Dialogview", "dialog", V, "dialogActTail", V, false, false));
        g.add(head("Game/Teamate", "teamate", "(Ljavafx/scene/canvas/GraphicsContext;)V", "teamMenuHead", V, false, false));
        g.add(tail("Game/Teamate", "teamate", "(Ljavafx/scene/canvas/GraphicsContext;)V", "teamMenuTail", V, false, false));

        // ---- client: NPCs never use items here (eat, drink, repair from their bags): the host does it,
        // an NPC bag the guest has open would otherwise lose the item twice
        g.add(new Patch("Game/Use", "unity_counter", V, Kind.LOOP_SKIP_SLOT, "clientSkipNpc", "(I)Z", false, false, "301:15", false));
        g.add(new Patch("Game/Use", "changehandle", V, Kind.LOOP_SKIP_SLOT, "clientSkipNpc", "(I)Z", false, false, null, false));

        // ---- the story with two players (StoryCoop) ---------------------------------------------
        // goals and map events reached by the guest, mission markers started by the guest
        g.add(redirect("Game/Direction", "targetouch", V, "Game/Direction.targetclose(D)Z", "targetcloseCoop",
                "(LGame/Direction;D)Z"));
        g.add(redirect("Game/Direction", "targetouch", V, "Game/Direction.inarea(DDI)Z", "inareaCoop",
                "(LGame/Direction;DDI)Z"));
        g.add(tail("Game/MapThing", "treasure", "(Z)V", "treasureGuestPass", "(Z)V", false, true));
        g.add(cancel("Game/Direction", "startmission", V, "clientStartMission", Z, false, false));
        // story rewards and rests for the guest too
        g.add(head("Game/Direction", "give", "(Ljava/lang/String;)V", "storyGive", "(Ljava/lang/String;)V", false, true));
        g.add(head("Game/Direction", "addtargetnow", V, "storyTargetDone", "(LGame/Direction;)V", true, false));
        // the story and mission modes go on while the host is dead and the guest plays
        g.add(new Patch("Game/Direction", "targetouch", V, Kind.FIELD_GET, "storyDeathed", "()Z", false, false, "deathed:Z", false));
        g.add(new Patch(P, "realtime", V, Kind.FIELD_GET, "realtimeHeal", "()[D", false, false, "ctheal:[D", false));

        // ---- everyday things (LifeSync) ---------------------------------------------------------
        g.add(cancel(P, "bonfireburn", "(I)V", "bonfireBurn", "(I)Z", false, true));
        g.add(tail(P, "getbonfiretime", "(I)V", "bonfireTime", "(I)V", false, true));
        g.add(head(P, "cockfoodcreater", "(IIB)V", "feedHead", "(IIB)V", false, true));
        g.add(redirect("Game/Dialogview", "unity", V, "Game/Dialogview.addhour(I)V", "sleepAddHour", "(I)V"));
        for (String m : new String[] {"pcgame", "football", "cargame", "lover"}) {
            g.add(cancel(P, m, V, "minigameBlock", Z, false, false));
        }
        g.add(head("Game/Craft", "mine", V, "craftMineHead", "(LGame/Craft;)V", true, false));
        g.add(head("Game/Craft", "log", V, "craftLogHead", "(LGame/Craft;)V", true, false));

        // ---- locks, keys, power lines, vents, flame jets, story messages (GuestEdits) -----------
        g.add(tail("Game/Key", "takekey", "(I)V", "keyTaken", "(I)V", false, true));
        g.add(head("Game/Key", "usekey", "(I)Z", "keyUsing", "(I)V", false, true));
        g.add(head("Game/Other", "opendoorwithkey", "(I)V", "doorKey", "(I)V", false, true));
        String touch = "Game/Other.powertouch(I)V";
        g.add(redirect(P, "shoot", V, touch, "powerShot", "(LGame/Other;I)V"));
        g.add(redirect("Game/Other", "powerstickexplode", "(DDD)V", touch, "powerBlast", "(LGame/Other;I)V"));
        g.add(redirect("Game/Other", "powerhit", "(DD)Z", touch, "powerBlast", "(LGame/Other;I)V"));
        String notice = "Game/NoticeView.notice(Ljava/lang/String;)V";
        g.add(redirect(P, "realtime", V, notice, "hostNotice", "(LGame/NoticeView;Ljava/lang/String;)V"));
        g.add(redirect("Game/Direction", "targetouch", V, notice, "hostNotice", "(LGame/NoticeView;Ljava/lang/String;)V"));
        g.add(redirect(P, "realtime", V, "Game/WordAll.hintREAL(Ljava/lang/String;D)V", "hostHint",
                "(LGame/WordAll;Ljava/lang/String;D)V"));
        g.add(redirect(P, "realtime", V, "Game/Direction.newguidance(Ljava/lang/String;IIILjava/lang/String;)Z", "hostGuidance",
                "(LGame/Direction;Ljava/lang/String;IIILjava/lang/String;)Z"));
        g.add(redirect("Game/MapThing", "treasureUSE", "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V",
                "Game/MapThing.direction(Ljava/lang/String;Ljava/lang/String;)Z", "storyDirection",
                "(LGame/MapThing;Ljava/lang/String;Ljava/lang/String;)Z"));
        g.add(head("Game/Mission", "jump_camp", V, "jumpCamp", V, false, false));
        g.add(redirect(P, "flamer", V, "Game/MyPanel.inscreen(DDDD)Z", "flamerInscreen", "(DDDD)Z"));
        g.add(head("Game/Other", "move", V, "otherMoveHead", "(LGame/Other;)V", true, false));
        g.add(redirect("Game/Other", "move", V, "Game/MyPanel.getdistance2(DDDD)D", "nearEither", "(DDDD)D"));

        // ---- explosions, barrels, boomer gas, traps (HazardSync) --------------------------------
        g.add(head(P, "explodecreater", "(IIDDDZ)V", "explodeHead", "(IIDDDZ)V", false, true));
        // explosions of these happen on both machines anyway
        for (String[] m : new String[][] {{P, "grenade"}, {P, "car"}, {P, "barrel"}, {"Game/Animation", "play"}}) {
            g.add(head(m[0], m[1], V, "hazardShared", V, false, false));
            g.add(tail(m[0], m[1], V, "hazardSharedEnd", V, false, false));
        }
        g.add(cancel(P, "boommer", "(I)V", "boomerHead", "(I)Z", false, true));
        g.add(head(P, "barrel", V, "barrelHead", V, false, false));
        g.add(head(P, "barrelcreater", "(DDI)Z", "barrelCreating", "(DDI)V", false, true));
        // traps and their impact never react to the other player's figure (his own machine decides about him)
        for (String[] m : new String[][] {{P, "blade"}, {"Game/Craft", "mine"}, {"Game/Craft", "log"}}) {
            g.add(optional(new Patch(m[0], m[1], V, Kind.LOOP_SKIP_SLOT, "skipSlot", "(I)Z", false, false, "301", false)));
        }
        g.add(head(P, "blade", V, "bladeHead", "(LGame/MyPanel;)V", true, false));
        g.add(head(P, "trapnail", V, "nailHead", "(LGame/MyPanel;)V", true, false));
        g.add(redirect(P, "spiter", V, "Game/MyPanel.spiterdamage(I)Z", "spiterDamage", "(I)Z"));

        // ---- cars and skateboards (VehicleSync) ------------------------------------------------
        g.add(after(P, "collection", V, "Game/MyPanel.car()V", "afterCar", V, false));
        g.add(after(P, "collection", V, "Game/MyPanel.skate()V", "afterSkate", V, false));
        g.add(head(P, "carcreater", "(DDDIIII)I", "carCreating", "(DDDIIII)V", false, true));
        g.add(head(P, "carpickup", "(I)V", "carPickupHead", "(I)V", false, true));
        g.add(tail(P, "carpickup", "(I)V", "carPickupTail", "(I)V", false, true));
        g.add(head(P, "skatepickup", "(I)V", "skatePickupHead", "(I)V", false, true));
        g.add(tail(P, "skatepickup", "(I)V", "skatePickupTail", "(I)V", false, true));

        // ---- the other player as a yellow dot on the minimap and the map (MapMarkers) ----
        g.add(redirect(P, "rad", V, "Game/MyPanel.getradcoordinate(DDIZ)V", "radCoord", "(LGame/MyPanel;DDIZ)V"));
        g.add(fieldAfter(P, "page3", V, "Game/MyPanel.radtime", "radarDrawn", V));
        for (String m : new String[] {"smallmap", "mapview"}) {
            g.add(before("Game/MapView", m, V, "Game/MapView.draw_mission_button(I)V", "mapPartner", "(LGame/MapView;)V", true));
        }
        // a dead player who watches his partner may open the map
        g.add(new Patch(P, "unity", V, Kind.FIELD_GET, "unityDeathed", Z, false, false, "deathed:Z", false));

        // ---- the pause menu does not stop the world while playing together ----
        g.add(head(P, "game", V, "gameHead", V, false, false));

        // ---- client: the host simulates zombies, NPCs, spawns, time and weather -------------
        String[][] noAi = {
            {"Game/Zombierespawn", "unity", V}, {"Game/Zombierespawn", "fake", "(Z)V"},
            {"Game/Humanrespawn", "unity", "(Z)V"}, {"Game/Humanrespawn", "fake", "(Z)V"},
            {"Game/Humanrespawn", "move", V}, {"Game/Humanrespawn", "movecar", V},
            {P, "eye", V}, {P, "runaim", V}, {P, "runmove", V},
            {"Game/Use", "unity_terrorist", V}, {P, "zombierush", "(IIID)V"},
            {"Game/DirectorMode", "director_mode_only", V}, {"Game/L4DMode", "l4d_mode_only", V},
            {"Game/Change", "zombie_into_wall", V},
            {"Game/DirectorMode", "is_time_run", Z}, {"Game/DirectorMode", "is_weather_run", Z},
            // the story / missions advance on the host only
            {"Game/Direction", "targetouch", V},
            {"Game/Direction", "addtargetnow", V}, {"Game/Direction", "savejump", V},
            // map events (story triggers, ambushes) fire on the host only and are sent from there
            {"Game/MapThing", "treasure", "(Z)V"}, {"Game/MapThing", "treasurewait", V},
            {"Game/Change", "jump_to_next_mission", V},
            // barricades and chicken traps act on zombies / chickens: host only (mirrored by WorldSync)
            {P, "checkhinder", V}, {"Game/Other", "cocktrap", V},
            // zombies out of vents / from the ceiling: spawned by the host only (ZombieSync shows them)
            {"Game/Zombierespawn", "pipetop", "(DDI)V"}, {"Game/Zombierespawn", "fall", "(DDB)V"},
        };
        for (String[] m : noAi) {
            g.add(cancel(m[0], m[1], m[2], "clientNoAI", Z, false, false));
        }
        // the AI steering of the own figure in command mode (Q) / build mode still runs on the client (QMode)
        g.add(cancel("Game/Function", "bot", V, "clientBot", "(LGame/Function;)Z", true, false));
        // the clock, sieges, rushes, crops etc. run on the host; the client only checks level-ups
        g.add(cancel(P, "realtime", V, "clientRealtime", Z, false, false));
        // the client never writes save slots
        g.add(cancel(P, "savestock", "(IZ)V", "clientNoSave", Z, false, false));
        g.add(cancel(P, "savestock_cloud", "(IZ)V", "clientNoSave", Z, false, false));

        // ---- down / revive: E next to the downed partner, his body is never looted (DeathSync) ----
        g.add(cancel(P, "opendialog", V, "openDialogHead", Z, false, false));
        g.add(cancel(P, "counterDialog", "(I)Z", "counterDialogHead", "(I)Z", false, true));
        // a save made while down / dead stores the player alive; the save slot in play is remembered (SaveSync)
        g.add(head("Game/Save", "saveOther", "(Z)V", "saveOtherHead", V, false, false));
        g.add(tail("Game/Save", "saveOther", "(Z)V", "saveOtherTail", V, false, false));
        g.add(head("Game/SaveStock", "<init>", "(Ljava/lang/String;Ljava/lang/String;Z)V", "saveStockHead",
                "(Ljava/lang/String;Ljava/lang/String;Z)V", false, true));
        // a dead player may open the save menu; the guest's F5 saves on the host
        g.add(new Patch(P, "page4", V, Kind.FIELD_GET, "page4Deathed", Z, false, false, "deathed:Z", false));
        g.add(head(P, "backfire", V, "backfireHead", "(LGame/MyPanel;)V", true, false));

        // ---- damage routing -------------------------------------------------------------------
        g.add(cancel(P, "ctdamage", "(IDDZZ)V", "ctdamage", "(IDDZZ)Z", false, true));
        g.add(cancel(P, "tdamage", "(IIIDZ)V", "tdamage", "(IIIDZ)Z", false, true));
        g.add(cancel(P, "death", "(I)V", "death", "(I)Z", false, true));

        // ---- story: cutscenes, dialog boxes and pictures for both players ----------------------
        g.add(tail("Game/Animation", "playfore", "(Ljava/lang/String;Z)V", "cutsceneStarted", "(Ljava/lang/String;Z)V", false, true));
        g.add(head("Game/Animation", "play", V, "cutscenePlayHead", V, false, false));
        g.add(tail("Game/HeadDialog", "headDialogfore", "(Ljava/lang/String;II)V", "dialogStarted", "(Ljava/lang/String;II)V", false, true));
        g.add(cancel("Game/HeadDialog", "speak", V, "dialogSpeak", Z, false, false));
        // the client's copy of a scene never creates figures or gives guns: they come from the host
        String playfore = "(Ljava/lang/String;Z)V";
        g.add(redirect("Game/Animation", "playfore", playfore, "Game/Humanrespawn.animation(BB)I", "cutHumanActor",
                "(LGame/Humanrespawn;BB)I"));
        g.add(redirect("Game/Animation", "playfore", playfore, "Game/Zombierespawn.animation(B)I", "cutZombieActor",
                "(LGame/Zombierespawn;B)I"));
        g.add(redirect("Game/Animation", "playfore", playfore, "Game/Humanrespawn.getgunbyname(IID)V", "cutGiveGun",
                "(LGame/Humanrespawn;IID)V"));
        // host: the story waits while a picture, a notice or the inventory is open (as in single player,
        // where the whole world pauses then; in co-op the world keeps running)
        g.add(cancel("Game/Direction", "targetouch", V, "hostStoryHold", Z, false, false));
        // host: map events wait while the guest loads the world (replayed afterwards)
        g.add(cancel("Game/MapThing", "treasure", "(Z)V", "treasureGate", "(Z)Z", false, true));
        g.add(cancel("Game/MapThing", "treasurewait", V, "treasureWaitGate", Z, false, false));
        // "watch or skip this scene?" (asked only on the host): in co-op both watch it
        g.add(redirect("Game/Direction", "targetouch", V, "Game/NoticeView.notice_yesno(Ljava/lang/String;I)V", "storyYesNo",
                "(LGame/NoticeView;Ljava/lang/String;I)V"));
        // the host skips / closes a story dialog: the guest's copy closes too
        g.add(tail("Game/HeadDialog", "close", V, "headDialogClosed", V, false, false));
        // story pictures, hint texts and sounds
        g.add(head("Game/Direction", "photo", "(Ljava/lang/String;)V", "storyPhoto", "(Ljava/lang/String;)V", false, true));
        g.add(head("Game/MapThing", "treasureUSE", "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V",
                "treasureUse", "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V", false, true));
        g.add(redirect("Game/Direction", "targetouch", V, "Game/WordAll.hint(Ljava/lang/String;D)V", "storyHint",
                "(LGame/WordAll;Ljava/lang/String;D)V"));

        // ---- speech bubbles are shown on the other machine too -------------------------------
        g.add(head("Game/Speak", "speak", "(ILjava/lang/String;)V", "speak", "(ILjava/lang/String;)V", false, true));

        // ---- shots (visuals for the other player) -------------------------------------------
        // ghost bullets of the other machine stop at the first hit and only show blood (no damage)
        g.add(optional(new Patch(P, "shoot", V, Kind.SWITCH_ARG, "ghostHit", "(III)I", false, false, "51:53,50", false)));
        g.add(head(P, "shootcreater", "(DDDDDBII)V", "shootcreater", "(LGame/MyPanel;DDDDDBII)V", true, true));

        // ---- world transfer: the client plays the host's world in Save\coop\auto -----------
        g.add(new Patch("*", "*", null, Kind.LDC_STRING, "savePath", "(Ljava/lang/String;)Ljava/lang/String;", false,
                false, "Save\\", false));
        g.add(cancel("Game/SaveHistory", "<init>", V, "clientNoSave", Z, false, false));

        // ---- grenades, molotovs, acid and flames are created on the other machine as well ----
        String[][] creators = {
            {"Game/Use", "unity_counter", "Game/Use.gren", "Game/Use.spiternuming", "Game/Use.flam"},
            {"Game/Use", "unity_terrorist", "Game/Use.spiternuming"},
            {P, "machinegun", "Game/MyPanel.gren", "Game/MyPanel.flam"},
        };
        for (String[] c : creators) {
            g.add(head(c[0], c[1], V, "projMark", V, false, false));
            for (int i = 2; i < c.length; i++) {
                g.add(optional(fieldAfter(c[0], c[1], V, c[i], "projCreated", V)));
            }
        }
        // ---- client: damage to the own player from explosions / fire / acid is decided by the host ----
        for (String[] m : new String[][] {{P, "explodecreater", "(IIDDDZ)V"}, {P, "spiter", V}, {P, "flame", V},
                {P, "smokeblood", V}, {"Game/Other", "move", V}}) {
            g.add(head(m[0], m[1], m[2], "envEnter", V, false, false));
            g.add(tail(m[0], m[1], m[2], "envExit", V, false, false));
        }

        // ---- world objects: the host rolls loot; doors used by the client are switched on the host ----
        g.add(cancel(P, "caseDialog", "(I)V", "clientCaseDialog", "(I)Z", false, true));
        g.add(cancel(P, "terroristDialog", "(I)V", "clientTerroristDialog", "(I)Z", false, true));
        g.add(cancel(P, "dropDialog", "(I)V", "clientDropDialog", "(I)Z", false, true));
        g.add(tail("Game/Other", "opendoor", "(I)V", "afterOpenDoor", "(I)V", false, true));

        // ---- travel together ----------------------------------------------------------------
        g.add(cancel(P, "travel", "(Ljava/lang/String;III)V", "travel", "(Ljava/lang/String;III)Z", false, true));
        // every map switch (exits, doors, stairs, holes, world map, story) ends in traveling():
        // the client never switches alone, the host travels and sends the new map
        g.add(cancel(P, "traveling", "(Ljava/lang/String;III)V", "travelingHead", "(Ljava/lang/String;III)Z", false, true));
        g.add(tail(P, "traveling", "(Ljava/lang/String;III)V", "afterTraveling", V, false, false));

        return g;
    }

    // ---- helpers ------------------------------------------------------------------------

    static Patch head(String owner, String m, String desc, String hook, String hookDesc, boolean passThis, boolean passArgs) {
        return new Patch(owner, m, desc, Kind.HEAD, hook, hookDesc, passThis, passArgs, null, false);
    }

    static Patch cancel(String owner, String m, String desc, String hook, String hookDesc, boolean passThis, boolean passArgs) {
        return new Patch(owner, m, desc, Kind.HEAD_CANCEL, hook, hookDesc, passThis, passArgs, null, false);
    }

    static Patch tail(String owner, String m, String desc, String hook, String hookDesc, boolean passThis, boolean passArgs) {
        return new Patch(owner, m, desc, Kind.TAIL, hook, hookDesc, passThis, passArgs, null, false);
    }

    static Patch redirect(String owner, String m, String desc, String target, String hook, String hookDesc) {
        return new Patch(owner, m, desc, Kind.REDIRECT, hook, hookDesc, false, false, target, false);
    }

    static Patch after(String owner, String m, String desc, String target, String hook, String hookDesc, boolean passThis) {
        return new Patch(owner, m, desc, Kind.AFTER_CALL, hook, hookDesc, passThis, false, target, false);
    }

    static Patch before(String owner, String m, String desc, String target, String hook, String hookDesc, boolean passThis) {
        return new Patch(owner, m, desc, Kind.BEFORE_CALL, hook, hookDesc, passThis, false, target, false);
    }

    static Patch fieldAfter(String owner, String m, String desc, String field, String hook, String hookDesc) {
        return new Patch(owner, m, desc, Kind.FIELD_AFTER, hook, hookDesc, false, false, field, false);
    }

    static Patch optional(Patch p) {
        return new Patch(p.owner, p.method, p.desc, p.kind, p.hookName, p.hookDesc, p.passThis, p.passArgs, p.target, true);
    }
}
