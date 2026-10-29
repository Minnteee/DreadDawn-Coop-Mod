"""Send commands to a running test instance: python dbg.py PORT "cmd" ["cmd" ...]
A command "sleep N" waits N seconds locally."""
import socket
import sys
import time


def main():
    port = int(sys.argv[1])
    s = socket.create_connection(("127.0.0.1", port), timeout=60)
    f = s.makefile("rb")
    for cmd in sys.argv[2:]:
        if cmd.startswith("sleep "):
            time.sleep(float(cmd.split()[1]))
            continue
        s.sendall((cmd + "\n").encode("utf-8"))
        out = []
        while True:
            line = f.readline()
            if not line:
                break
            text = line.decode("utf-8", "replace").rstrip("\r\n")
            if text == ".":
                break
            out.append(text)
        print("> " + cmd + "\n" + "\n".join(out), flush=True)
    s.close()


if __name__ == "__main__":
    main()
