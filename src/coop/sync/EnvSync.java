package coop.sync;

import Game.MyPanel;
import coop.net.Msg;
import coop.net.Proto;

/** Time of day and weather: decided by the host, mirrored by the client (1x per second). */
public final class EnvSync {
    private EnvSync() {}

    public static Msg capture() {
        Msg m = new Msg(Proto.ENV, 128);
        m.i32(MyPanel.realtime).i32(MyPanel.minute).i32(MyPanel.hour).i32(MyPanel.day).i32(MyPanel.week);
        m.f64(MyPanel.timefast).f64(MyPanel.rainamounted).f64(MyPanel.rainamount).i32(MyPanel.raintime)
                .i32(MyPanel.rainrandom).f64(MyPanel.sunnycloude);
        double cloud = 0;
        int cloudtime = 0;
        if (MyPanel.Other != null) {
            cloud = MyPanel.Other.cloud;
            cloudtime = MyPanel.Other.cloudtime;
        }
        m.f64(cloud).i32(cloudtime).f64(MyPanel.windang).f64(MyPanel.windstrong);
        // mission state so the client shows the same goals
        int mission = -1;
        int width = 0;
        int finish = 0;
        int target = -1;
        if (MyPanel.Direction != null) {
            mission = MyPanel.Direction.missionuming;
            width = MyPanel.Direction.missionwidthnow;
            finish = MyPanel.Direction.missionwidthfinish;
            target = MyPanel.Direction.targetnow;
        }
        m.i32(mission).i32(width).i32(finish).i32(target).i32(MyPanel.missionmodekind);
        // mission mode goals (zombie area / air drop / camp siege): progress bars, countdowns, arrows
        m.i32(MyPanel.zombieareanum).i32(MyPanel.zombieareanuming).i32(MyPanel.zombieareakind)
                .i32(MyPanel.zombieareaENDx).i32(MyPanel.zombieareaENDy).bool(MyPanel.zombieareaAIRDROP)
                .i32(MyPanel.zombieareamapid).bool(MyPanel.zombieareamap != null);
        if (MyPanel.zombieareamap != null) {
            m.str(MyPanel.zombieareamap);
        }
        m.i32(MyPanel.towerid).i32(MyPanel.airdropid).i32(MyPanel.camprushtime).i32(MyPanel.camptime);
        return m;
    }

    public static void receive(Msg m) {
        int realtime = m.i32();
        int minute = m.i32();
        int hour = m.i32();
        int day = m.i32();
        int week = m.i32();
        double timefast = m.f64();
        double rainamounted = m.f64();
        double rainamount = m.f64();
        int raintime = m.i32();
        int rainrandom = m.i32();
        double sunny = m.f64();
        double cloud = m.f64();
        int cloudtime = m.i32();
        double windang = m.f64();
        double windstrong = m.f64();
        int mission = m.i32();
        int width = m.i32();
        int finish = m.i32();
        int target = m.i32();
        int missionmode = m.i32();
        int zNum = m.i32();
        int zNuming = m.i32();
        int zKind = m.i32();
        int zEndX = m.i32();
        int zEndY = m.i32();
        boolean zAir = m.bool();
        int zMapId = m.i32();
        String zMap = m.bool() ? m.str() : null;
        int towerid = m.i32();
        int airdropid = m.i32();
        int camprush = m.i32();
        int camptime = m.i32();
        if (!coop.Coop.inGame()) {
            return;
        }
        MyPanel.zombieareanum = zNum;
        MyPanel.zombieareanuming = zNuming;
        MyPanel.zombieareakind = zKind;
        MyPanel.zombieareaENDx = zEndX;
        MyPanel.zombieareaENDy = zEndY;
        MyPanel.zombieareaAIRDROP = zAir;
        MyPanel.zombieareamapid = zMapId;
        MyPanel.zombieareamap = zMap;
        MyPanel.towerid = towerid;
        MyPanel.airdropid = airdropid;
        MyPanel.camprushtime = camprush;
        MyPanel.camptime = camptime;
        boolean jump = Math.abs(MyPanel.hour * 60 + MyPanel.minute - (hour * 60 + minute)) > 10;
        MyPanel.realtime = realtime;
        MyPanel.minute = minute;
        MyPanel.hour = hour;
        MyPanel.day = day;
        MyPanel.week = week;
        MyPanel.timefast = timefast;
        if (Math.abs(MyPanel.rainamounted - rainamounted) > 1e-6) {
            MyPanel.rainamounted = rainamounted;
        }
        if (Math.abs(MyPanel.rainamount - rainamount) > 0.2) {
            MyPanel.rainamount = rainamount;
        }
        // keep the client's own timers far from zero: it never rolls its own weather
        MyPanel.raintime = Math.max(raintime, 100000);
        MyPanel.rainrandom = Math.max(rainrandom, 100000);
        MyPanel.sunnycloude = sunny;
        if (MyPanel.Other != null) {
            MyPanel.Other.cloud = cloud;
            MyPanel.Other.cloudtime = Math.max(cloudtime, 100000);
        }
        MyPanel.windang = windang;
        MyPanel.windstrong = windstrong;
        if (jump) {
            MyPanel.timehour = true;
        }
        MyPanel.missionmodekind = missionmode;
        if (MyPanel.Direction != null && mission >= 0) {
            boolean changed = MyPanel.Direction.missionuming != mission || MyPanel.Direction.missionwidthnow != width
                    || MyPanel.Direction.missionwidthfinish != finish || MyPanel.Direction.targetnow != target;
            if (changed) {
                boolean newMission = MyPanel.Direction.missionuming != mission;
                MyPanel.Direction.missionuming = mission;
                MyPanel.Direction.missionwidthnow = width;
                MyPanel.Direction.missionwidthfinish = finish;
                MyPanel.Direction.targetnow = target;
                MissionMirror.refresh(newMission);
            }
        }
    }
}
