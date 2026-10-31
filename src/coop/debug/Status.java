package coop.debug;

import Game.MyPanel;
import coop.Log;

/** One-line summary of the game state for testing, plus test actions. */
public final class Status {
    private Status() {}

    public static String text() {
        StringBuilder sb = new StringBuilder();
        sb.append("page=").append(MyPanel.page);
        if (MyPanel.doucount != null) {
            sb.append(" map=").append(MyPanel.select).append(MyPanel.selectid);
            sb.append(" player=(").append((int) MyPanel.doucount[0][300]).append(',').append((int) MyPanel.doucount[1][300]).append(')');
            sb.append(" heal=").append((int) MyPanel.ctheal[300]);
            sb.append(" p299=(").append((int) MyPanel.doucount[0][299]).append(',').append((int) MyPanel.doucount[1][299])
                    .append(" h=").append((int) MyPanel.ctheal[299]).append(" k=").append(MyPanel.cteamatekind[299]).append(')');
            int z = 0;
            int zr = 0;
            for (int x = 0; x < MyPanel.te; x++) {
                if (MyPanel.theal[x] > 0) {
                    z++;
                    if (Math.abs(MyPanel.douterro[0][x] - MyPanel.doucount[0][300]) < 1500
                            && Math.abs(MyPanel.douterro[1][x] - MyPanel.doucount[1][300]) < 1500) {
                        zr++;
                    }
                }
            }
            sb.append(" zombies=").append(z).append(" near=").append(zr);
            sb.append(" time=").append(MyPanel.hour).append(':').append(MyPanel.minute);
        }
        sb.append(" deathed=").append(MyPanel.deathed);
        Runtime rt = Runtime.getRuntime();
        sb.append(" mem=").append((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)).append("MB");
        sb.append(" ").append(coop.Coop.statusText());
        return sb.toString();
    }

    /** "sim WHAT ..." debug actions, run on the simulation thread. */
    public static void simCommand(String what, String[] a) {
        try {
            switch (what) {
                case "tp": {
                    double x = Double.parseDouble(a[2]);
                    double y = Double.parseDouble(a[3]);
                    MyPanel.doucount[0][300] = x;
                    MyPanel.doucount[1][300] = y;
                    break;
                }
                case "zombienear": {
                    // "sim zombienear COUNTER DIST": zombie in sight of a counter (299 = the other player)
                    int c = Integer.parseInt(a[2]);
                    double dist = Double.parseDouble(a.length > 3 ? a[3] : "350");
                    double cx = MyPanel.doucount[0][c];
                    double cy = MyPanel.doucount[1][c];
                    for (int i = 0; i < 16; i++) {
                        double ang = i * Math.PI / 8;
                        double zx = cx + Math.cos(ang) * dist;
                        double zy = cy + Math.sin(ang) * dist;
                        int[] o = new int[2];
                        if (MyPanel.position.findposition((int) zx, (int) zy, o) && MyPanel.checkpointseeFAST(cx, cy, zx, zy)) {
                            simCommand("zombie", new String[] {"sim", "zombie", String.valueOf(zx - MyPanel.doucount[0][300]),
                                String.valueOf(zy - MyPanel.doucount[1][300])});
                            Log.info("test zombie in view of " + c + " at angle " + i);
                            return;
                        }
                    }
                    Log.info("no free slot for a test zombie");
                    break;
                }
                case "zombie": {
                    // spawn one zombie next to the local player (test only)
                    int x = MyPanel.Zombierespawn.select();
                    if (x > -1) {
                        MyPanel.Zombierespawn.zombiefore(x);
                        MyPanel.zombierun[x] = false;
                        MyPanel.douterro[0][x] = MyPanel.doucount[0][300] + Double.parseDouble(a.length > 2 ? a[2] : "300");
                        MyPanel.douterro[1][x] = MyPanel.doucount[1][300] + Double.parseDouble(a.length > 3 ? a[3] : "0");
                        int[] out = new int[2];
                        MyPanel.position.findpositionstupid((int) MyPanel.douterro[0][x], (int) MyPanel.douterro[1][x], out);
                        MyPanel.tposition[0][x] = MyPanel.terro[0][x] = out[0];
                        MyPanel.tposition[1][x] = MyPanel.terro[1][x] = out[1];
                        MyPanel.angterro[x] = MyPanel.aimterro[x] = Math.random() * Math.PI * 2;
                        MyPanel.zombierun[x] = true;
                        MyPanel.Imterro[x] = (byte) MyPanel.Bone.get_random_normal_zombie();
                        MyPanel.Zombierespawn.getzombieweapon(x);
                        Log.info("test zombie " + x + " hp=" + MyPanel.theal[x]);
                    }
                    break;
                }
                case "aimz": {
                    // point the mouse at zombie slot N (test)
                    int z = Integer.parseInt(a[2]);
                    MyPanel.moux = (int) MyPanel.Rtsx(MyPanel.douterro[0][z]);
                    MyPanel.mouy = (int) MyPanel.Rtsy(MyPanel.douterro[1][z]);
                    break;
                }
                case "aimc": {
                    // point the mouse at counter slot N (test)
                    int z = Integer.parseInt(a[2]);
                    MyPanel.moux = (int) MyPanel.Rtsx(MyPanel.doucount[0][z]);
                    MyPanel.mouy = (int) MyPanel.Rtsy(MyPanel.doucount[1][z]);
                    break;
                }
                case "spawnitem": {
                    // "sim spawnitem NAME AMOUNT": item on the ground next to the player
                    int name = Integer.parseInt(a[2]);
                    int amount = a.length > 3 ? Integer.parseInt(a[3]) : 1;
                    MyPanel.stuffcreater.mapnumcreater(name, amount, (int) MyPanel.doucount[0][300] + 40,
                            (int) MyPanel.doucount[1][300], false);
                    Log.info("test item " + name + " x" + amount + " dropped, ground items=" + MyPanel.mapacknuming);
                    break;
                }
                case "packadd": {
                    // "sim packadd CODE NAME AMOUNT SLOT"
                    int num = MyPanel.gethumpacknameid(a[2]);
                    MyPanel.stuffcreater.unitynumcreater(Integer.parseInt(a[3]), Integer.parseInt(a[4]), Integer.parseInt(a[5]), num);
                    Log.info("Pack " + a[2] + ": " + coop.sync.Profile.packToString(a[2]));
                    break;
                }
                case "packshow": {
                    Log.info("Pack " + a[2] + ": " + coop.sync.Profile.packToString(a[2]));
                    break;
                }
                case "bag": {
                    MyPanel.sleepingbagcreater((int) MyPanel.doucount[0][300] + Integer.parseInt(a.length > 2 ? a[2] : "50"),
                            (int) MyPanel.doucount[1][300]);
                    Log.info("sleeping bag built, count=" + MyPanel.sleepingbagnuming);
                    break;
                }
                case "pickupall": {
                    MyPanel.mapacktaker.mapacktaker(300, 0, 0);
                    Log.info("picked up, ground items=" + MyPanel.mapacknuming);
                    break;
                }
                case "nearcase": {
                    int best = -1;
                    double bd = Double.MAX_VALUE;
                    for (int x = 0; x < MyPanel.ObjectCase.casenuming; x++) {
                        double d = Math.pow(MyPanel.ObjectCase.casepos[0][x] - MyPanel.doucount[0][300], 2)
                                + Math.pow(MyPanel.ObjectCase.casepos[1][x] - MyPanel.doucount[1][300], 2);
                        if (MyPanel.ObjectCase.casekind[x] == 0 && d < bd) {
                            bd = d;
                            best = x;
                        }
                    }
                    Log.info("nearest furniture " + best + " distance " + (int) Math.sqrt(bd));
                    if (best > -1) {
                        coop.Coop.panel.caseDialog(best);
                    }
                    break;
                }
                case "neardoor": {
                    int best = -1;
                    double bd = Double.MAX_VALUE;
                    for (int x = 0; x < MyPanel.Other.doornuming; x++) {
                        double d = Math.pow(MyPanel.Other.door[0][x] - MyPanel.doucount[0][300], 2)
                                + Math.pow(MyPanel.Other.door[1][x] - MyPanel.doucount[1][300], 2);
                        if (d < bd) {
                            bd = d;
                            best = x;
                        }
                    }
                    if (best > -1) {
                        Log.info("door " + best + " open=" + MyPanel.Other.dooropen[best] + " key=" + MyPanel.Other.doorkey[best]);
                        MyPanel.Other.opendoor(best);
                        Log.info("door " + best + " now open=" + MyPanel.Other.dooropen[best]);
                    }
                    break;
                }
                case "travel": {
                    MyPanel.travel(a[2], Integer.parseInt(a[3]), Integer.parseInt(a[4]), Integer.parseInt(a[5]));
                    break;
                }
                case "heal": {
                    MyPanel.ctheal[300] = 100;
                    MyPanel.healplayer = 100;
                    break;
                }
                case "boom": {
                    // "sim boom DX DY": explosion next to the own player (test)
                    MyPanel.explodecreater((int) (MyPanel.doucount[0][300] + Double.parseDouble(a[2])),
                            (int) (MyPanel.doucount[1][300] + Double.parseDouble(a[3])), 300.0, 800.0, 500.0, false);
                    break;
                }
                case "barrel": {
                    // "sim barrel DX DY": a barrel next to the own player (test)
                    MyPanel.barrelcreater(MyPanel.doucount[0][300] + Double.parseDouble(a[2]),
                            MyPanel.doucount[1][300] + Double.parseDouble(a[3]), 0);
                    break;
                }
                case "traveling": {
                    // "sim traveling MAP X Y ID": what an exit / door / world map does (test of the travel hook)
                    coop.Coop.panel.traveling(a[2], Integer.parseInt(a[3]), Integer.parseInt(a[4]), Integer.parseInt(a[5]));
                    break;
                }
                case "car": {
                    // "sim car CODE": RC car in front of the own player, start driving it (test)
                    double px = MyPanel.doucount[0][300];
                    double py = MyPanel.doucount[1][300];
                    int slot = MyPanel.carcreater(px + 60, py, MyPanel.angcount[300], 0, 100, 100,
                            Integer.parseInt(a.length > 2 ? a[2] : "777"));
                    if (slot >= 0) {
                        MyPanel.carmode = true;
                        MyPanel.carnumber = slot;
                    }
                    Log.info("test car slot " + slot);
                    break;
                }
                case "skate": {
                    // "sim skate": a skateboard under the own player, riding (test, like using the item)
                    for (int y = 0; y < 10; y++) {
                        if (!MyPanel.skatexist[y]) {
                            double hx = MyPanel.doucount[0][300];
                            double hy = MyPanel.doucount[1][300];
                            double an = MyPanel.angcount[300];
                            MyPanel.skatekind[y] = 0;
                            MyPanel.skatexist[y] = true;
                            MyPanel.skate[0][y] = hx + Math.cos(an - 1.57) * 25.0;
                            MyPanel.skate[1][y] = hy + Math.sin(an - 1.57) * 25.0;
                            MyPanel.skate[2][y] = hx;
                            MyPanel.skate[3][y] = hy;
                            MyPanel.skate[4][y] = hx - Math.cos(an - 1.57) * 25.0;
                            MyPanel.skate[5][y] = hy - Math.sin(an - 1.57) * 25.0;
                            MyPanel.angskate[y] = an;
                            for (int i = 0; i < 6; i++) {
                                MyPanel.skatespeed[i][y] = 0;
                            }
                            MyPanel.speedskate[y] = 100;
                            MyPanel.skatewaste[y] = 100;
                            MyPanel.skatehumanid[y] = 300;
                            MyPanel.skatetime[y] = 40;
                            MyPanel.ctskateing[300] = true;
                            MyPanel.ctskateid[300] = (byte) y;
                            Log.info("test skateboard slot " + y);
                            break;
                        }
                    }
                    break;
                }
                case "npc": {
                    // "sim npc trader|survivor [COUNTER]": NPC next to a counter (300 = own player), like the game makes them
                    Game.Humanrespawn h = MyPanel.Humanrespawn;
                    int c = a.length > 3 ? Integer.parseInt(a[3]) : 300;
                    int x = h.selecthuman();
                    if (x < 0) {
                        break;
                    }
                    h.humanfore(x);
                    double nx = MyPanel.doucount[0][c] + 90;
                    double ny = MyPanel.doucount[1][c];
                    MyPanel.doucount[0][x] = nx;
                    MyPanel.doucount[1][x] = ny;
                    MyPanel.gocounter[0][x] = nx;
                    MyPanel.gocounter[1][x] = ny;
                    MyPanel.ctpositionreal[0][x] = (int) nx;
                    MyPanel.ctpositionreal[1][x] = (int) ny;
                    MyPanel.ctheal[x] = 100;
                    MyPanel.ctkevlar[x] = 100;
                    MyPanel.cthunger[x] = 100f;
                    MyPanel.ctstrength[x] = 100;
                    MyPanel.Imcount[x] = MyPanel.Bone.get_random_normal_human();
                    h.getname(x);
                    MyPanel.clearpack(MyPanel.ctcode[x]);
                    MyPanel.ctcode[x] = MyPanel.getcode();
                    int num = MyPanel.packcreater(MyPanel.ctcode[x], 40);
                    MyPanel.cteamatekind[x] = 0;
                    MyPanel.ctwealth[x] = 40;
                    if (a[2].equals("trader")) {
                        h.shopper(num);
                        MyPanel.ctcontrolkind[x] = 8;
                        MyPanel.ctfight[x] = false;
                    } else {
                        h.getmelee(num);
                        MyPanel.ctcontrolkind[x] = 0;
                        MyPanel.ctfight[x] = true;
                    }
                    Log.info("test NPC " + a[2] + " slot " + x + " code " + MyPanel.ctcode[x]);
                    break;
                }
                case "zavoid": {
                    // "sim zavoid N": zombie N dodges (test of the guest-area animation)
                    MyPanel.Use.zombieavoid(Integer.parseInt(a[2]), 1, 0.3);
                    break;
                }
                case "zfall": {
                    int n = Integer.parseInt(a[2]);
                    MyPanel.terroristfall(n, MyPanel.douterro[0][n] + 50, MyPanel.douterro[1][n]);
                    break;
                }
                case "scene": {
                    // "sim scene REL/PATH": a cutscene folder below the game folder (test of the story sync)
                    String scene = MyPanel.pathall + a[2].replace('/', '\\') + "\\";
                    coop.Coop.runFx(() -> MyPanel.Animation.playfore(scene, false)); // touches JavaFX nodes
                    break;
                }
                case "dialog": {
                    MyPanel.HeadDialog.headDialogfore(MyPanel.pathall + a[2].replace('/', '\\'), -1, -1);
                    break;
                }
                case "photo": {
                    MyPanel.Direction.photo(MyPanel.pathall + a[2].replace('/', '\\'));
                    break;
                }
                // ---- tests of GuestEdits (locks, keys, power lines, story messages)
                case "takekey":
                    MyPanel.Key.takekey(Integer.parseInt(a[2]));
                    break;
                case "usekey":
                    Log.info("usekey " + a[2] + " -> " + MyPanel.Key.usekey(Integer.parseInt(a[2])));
                    break;
                case "addarea": {
                    // "sim addarea NAME ID KIND": a locked area (host, test)
                    MyPanel.areanameing[MyPanel.areanuminging] = a[2];
                    MyPanel.areaiding[MyPanel.areanuminging] = Byte.parseByte(a[3]);
                    MyPanel.areakinding[MyPanel.areanuminging] = Byte.parseByte(a[4]);
                    MyPanel.areamoneying[MyPanel.areanuminging] = 0;
                    MyPanel.areanuminging++;
                    MyPanel.area();
                    break;
                }
                case "unlockarea": {
                    // "sim unlockarea NAME ID": what a lockpick / code / key does
                    for (int i = 0; i < MyPanel.areanuminging; i++) {
                        if (a[2].equals(MyPanel.areanameing[i]) && MyPanel.areaiding[i] == Integer.parseInt(a[3])) {
                            MyPanel.areanameing[i] = null;
                            break;
                        }
                    }
                    MyPanel.area();
                    break;
                }
                case "lockbox": {
                    // "sim lockbox 1|0": nearest box locked / unlocked
                    int best = -1;
                    double bd = Double.MAX_VALUE;
                    for (int i = 0; i < MyPanel.boxexist.length; i++) {
                        if (MyPanel.boxexist[i]) {
                            double d = Math.pow(MyPanel.box[0][i] - MyPanel.doucount[0][300], 2)
                                    + Math.pow(MyPanel.box[1][i] - MyPanel.doucount[1][300], 2);
                            if (d < bd) {
                                bd = d;
                                best = i;
                            }
                        }
                    }
                    if (best >= 0) {
                        MyPanel.boxlocked[best] = a[2].equals("1");
                        Log.info("box " + best + " at " + MyPanel.box[0][best] + "," + MyPanel.box[1][best] + " locked="
                                + MyPanel.boxlocked[best]);
                    }
                    break;
                }
                case "power": {
                    // "sim power IDX": a bullet of the own player hits power box IDX
                    coop.Hooks.powerShot(MyPanel.Other, Integer.parseInt(a[2]));
                    break;
                }
                case "notice":
                    // host: a camp warning out of the host's mission code
                    coop.Hooks.hostNotice(MyPanel.NoticeView, MyPanel.NoticeView.campNOW);
                    break;
                case "guide":
                    // host: "sim guide X Y" the air drop arrow on this map
                    coop.Hooks.hostGuidance(MyPanel.Direction, MyPanel.select, MyPanel.selectid, Integer.parseInt(a[2]),
                            Integer.parseInt(a[3]), null);
                    break;
                case "jumpcamp":
                    MyPanel.Mission.jump_camp();
                    break;
                case "sleep": {
                    // "sim sleep H": what the end of sleeping in a bed / sleeping bag does
                    int h = Integer.parseInt(a[2]);
                    coop.Hooks.sleepAddHour(h);
                    MyPanel.realtime += h * 60;
                    break;
                }
                default:
                    Log.info("unknown sim action " + what);
            }
        } catch (Throwable t) {
            Log.error("simCommand " + what, t);
        }
    }
}
