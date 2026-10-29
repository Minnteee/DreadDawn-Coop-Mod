package coop.sync;

import Game.MyPanel;
import coop.Coop;
import coop.Log;
import coop.net.Msg;
import coop.net.Net;
import coop.net.Proto;

/**
 * Remote-controlled toy cars (10 slots) and skateboards (10 slots).
 * - The host owns all of them and sends their state 20x per second; the client shows them smoothly
 *   (interpolated, a little in the past, like the other player's figure).
 * - A car the client drives and a board the client rides are simulated by the client itself and sent to
 *   the host 30x per second; the host follows them (and decides what they hit).
 * - Placing and picking up cars / boards on the client is done on the host as well.
 * Boards ridden by the other player always stay under that player's figure.
 */
public final class VehicleSync {
    private VehicleSync() {}

    static final int N = 10;
    static final long DELAY_MS = 80;
    static final long GUEST_TIMEOUT_MS = 600;

    static final int EV_CAR_CREATE = 1;
    static final int EV_CAR_PICKUP = 2;
    static final int EV_SKATE_PICKUP = 3;
    /** host -> client: a car exploded */
    static final int EV_CAR_EXPLODE = 4;

    private static final boolean[] hostLastExist = new boolean[N];
    /** client: the host's heal of every car (the client keeps a huge value so cars never explode locally) */
    private static final int[] hostHeal = new int[N];
    private static final int SAFE_HEAL = 1_000_000;

    // ------------------------------------------------------------------ host -> client

    static final class CarSnap {
        long t;
        boolean exist;
        int kind;
        int code;
        int heal;
        int speed;
        boolean sound;
        boolean light;
        boolean driving;
        boolean hostDrives;
        final float[] pos = new float[4];
        final float[] spd = new float[4];
    }

    static final class SkateSnap {
        long t;
        boolean exist;
        int kind;
        int rider; // already mapped to this machine's slots (-1 none)
        double speed;
        int waste;
        float ang;
        final float[] pos = new float[6];
    }

    /** a few timestamped states; sample() interpolates at a past time (like the other player's figure) */
    static final class Track {
        static final int CAP = 8;
        final int dim;
        final long[] t = new long[CAP];
        final float[][] v;
        int head = -1;
        int count;

        Track(int dim) {
            this.dim = dim;
            v = new float[CAP][dim];
        }

        synchronized void clear() {
            head = -1;
            count = 0;
        }

        synchronized void push(long time, float[] vals, int off) {
            head = (head + 1) % CAP;
            t[head] = time;
            System.arraycopy(vals, off, v[head], 0, dim);
            count = Math.min(count + 1, CAP);
        }

        synchronized boolean sample(long rt, double[] out) {
            if (count == 0) {
                return false;
            }
            if (rt >= t[head]) {
                for (int i = 0; i < dim; i++) {
                    out[i] = v[head][i];
                }
                return true;
            }
            for (int k = 0; k < count - 1; k++) {
                int b = (head - k + CAP) % CAP;
                int a = (head - k - 1 + CAP) % CAP;
                if (t[a] <= rt && rt <= t[b]) {
                    double f = t[b] == t[a] ? 1 : (double) (rt - t[a]) / (t[b] - t[a]);
                    for (int i = 0; i < dim; i++) {
                        out[i] = v[a][i] + (v[b][i] - v[a][i]) * f;
                    }
                    return true;
                }
            }
            int oldest = (head - count + 1 + CAP) % CAP;
            for (int i = 0; i < dim; i++) {
                out[i] = v[oldest][i];
            }
            return true;
        }
    }

    private static final Track[] carTrack = new Track[N];
    private static final Track[] skateTrack = new Track[N];
    private static final Track guestCarTrack = new Track(4);

    static {
        for (int x = 0; x < N; x++) {
            carTrack[x] = new Track(4);
            skateTrack[x] = new Track(6);
        }
    }

    private static final CarSnap[][] carBuf = new CarSnap[N][2];
    private static final SkateSnap[][] skateBuf = new SkateSnap[N][2];
    private static volatile long lastHostState;

    private static long lastSend;
    private static long lastClientSend;

    /** Host worker, end of frame: all cars and boards. */
    public static void hostTick() {
        if (MyPanel.carexist == null) {
            return;
        }
        for (int x = 0; x < N; x++) {
            if (hostLastExist[x] && !MyPanel.carexist[x] && MyPanel.carheal[x] <= 0) {
                Net.send(new Msg(Proto.VEH_EVENT, 24).u8(EV_CAR_EXPLODE).u8(x).i32((int) MyPanel.car[0][x])
                        .i32((int) MyPanel.car[1][x]));
            }
            hostLastExist[x] = MyPanel.carexist[x];
        }
        long now = System.currentTimeMillis();
        if (now - lastSend < 50) {
            return;
        }
        lastSend = now;
        Msg m = new Msg(Proto.VEH_STATE, 1024);
        int nc = 0;
        for (int x = 0; x < N; x++) {
            if (MyPanel.carexist[x]) {
                nc++;
            }
        }
        m.u8(nc);
        for (int x = 0; x < N; x++) {
            if (!MyPanel.carexist[x]) {
                continue;
            }
            int flags = (MyPanel.carsound[x] ? 1 : 0) | (MyPanel.caropenflashlight[x] ? 2 : 0)
                    | (MyPanel.cardriving[x] ? 4 : 0) | (MyPanel.carmode && MyPanel.carnumber == x ? 8 : 0);
            m.u8(x).u8(MyPanel.carkind[x]).i32(MyPanel.carcode[x]).i32(MyPanel.carheal[x]).i32(MyPanel.speedcar[x]).u8(flags);
            for (int i = 0; i < 4; i++) {
                m.f32((float) MyPanel.car[i][x]);
            }
            for (int i = 0; i < 4; i++) {
                m.f32((float) MyPanel.carspeed[i][x]);
            }
        }
        int ns = 0;
        for (int x = 0; x < N; x++) {
            if (MyPanel.skatexist[x]) {
                ns++;
            }
        }
        m.u8(ns);
        for (int x = 0; x < N; x++) {
            if (!MyPanel.skatexist[x]) {
                continue;
            }
            int rider = MyPanel.skatehumanid[x];
            boolean riding = rider >= 0 && rider <= 300 && MyPanel.ctskateing[rider] && MyPanel.ctskateid[rider] == x;
            m.u8(x).u8(MyPanel.skatekind[x]).i16(riding ? rider : -1).f32((float) MyPanel.speedskate[x])
                    .i32(MyPanel.skatewaste[x]).f32((float) MyPanel.angskate[x]);
            for (int i = 0; i < 6; i++) {
                m.f32((float) MyPanel.skate[i][x]);
            }
        }
        Net.send(m);
    }

    private static void receiveState(Msg m) {
        long now = System.currentTimeMillis();
        boolean[] carSeen = new boolean[N];
        int nc = m.u8();
        CarSnap[] cars = new CarSnap[N];
        for (int i = 0; i < nc; i++) {
            CarSnap c = new CarSnap();
            int x = m.u8();
            c.t = now;
            c.exist = true;
            c.kind = m.u8();
            c.code = m.i32();
            c.heal = m.i32();
            c.speed = m.i32();
            int fl = m.u8();
            c.sound = (fl & 1) != 0;
            c.light = (fl & 2) != 0;
            c.driving = (fl & 4) != 0;
            c.hostDrives = (fl & 8) != 0;
            for (int k = 0; k < 4; k++) {
                c.pos[k] = m.f32();
            }
            for (int k = 0; k < 4; k++) {
                c.spd[k] = m.f32();
            }
            if (x >= 0 && x < N) {
                cars[x] = c;
                carSeen[x] = true;
            }
        }
        int ns = m.u8();
        SkateSnap[] skates = new SkateSnap[N];
        for (int i = 0; i < ns; i++) {
            SkateSnap s = new SkateSnap();
            int x = m.u8();
            s.t = now;
            s.exist = true;
            s.kind = m.u8();
            int r = m.i16();
            s.rider = r == 300 ? Puppet.K : (r == Puppet.K ? 300 : r);
            s.speed = m.f32();
            s.waste = m.i32();
            s.ang = m.f32();
            for (int k = 0; k < 6; k++) {
                s.pos[k] = m.f32();
            }
            if (x >= 0 && x < N) {
                skates[x] = s;
            }
        }
        for (int x = 0; x < N; x++) {
            CarSnap old = carBuf[x][1];
            CarSnap c = cars[x];
            if (c == null || old == null || !old.exist || old.code != c.code) {
                carTrack[x].clear();
            }
            if (c != null) {
                carTrack[x].push(now, c.pos, 0);
            }
            SkateSnap olds = skateBuf[x][1];
            SkateSnap sk = skates[x];
            if (sk == null || olds == null || !olds.exist) {
                skateTrack[x].clear();
            }
            if (sk != null) {
                skateTrack[x].push(now, sk.pos, 0);
            }
        }
        synchronized (VehicleSync.class) {
            for (int x = 0; x < N; x++) {
                CarSnap c = cars[x];
                if (c == null) {
                    c = new CarSnap();
                    c.t = now;
                    c.exist = false;
                }
                carBuf[x][0] = carBuf[x][1];
                carBuf[x][1] = c;
                SkateSnap s = skates[x];
                if (s == null) {
                    s = new SkateSnap();
                    s.t = now;
                    s.exist = false;
                    s.rider = -1;
                }
                skateBuf[x][0] = skateBuf[x][1];
                skateBuf[x][1] = s;
            }
            lastHostState = now;
        }
    }

    // ------------------------------------------------------------------ client -> host

    /** guest's car / board as last received by the host */
    private static volatile float[] guestCar; // slot, code, pos4, spd4, sound, light
    private static volatile long guestCarAt;
    private static volatile float[] guestSkate; // slot, kind, speed, waste, ang, pos6
    private static volatile long guestSkateAt;
    private static int guestSkateSlot = -1;

    /** Client worker, end of frame: the car it drives / the board it rides. */
    public static void clientTick() {
        long now = System.currentTimeMillis();
        if (now - lastClientSend < 33 || MyPanel.carexist == null) {
            return;
        }
        lastClientSend = now;
        Msg m = new Msg(Proto.VEH_CLIENT, 128);
        int x = MyPanel.carnumber;
        boolean driving = MyPanel.carmode && x >= 0 && x < N && MyPanel.carexist[x];
        m.bool(driving);
        if (driving) {
            m.u8(x).i32(MyPanel.carcode[x]);
            for (int i = 0; i < 4; i++) {
                m.f32((float) MyPanel.car[i][x]);
            }
            for (int i = 0; i < 4; i++) {
                m.f32((float) MyPanel.carspeed[i][x]);
            }
            m.bool(MyPanel.carsound[x]).bool(MyPanel.caropenflashlight[x]);
        }
        int b = MyPanel.ctskateid[300];
        boolean riding = MyPanel.ctskateing[300] && b >= 0 && b < N && MyPanel.skatexist[b];
        m.bool(riding);
        if (riding) {
            m.u8(b).u8(MyPanel.skatekind[b]).f32((float) MyPanel.speedskate[b]).i32(MyPanel.skatewaste[b])
                    .f32((float) MyPanel.angskate[b]);
            for (int i = 0; i < 6; i++) {
                m.f32((float) MyPanel.skate[i][b]);
            }
        }
        if (driving || riding || wasActive) {
            Net.send(m);
        }
        wasActive = driving || riding;
    }

    private static boolean wasActive;

    private static void receiveClient(Msg m) {
        long now = System.currentTimeMillis();
        if (m.bool()) {
            float[] c = new float[12];
            c[0] = m.u8();
            c[1] = m.i32();
            for (int i = 0; i < 4; i++) {
                c[2 + i] = m.f32();
            }
            for (int i = 0; i < 4; i++) {
                c[6 + i] = m.f32();
            }
            c[10] = m.bool() ? 1 : 0;
            c[11] = m.bool() ? 1 : 0;
            float[] prev = guestCar;
            if (prev == null || (int) prev[0] != (int) c[0] || now - guestCarAt > GUEST_TIMEOUT_MS) {
                guestCarTrack.clear();
            }
            guestCarTrack.push(now, c, 2);
            guestCar = c;
            guestCarAt = now;
        } else {
            guestCar = null;
        }
        if (m.bool()) {
            float[] s = new float[11];
            s[0] = m.u8();
            s[1] = m.u8();
            s[2] = m.f32();
            s[3] = m.i32();
            s[4] = m.f32();
            for (int i = 0; i < 6; i++) {
                s[5 + i] = m.f32();
            }
            guestSkate = s;
            guestSkateAt = now;
        } else {
            guestSkate = null;
        }
    }

    // ------------------------------------------------------------------ after the game moved cars / boards

    /** Worker, after every MyPanel.car() call (4x per frame). */
    public static void afterCar() {
        if (!Coop.playing() || MyPanel.carexist == null) {
            return;
        }
        if (Net.isHost()) {
            float[] c = guestCar;
            if (c == null || System.currentTimeMillis() - guestCarAt > GUEST_TIMEOUT_MS) {
                return;
            }
            int x = (int) c[0];
            if (x < 0 || x >= N || !MyPanel.carexist[x] || MyPanel.carcode[x] != (int) c[1]
                    || (MyPanel.carmode && MyPanel.carnumber == x)) {
                return;
            }
            // smooth: shown a little in the past, between the states of the guest
            double[] p = new double[4];
            if (!guestCarTrack.sample(System.currentTimeMillis() - DELAY_MS, p)) {
                for (int i = 0; i < 4; i++) {
                    p[i] = c[2 + i];
                }
            }
            for (int i = 0; i < 4; i++) {
                MyPanel.car[i][x] = p[i];
                MyPanel.carspeed[i][x] = c[6 + i];
            }
            MyPanel.carsound[x] = c[10] > 0;
            MyPanel.caropenflashlight[x] = c[11] > 0;
            return;
        }
        // client: every car the host owns follows the host smoothly
        long render = System.currentTimeMillis() - DELAY_MS;
        for (int x = 0; x < N; x++) {
            CarSnap a;
            CarSnap b;
            synchronized (VehicleSync.class) {
                a = carBuf[x][0];
                b = carBuf[x][1];
            }
            if (b == null) {
                continue;
            }
            boolean mine = MyPanel.carmode && MyPanel.carnumber == x;
            if (b.exist) {
                hostHeal[x] = b.heal;
            }
            if (mine) {
                if (b.exist && b.heal < MyPanel.carheal[x]) {
                    MyPanel.carheal[x] = b.heal; // damage is the host's
                }
                continue;
            }
            if (!b.exist) {
                if (MyPanel.carexist[x]) {
                    MyPanel.carexist[x] = false; // picked up or destroyed on the host
                }
                continue;
            }
            boolean appeared = !MyPanel.carexist[x] || MyPanel.carcode[x] != b.code;
            MyPanel.carexist[x] = true;
            MyPanel.carkind[x] = b.kind;
            MyPanel.carcode[x] = b.code;
            MyPanel.carheal[x] = SAFE_HEAL; // explosions come from the host
            MyPanel.speedcar[x] = b.speed;
            MyPanel.carsound[x] = b.sound;
            MyPanel.caropenflashlight[x] = b.light;
            MyPanel.cardriving[x] = b.driving;
            double[] p = new double[4];
            if (appeared || !carTrack[x].sample(render, p)) {
                for (int i = 0; i < 4; i++) {
                    p[i] = b.pos[i];
                }
            }
            for (int i = 0; i < 4; i++) {
                MyPanel.car[i][x] = p[i];
                MyPanel.carspeed[i][x] = b.spd[i];
            }
        }
    }

    /** Worker, after every MyPanel.skate() call (4x per frame). */
    public static void afterSkate() {
        if (!Coop.playing() || MyPanel.skatexist == null) {
            return;
        }
        if (Net.isHost()) {
            hostGuestBoard();
            return;
        }
        long render = System.currentTimeMillis() - DELAY_MS;
        boolean puppetRides = false;
        for (int x = 0; x < N; x++) {
            SkateSnap a;
            SkateSnap b;
            synchronized (VehicleSync.class) {
                a = skateBuf[x][0];
                b = skateBuf[x][1];
            }
            if (b == null) {
                continue;
            }
            if (MyPanel.ctskateing[300] && MyPanel.ctskateid[300] == x) {
                continue; // the board under the own player: our own physics
            }
            if (!b.exist || b.rider == 300) {
                if (!b.exist && MyPanel.skatexist[x]) {
                    MyPanel.skatexist[x] = false;
                }
                continue;
            }
            MyPanel.skatexist[x] = true;
            MyPanel.skatekind[x] = (byte) b.kind;
            MyPanel.speedskate[x] = b.speed;
            MyPanel.skatewaste[x] = b.waste;
            if (b.rider == Puppet.K && Puppet.active()) {
                // the host rides it: keep it under the host's figure
                puppetRides = true;
                placeUnder(x, Puppet.K, b.pos, b.ang);
                continue;
            }
            if (b.rider >= 0 && b.rider < Puppet.K) {
                MyPanel.skatehumanid[x] = b.rider;
            }
            double[] p = new double[6];
            if (!skateTrack[x].sample(render, p)) {
                for (int i = 0; i < 6; i++) {
                    p[i] = b.pos[i];
                }
            }
            for (int i = 0; i < 6; i++) {
                MyPanel.skate[i][x] = p[i];
                MyPanel.skatespeed[i][x] = 0;
            }
            MyPanel.angskate[x] = b.ang;
        }
        if (!puppetRides && MyPanel.ctskateing[Puppet.K]) {
            MyPanel.ctskateing[Puppet.K] = false;
        }
    }

    /** board x keeps its shape and lies under figure k (its middle ball = the figure's position) */
    private static void placeUnder(int x, int k, float[] shape, float ang) {
        double mx = Puppet.appliedX;
        double my = Puppet.appliedY;
        double ox = mx - shape[2];
        double oy = my - shape[3];
        for (int i = 0; i < 6; i += 2) {
            MyPanel.skate[i][x] = shape[i] + ox;
            MyPanel.skate[i + 1][x] = shape[i + 1] + oy;
            MyPanel.skatespeed[i][x] = 0;
            MyPanel.skatespeed[i + 1][x] = 0;
        }
        MyPanel.angskate[x] = ang;
        MyPanel.skatehumanid[x] = k;
        MyPanel.ctskateing[k] = true;
        MyPanel.ctskateid[k] = (byte) x;
    }

    /** host: the guest's board follows the guest's figure; when the guest steps off it stays where it is */
    private static void hostGuestBoard() {
        float[] s = guestSkate;
        int k = Puppet.K;
        if (s == null || System.currentTimeMillis() - guestSkateAt > GUEST_TIMEOUT_MS || !Puppet.active()) {
            if (MyPanel.ctskateing[k]) {
                MyPanel.ctskateing[k] = false;
            }
            guestSkateSlot = -1;
            return;
        }
        int x = (int) s[0];
        if (guestSkateSlot != x) {
            // a new ride: the guest's board (same slot as on the guest's machine if free there)
            if (x < 0 || x >= N || (MyPanel.skatexist[x] && MyPanel.skatehumanid[x] != k
                    && MyPanel.skatehumanid[x] >= 0 && MyPanel.ctskateing[MyPanel.skatehumanid[x]]
                    && MyPanel.ctskateid[MyPanel.skatehumanid[x]] == x)) {
                x = -1;
                for (int i = 0; i < N; i++) {
                    if (!MyPanel.skatexist[i]) {
                        x = i;
                        break;
                    }
                }
                if (x < 0) {
                    return;
                }
            }
            guestSkateSlot = x;
            MyPanel.skatexist[x] = true;
            MyPanel.skatetime[x] = 0;
        }
        x = guestSkateSlot;
        MyPanel.skatexist[x] = true;
        MyPanel.skatekind[x] = (byte) s[1];
        MyPanel.speedskate[x] = s[2];
        MyPanel.skatewaste[x] = (int) s[3];
        float[] shape = new float[6];
        System.arraycopy(s, 5, shape, 0, 6);
        placeUnder(x, k, shape, s[4]);
    }

    private static void explodeLocal(int x, int y) {
        try {
            Combat.envDepth++;
            HazardSync.sharedEnter();
            MyPanel.explodecreater(x, y, 300.0, 800.0, 500.0, false);
        } catch (Throwable t) {
            Log.rare("Auto-Explosion " + t);
        } finally {
            HazardSync.sharedExit();
            Combat.envDepth--;
        }
    }

    // ------------------------------------------------------------------ placing / picking up on the client

    private static final ThreadLocal<Boolean> applying = ThreadLocal.withInitial(() -> false);
    private static boolean pickupWasThere;

    /** HEAD MyPanel.carcreater (static): the client places a car -> the host places it too. */
    public static void carCreating(double x, double y, double ang, int kind, int speed, int heal, int code) {
        if (applying.get() || !Net.isClient() || !Coop.playing()) {
            return;
        }
        int slot = -1;
        for (int i = 0; i < N; i++) {
            if (!MyPanel.carexist[i]) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            return;
        }
        Net.send(new Msg(Proto.VEH_EVENT, 64).u8(EV_CAR_CREATE).u8(slot).f64(x).f64(y).f64(ang).i32(kind).i32(speed)
                .i32(heal).i32(code));
    }

    public static void carPickupHead(int x) {
        pickupWasThere = x >= 0 && x < N && MyPanel.carexist[x];
        if (pickupWasThere && Net.isClient() && MyPanel.carheal[x] >= SAFE_HEAL) {
            MyPanel.carheal[x] = hostHeal[x]; // the item keeps the real health
        }
    }

    public static void carPickupTail(int x) {
        if (pickupWasThere && !MyPanel.carexist[x] && Net.isClient() && Coop.playing()) {
            Net.send(new Msg(Proto.VEH_EVENT, 16).u8(EV_CAR_PICKUP).u8(x).i32(MyPanel.carcode[x]));
        }
    }

    private static boolean skatePickupWasThere;

    public static void skatePickupHead(int x) {
        skatePickupWasThere = x >= 0 && x < N && MyPanel.skatexist[x];
    }

    public static void skatePickupTail(int x) {
        if (skatePickupWasThere && !MyPanel.skatexist[x] && Net.isClient() && Coop.playing()) {
            Net.send(new Msg(Proto.VEH_EVENT, 16).u8(EV_SKATE_PICKUP).u8(x));
        }
    }

    private static void hostEvent(Msg m) {
        int kind = m.u8();
        switch (kind) {
            case EV_CAR_CREATE: {
                int slot = m.u8();
                double x = m.f64();
                double y = m.f64();
                double ang = m.f64();
                int ck = m.i32();
                int speed = m.i32();
                int heal = m.i32();
                int code = m.i32();
                Coop.runSim(() -> {
                    applying.set(true);
                    try {
                        if (slot >= 0 && slot < N && !MyPanel.carexist[slot]) {
                            // same slot as on the guest machine (like MyPanel.carcreater)
                            MyPanel.carkind[slot] = ck;
                            MyPanel.car[0][slot] = x + Math.cos(ang - Math.PI / 2) * 3.0;
                            MyPanel.car[1][slot] = y + Math.sin(ang - Math.PI / 2) * 3.0;
                            MyPanel.car[2][slot] = x;
                            MyPanel.car[3][slot] = y;
                            for (int i = 0; i < 4; i++) {
                                MyPanel.carspeed[i][slot] = 0;
                            }
                            MyPanel.speedcar[slot] = speed;
                            MyPanel.carheal[slot] = heal;
                            MyPanel.caropenflashlight[slot] = false;
                            MyPanel.carcode[slot] = code;
                            MyPanel.carsound[slot] = false;
                            MyPanel.carexist[slot] = true;
                        } else {
                            MyPanel.carcreater(x, y, ang, ck, speed, heal, code);
                        }
                    } finally {
                        applying.set(false);
                    }
                    Log.info("Auto des Gasts aufgestellt (Platz " + slot + ")");
                });
                break;
            }
            case EV_CAR_PICKUP: {
                int slot = m.u8();
                int code = m.i32();
                Coop.runSim(() -> {
                    if (slot >= 0 && slot < N && MyPanel.carexist[slot] && MyPanel.carcode[slot] == code) {
                        MyPanel.carexist[slot] = false;
                        if (MyPanel.carmode && MyPanel.carnumber == slot) {
                            MyPanel.carmode = false;
                        }
                    }
                });
                break;
            }
            case EV_SKATE_PICKUP: {
                int slot = m.u8();
                Coop.runSim(() -> {
                    int x = slot;
                    if (guestSkateSlot >= 0 && guestSkateSlot != slot && (guestSkate == null || (int) guestSkate[0] == slot)) {
                        x = guestSkateSlot;
                    }
                    if (x >= 0 && x < N && MyPanel.skatexist[x]
                            && !(MyPanel.ctskateing[300] && MyPanel.ctskateid[300] == x)) {
                        MyPanel.skatexist[x] = false;
                    }
                });
                break;
            }
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ messages

    public static void handle(Msg m) {
        switch (m.type) {
            case Proto.VEH_STATE:
                if (Net.isClient()) {
                    receiveState(m);
                }
                break;
            case Proto.VEH_CLIENT:
                if (Net.isHost()) {
                    receiveClient(m);
                }
                break;
            case Proto.VEH_EVENT:
                if (Net.isHost()) {
                    hostEvent(m);
                } else if (m.u8() == EV_CAR_EXPLODE) {
                    int x = m.u8();
                    int px = m.i32();
                    int py = m.i32();
                    Coop.runSim(() -> {
                        if (x >= 0 && x < N && MyPanel.carexist[x]) {
                            MyPanel.carexist[x] = false;
                            explodeLocal(px, py);
                        }
                    });
                }
                break;
            default:
                break;
        }
    }

    public static void reset() {
        synchronized (VehicleSync.class) {
            for (int x = 0; x < N; x++) {
                carBuf[x][0] = carBuf[x][1] = null;
                skateBuf[x][0] = skateBuf[x][1] = null;
            }
        }
        guestCar = null;
        guestSkate = null;
        guestSkateSlot = -1;
        wasActive = false;
        guestCarTrack.clear();
        for (int x = 0; x < N; x++) {
            carTrack[x].clear();
            skateTrack[x].clear();
        }
    }
}
