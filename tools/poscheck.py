"""Compares zombie/NPC positions of host (7801) and client (7802) at the same moment.
python -I tools/poscheck.py [ROUNDS]"""
import math
import socket
import sys
import threading
import time


def query(port, out):
    s = socket.create_connection(("127.0.0.1", port), timeout=10)
    f = s.makefile("rb")
    s.sendall(b"zpos\n")
    lines = []
    while True:
        line = f.readline()
        if not line:
            break
        text = line.decode("utf-8", "replace").rstrip("\r\n")
        if text == ".":
            break
        lines.append(text)
    s.close()
    out[port] = lines[-1] if lines else ""


def parse(text):
    parts = text.split()
    t = int(parts[0][2:])
    pos = {}
    for p in parts[1:]:
        k, v = p.split(":")
        x, y = v.split(",")
        pos[k] = (int(x), int(y))
    return t, pos


def main():
    rounds = int(sys.argv[1]) if len(sys.argv) > 1 else 10
    allz = []
    allh = []
    for _ in range(rounds):
        out = {}
        th = [threading.Thread(target=query, args=(p, out)) for p in (7801, 7802)]
        for t in th:
            t.start()
        for t in th:
            t.join()
        th_, hp = parse(out[7801])
        tc_, cp = parse(out[7802])
        common = [k for k in hp if k in cp]
        dz = [math.dist(hp[k], cp[k]) for k in common if k[0] == "z"]
        dh = [math.dist(hp[k], cp[k]) for k in common if k[0] == "h"]
        allz += dz
        allh += dh
        print("dt=%dms zombies host=%d client=%d gemeinsam=%d  max=%.0f  npcs gemeinsam=%d max=%.0f" % (
            tc_ - th_, sum(1 for k in hp if k[0] == "z"), sum(1 for k in cp if k[0] == "z"), len(dz),
            max(dz) if dz else 0, len(dh), max(dh) if dh else 0))
        time.sleep(0.4)
    if allz:
        allz.sort()
        print("Zombies: Mittel %.1f  Median %.1f  95%% %.1f  Max %.1f Einheiten" % (
            sum(allz) / len(allz), allz[len(allz) // 2], allz[int(len(allz) * 0.95)], allz[-1]))
    if allh:
        allh.sort()
        print("NPCs:    Mittel %.1f  Median %.1f  Max %.1f Einheiten" % (sum(allh) / len(allh), allh[len(allh) // 2], allh[-1]))


if __name__ == "__main__":
    main()
