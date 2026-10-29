package coop.sync;

import Game.MyPanel;
import coop.Config;
import coop.net.Msg;
import coop.net.Net;
import coop.net.Proto;

/** The local player's visible state, sent ~30 times per second to the other player. */
public final class PlayerSync {
    private PlayerSync() {}

    public static final int F_RUN = 1;
    public static final int F_MOVE = 1 << 1;
    public static final int F_FALL = 1 << 2;
    public static final int F_SLEEP = 1 << 3;
    public static final int F_SKATE = 1 << 4;
    public static final int F_LIGHT = 1 << 5;
    public static final int F_HAVELIGHT = 1 << 6;
    public static final int F_RELOAD = 1 << 7;
    public static final int F_DEAD = 1 << 8;
    public static final int F_CAR = 1 << 9;
    public static final int F_INGAME = 1 << 10;

    /** One received sample. */
    public static final class State {
        public long time;
        public double x;
        public double y;
        public double ang;
        public double jump;
        public double heal;
        public double kevlar;
        public int flags;
        public int handlekind;
        public int handlename;
        public int flesh;
        public int bonehard;
        public int fire;
        public int use;
        public float push;
        public int fallkind;
        public String map;
        public double camx;
        public double camy;
        /** screen box relative to the camera: x from camx + viewL to camx + viewR, flat y likewise */
        public float viewL;
        public float viewR;
        public float viewT;
        public float viewB;
    }

    /** counts the local player's shots (Combat.shootcreater) */
    public static volatile int fireCount;
    private static int useCount;
    private static boolean lastUse;

    public static Msg capture() {
        int p = 300;
        int flags = 0;
        if (MyPanel.ctrun[p]) {
            flags |= F_RUN;
        }
        if (MyPanel.ctmove[p]) {
            flags |= F_MOVE;
        }
        if (MyPanel.ctfall[p]) {
            flags |= F_FALL;
        }
        if (MyPanel.ctsleeping[p]) {
            flags |= F_SLEEP;
        }
        if (MyPanel.ctskateing[p]) {
            flags |= F_SKATE;
        }
        if (MyPanel.ctopenflashlight[p] && MyPanel.cthaveflashlight[p]) {
            flags |= F_LIGHT;
        }
        if (MyPanel.cthaveflashlight[p]) {
            flags |= F_HAVELIGHT;
        }
        if (MyPanel.cthandlereload[p]) {
            flags |= F_RELOAD;
        }
        if (MyPanel.deathed || MyPanel.ctheal[p] <= 0 || DeathSync.localDead()) {
            flags |= F_DEAD;
        }
        if (MyPanel.carmode) {
            flags |= F_CAR;
        }
        flags |= F_INGAME;
        boolean use = MyPanel.Bone != null && MyPanel.Bone.ctuse[p];
        if (use && !lastUse && MyPanel.cthandlekind[p] != 1) {
            useCount++;
        }
        lastUse = use;
        int[] skin = playerSkin();
        Msg m = new Msg(Proto.PLAYER_STATE, 128);
        m.f64(MyPanel.doucount[0][p]).f64(MyPanel.doucount[1][p]).f32((float) MyPanel.angcount[p]).f32(MyPanel.jumpcountall[p]);
        m.f32((float) MyPanel.ctheal[p]).f32((float) MyPanel.ctkevlar[p]).i32(flags);
        m.i32(MyPanel.cthandlekind[p]).i32(MyPanel.cthandlename[p]).i32(skin[0]).i32(skin[1]);
        m.i32(fireCount).i32(useCount).f32(MyPanel.zombiepush[p]).u8(MyPanel.ctfallkind[p]);
        m.str(Config.name).str(MyPanel.select + MyPanel.selectid);
        m.f64(-MyPanel.keyxed).f64(-MyPanel.keyyed);
        // the own screen relative to the camera (the host simulates this area like its own screen)
        double[] f = MyPanel.pFAST;
        m.f32((float) (f[0] + MyPanel.keyxed)).f32((float) (f[1] + MyPanel.keyxed));
        m.f32((float) (f[2] + MyPanel.keyyed * MyPanel.flatex)).f32((float) (f[3] + MyPanel.keyyed * MyPanel.flatex));
        return m;
    }

    public static void receive(Msg m) {
        State s = new State();
        s.time = System.currentTimeMillis();
        s.x = m.f64();
        s.y = m.f64();
        s.ang = m.f32();
        s.jump = m.f32();
        s.heal = m.f32();
        s.kevlar = m.f32();
        s.flags = m.i32();
        s.handlekind = m.i32();
        s.handlename = m.i32();
        s.flesh = m.i32();
        s.bonehard = m.i32();
        s.fire = m.i32();
        s.use = m.i32();
        s.push = m.f32();
        s.fallkind = m.u8();
        String name = m.str();
        s.map = m.str();
        s.camx = m.f64();
        s.camy = m.f64();
        s.viewL = m.f32();
        s.viewR = m.f32();
        s.viewT = m.f32();
        s.viewB = m.f32();
        Puppet.name = name;
        if (Net.peerName.isEmpty()) {
            Net.peerName = name;
        }
        Puppet.push(s);
    }

    /** client in the host's world: its own look (Profile.ownShape), so both players look different */
    static int[] ownSkin() {
        try {
            int s = Profile.ownShape;
            if (net() && coop.Coop.clientInWorld && s >= 0 && MyPanel.gamekind < 0 && MyPanel.Bone != null
                    && s < MyPanel.Bone.counterflesh.length) {
                return new int[] {MyPanel.Bone.counterflesh[s][0], MyPanel.Bone.counterflesh[s][1]};
            }
        } catch (Throwable t) {
            // ignore
        }
        return null;
    }

    private static boolean net() {
        return Net.isClient();
    }

    /** the character look of the local player, same rules as Bone.get_player_shape() */
    static int[] playerSkin() {
        int[] own = ownSkin();
        if (own != null) {
            return own;
        }
        try {
            int flesh;
            int hard = 0;
            switch (MyPanel.gamekind) {
                case 0:
                    flesh = 26;
                    break;
                case 1:
                    flesh = 55;
                    break;
                default:
                    if ((MyPanel.survivalMode() || MyPanel.L4DMode()) && MyPanel.playershape > -1) {
                        flesh = MyPanel.Bone.counterflesh[MyPanel.playershape][0];
                        hard = MyPanel.Bone.counterflesh[MyPanel.playershape][1];
                    } else {
                        flesh = MyPanel.DirectorMode != null && MyPanel.DirectorMode.player_shape() > -1
                                ? MyPanel.DirectorMode.player_shape() : 16;
                    }
            }
            return new int[] {flesh, hard};
        } catch (Throwable t) {
            return new int[] {16, 0};
        }
    }
}
