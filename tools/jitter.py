"""Measures how smoothly the other player's figure moves on the client.
python -I tools/jitter.py SECONDS [KEY]
The host (7801) walks with KEY (default D) for SECONDS; meanwhile the host's own position (slot 300) and
the client's copy of him (slot 299 on 7802) are sampled ~every 15 ms. Prints speed statistics, the number
of backward steps (the copy moving against the walking direction) and stalls (no movement while walking)."""
import socket
import statistics
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


def sample(port, slot, secs, out):
    c = Conn(port)
    t0 = time.time()
    while time.time() - t0 < secs:
        try:
            x = float(c.cmd("get doucount.0.%d" % slot))
            y = float(c.cmd("get doucount.1.%d" % slot))
            out.append((time.time() - t0, x, y))
        except ValueError:
            pass
        time.sleep(0.012)


def stats(name, pts):
    steps = []
    for i in range(1, len(pts)):
        dt = pts[i][0] - pts[i - 1][0]
        dx = pts[i][1] - pts[i - 1][1]
        if dt > 0:
            steps.append(dx / dt)
    moving = [s for s in steps if abs(s) > 1]
    if not moving:
        print("%s: no movement (%d samples)" % (name, len(pts)))
        return
    sign = 1 if statistics.mean(moving) > 0 else -1
    fwd = [s * sign for s in steps]
    walking = fwd[len(fwd) // 6: len(fwd) * 5 // 6]  # middle part, not start / stop
    back = sum(1 for s in walking if s < -5)
    stall = sum(1 for s in walking if abs(s) <= 1)
    print("%s: samples=%d speed avg %.0f/s std %.0f/s min %.0f max %.0f | backward steps %d, stalls %d (of %d)" % (
        name, len(pts), statistics.mean(walking), statistics.pstdev(walking), min(walking), max(walking), back, stall,
        len(walking)))


def main():
    secs = float(sys.argv[1]) if len(sys.argv) > 1 else 3
    key = sys.argv[2] if len(sys.argv) > 2 else "D"
    host, copy = [], []
    th = [threading.Thread(target=sample, args=(7801, 300, secs + 1.0, host)),
          threading.Thread(target=sample, args=(7802, 299, secs + 1.0, copy))]
    for t in th:
        t.start()
    k = Conn(7801)
    time.sleep(0.3)
    k.cmd("key %s down" % key)
    time.sleep(secs)
    k.cmd("key %s up" % key)
    for t in th:
        t.join()
    stats("host own figure (300) ", host)
    stats("client's copy (299)  ", copy)


if __name__ == "__main__":
    main()
