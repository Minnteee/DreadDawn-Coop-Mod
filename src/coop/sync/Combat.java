package coop.sync;

import Game.MyPanel;
import coop.Coop;
import coop.Log;
import coop.net.Msg;
import coop.net.Net;
import coop.net.Proto;

/**
 * Damage rules in co-op:
 * - zombies and NPCs belong to the host: the client sends its hits, the host applies them;
 * - each player owns their own health: hits on the remote player's figure go to that player.
 */
public final class Combat {
    private Combat() {}

    private static final ThreadLocal<Boolean> applyingRemote = ThreadLocal.withInitial(() -> false);

    /** >0 while explosions / fire / acid are being processed (game thread) */
    public static int envDepth;

    /** Hook at the start of MyPanel.ctdamage. true = skip the game's own handling. */
    public static boolean ctdamage(int id, double damage, double angshock, boolean kevlar, boolean blood) {
        if (Coop.testGod && id == 300) {
            return true;
        }
        if (!Coop.playing() || applyingRemote.get()) {
            return false;
        }
        if (id == Puppet.K && Puppet.active() && Net.isClient() && envDepth > 0) {
            // fire, acid, explosions ... exist on the host too and hit the host's player there
            MyPanel.ctinjury[id] = MyPanel.injury;
            return true;
        }
        if (id == Puppet.K && Puppet.active()) {
            // the figure of the other player: that player takes the damage on their own machine
            // (no damage during cutscenes, like the game does for its own player)
            boolean cutscene = MyPanel.Animation != null && MyPanel.Animation.play;
            if (damage > 0 && !Puppet.isRemoteDead() && !cutscene) {
                Net.send(new Msg(Proto.DAMAGE_PLAYER).f64(damage).f64(angshock).bool(kevlar).bool(blood));
            }
            MyPanel.ctinjury[id] = MyPanel.injury;
            return true;
        }
        if (Net.isClient() && id == 300 && envDepth > 0) {
            // explosions, fire and acid exist on the host as well; the host sends the damage
            return true;
        }
        if (Net.isClient() && id >= 0 && id < Puppet.K) {
            // an NPC on the client: the host decides (explosions / fire / acid happen there as well)
            if (damage > 0 && envDepth <= 0) {
                Net.send(new Msg(Proto.HIT_HUMAN).i16(id).f64(damage).f64(angshock).bool(kevlar).bool(blood));
            }
            MyPanel.ctinjury[id] = MyPanel.injury;
            return true;
        }
        return false;
    }

    /** Hook at the start of MyPanel.tdamage. true = skip the game's own handling. */
    public static boolean tdamage(int id, int hitter, int damage, double angshock, boolean upblood) {
        if (!Coop.playing() || applyingRemote.get()) {
            return false;
        }
        if (Net.isClient()) {
            if (hitter == 300 || hitter == -2) { // the own player or a turret he uses
                Net.send(new Msg(Proto.HIT_ZOMBIE).i16(id).i32(damage).f64(angshock).bool(upblood));
                // immediate feedback, the result comes back from the host
                MyPanel.tinjury[id] = MyPanel.injury;
                MyPanel.tfireang[id] = MyPanel.tfireang[id] + angshock;
                MyPanel.NUM_OF_HIT++;
            }
            return true;
        }
        return false;
    }

    /** Hook at the start of MyPanel.shootcreater: counts the local player's shots for the other side. */
    public static void shootcreater(double humx, double humy, double speedx, double speedy, double ang, byte kind,
            int damage, int shooter) {
        if (shooter == 300 && Coop.playing()) {
            PlayerSync.fireCount++;
        }
    }

    public static void handle(Msg m) {
        switch (m.type) {
            case Proto.HIT_ZOMBIE: {
                int id = m.i16();
                int damage = m.i32();
                double ang = m.f64();
                boolean blood = m.bool();
                if (!Net.isHost() || id < 0 || id >= MyPanel.te) {
                    return;
                }
                Coop.runSim(() -> {
                    if (MyPanel.theal[id] <= 0) {
                        return;
                    }
                    boolean strong = false;
                    try {
                        strong = MyPanel.Zombierespawn.strong(id);
                    } catch (Throwable t) {
                        // ignore
                    }
                    applyingRemote.set(true);
                    try {
                        MyPanel.tdamage(id, Puppet.K, damage, ang, blood);
                    } finally {
                        applyingRemote.set(false);
                    }
                    if (MyPanel.theal[id] <= 0) {
                        Net.send(new Msg(Proto.ZOMBIE_EVENT).u8(1).bool(strong));
                    }
                });
                break;
            }
            case Proto.HIT_HUMAN: {
                int id = m.i16();
                double damage = m.f64();
                double ang = m.f64();
                boolean kevlar = m.bool();
                boolean blood = m.bool();
                if (!Net.isHost() || id < 0 || id >= Puppet.K) {
                    return;
                }
                Coop.runSim(() -> {
                    if (MyPanel.ctheal[id] <= 0) {
                        return;
                    }
                    applyingRemote.set(true);
                    try {
                        MyPanel.ctdamage(id, damage, ang, kevlar, blood);
                    } finally {
                        applyingRemote.set(false);
                    }
                });
                break;
            }
            case Proto.DAMAGE_PLAYER: {
                double damage = m.f64();
                double ang = m.f64();
                boolean kevlar = m.bool();
                boolean blood = m.bool();
                Coop.runSim(() -> {
                    if (MyPanel.ctheal[300] <= 0 || DeathSync.localDead()) {
                        return;
                    }
                    applyingRemote.set(true);
                    try {
                        MyPanel.ctdamage(300, damage, ang, kevlar, blood);
                    } finally {
                        applyingRemote.set(false);
                    }
                });
                break;
            }
            case Proto.ZOMBIE_EVENT: {
                int what = m.u8();
                boolean strong = m.bool();
                if (what == 1) {
                    // kill credit for the client's player
                    Coop.runSim(() -> {
                        if (MyPanel.LV < MyPanel.LVED) {
                            MyPanel.EXP += strong ? 2 : 1;
                        }
                        MyPanel.NUM_OF_KILL++;
                    });
                }
                break;
            }
            default:
                Log.rare("Combat: unknown " + m.type);
        }
    }
}
