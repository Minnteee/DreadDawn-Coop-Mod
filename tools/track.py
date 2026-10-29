"""Samples a field on host (7801) and client (7802) every ~40 ms and prints the series + step sizes.
python -I tools/track.py FIELDX FIELDY SECONDS     e.g.  car.2.0 car.3.0 3"""
import socket
import sys
import threading
import time


class Conn:
    def __init__(self, port):
        self.s = socket.create_connection(("127.0.0.1", port), timeout=10)
        self.f = self.s.makefile("rb")

    def cmd(self, c):
        self.s.sendall((c + "\n").encode())
        out = []
        while True:
            line = self.f.readline().decode("utf-8", "replace").rstrip("\r\n")
            if line == ".":
                break
            out.append(line)
        return out[-1] if out else ""


def sample(port, fx, fy, secs, out):
    c = Conn(port)
    t0 = time.time()
    while time.time() - t0 < secs:
        try:
            x = float(c.cmd("get " + fx))
            y = float(c.cmd("get " + fy))
        except ValueError:
            x = y = float("nan")
        out.append((time.time() - t0, x, y))
        time.sleep(0.04)


def main():
    fx, fy, secs = sys.argv[1], sys.argv[2], float(sys.argv[3])
    res = {}
    th = []
    for p in (7801, 7802):
        res[p] = []
        t = threading.Thread(target=sample, args=(p, fx, fy, secs, res[p]))
        t.start()
        th.append(t)
    for t in th:
        t.join()
    for p in (7801, 7802):
        pts = res[p]
        steps = []
        for i in range(1, len(pts)):
            dt = pts[i][0] - pts[i - 1][0]
            d = ((pts[i][1] - pts[i - 1][1]) ** 2 + (pts[i][2] - pts[i - 1][2]) ** 2) ** 0.5
            steps.append(d / dt if dt > 0 else 0)
        moving = [s for s in steps if s > 1]
        if moving:
            avg = sum(moving) / len(moving)
            dev = (sum((s - avg) ** 2 for s in moving) / len(moving)) ** 0.5
            print("%d: n=%d moving=%d speed avg %.0f/s  std %.0f/s  max %.0f/s  min %.0f/s  zero-steps-while-moving=%d" % (
                p, len(pts), len(moving), avg, dev, max(moving), min(moving),
                sum(1 for i in range(1, len(steps) - 1) if steps[i] < 1 and steps[i - 1] > 1 and steps[i + 1] > 1)))
        else:
            print("%d: n=%d no movement" % (p, len(pts)))
        print("   " + " ".join("%.0f,%.0f" % (x, y) for _, x, y in pts[::4]))


if __name__ == "__main__":
    main()
