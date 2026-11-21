"""ktesp: compile Kotlin, link it into a KTVM image and run it on an ESP32 (or the simulator)."""
import argparse
import glob
import os
import shutil
import struct
import subprocess
import sys
import time
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))
import linker  # noqa: E402

BUILD = os.path.join(ROOT, "build")
SHIM_SRC = os.path.join(ROOT, "runtime", "shim")
SHIM_OUT = os.path.join(BUILD, "shim")
KT_LIB = os.path.join(ROOT, "runtime", "kotlin")
SIM = os.path.join(BUILD, "ktvm-sim")
FIRMWARE = os.path.join(ROOT, "firmware")
BAUD = 460800
MAGIC_UPLOAD = b"\x7fKTV"

KOTLINC_FLAGS = [
    "-jvm-target", "1.8",
    "-Xlambdas=class", "-Xsam-conversions=class",          # no invokedynamic
    "-Xno-param-assertions", "-Xno-call-assertions", "-Xno-receiver-assertions",
    "-Xstring-concat=inline",
    "-nowarn",
]


class Fail(Exception):
    pass


def log(msg):
    print(f"\033[36m[ktesp]\033[0m {msg}", file=sys.stderr)


def find_tool(name, candidates):
    for c in candidates:
        if c and os.path.isfile(c) and os.access(c, os.X_OK):
            return c
    p = shutil.which(name)
    if p:
        return p
    raise Fail(f"`{name}` not found (brew install kotlin openjdk@21)")


def javac():
    jh = os.environ.get("JAVA_HOME")
    return find_tool("javac", [jh and os.path.join(jh, "bin", "javac"),
                               "/opt/homebrew/opt/openjdk@21/bin/javac", "/opt/homebrew/opt/openjdk/bin/javac"])


def kotlinc():
    return find_tool("kotlinc", ["/opt/homebrew/bin/kotlinc"])


def newest_mtime(paths):
    return max((os.path.getmtime(p) for p in paths), default=0)


def build_shim():
    srcs = glob.glob(os.path.join(SHIM_SRC, "**", "*.java"), recursive=True)
    stamp = os.path.join(SHIM_OUT, ".stamp")
    if os.path.exists(stamp) and os.path.getmtime(stamp) >= newest_mtime(srcs):
        return
    log(f"compiling runtime library ({len(srcs)} files)")
    shutil.rmtree(SHIM_OUT, ignore_errors=True)
    os.makedirs(SHIM_OUT)
    r = subprocess.run([javac(), "--release", "8", "-nowarn", "-Xlint:-options", "-d", SHIM_OUT] + srcs,
                       capture_output=True, text=True)
    if r.returncode:
        raise Fail("runtime library failed to compile:\n" + r.stderr)
    open(stamp, "w").close()


def build_sim():
    srcs = [os.path.join(ROOT, "host", "host_main.cpp")] + glob.glob(os.path.join(FIRMWARE, "src", "*.cpp"))
    srcs = [s for s in srcs if not s.endswith("main.cpp") or "host" in s]
    hdrs = glob.glob(os.path.join(FIRMWARE, "src", "*.h"))
    if os.path.exists(SIM) and os.path.getmtime(SIM) >= newest_mtime(srcs + hdrs):
        return
    log("compiling simulator")
    cxx = shutil.which("clang++") or shutil.which("g++")
    r = subprocess.run([cxx, "-std=c++17", "-O2", "-o", SIM] + srcs, capture_output=True, text=True)
    if r.returncode:
        raise Fail("simulator failed to compile:\n" + r.stderr)


def expand_sources(files):
    """A directory stands for all its .kt files; the one with `fun main(` goes first (it names the program)."""
    out = []
    for f in files:
        if os.path.isdir(f):
            kts = sorted(glob.glob(os.path.join(f, "*.kt")))
            kts.sort(key=lambda k: "fun main(" not in open(k, encoding="utf-8").read())
            if not kts:
                raise Fail(f"no .kt files in {f}")
            out += kts
        else:
            out.append(f)
    return out


def compile_and_link(files, out=None, verbose=False):
    files = expand_sources(files)
    if not files:
        raise Fail("no .kt files given")
    for f in files:
        if not os.path.exists(f):
            raise Fail(f"file not found: {f}")
    os.makedirs(BUILD, exist_ok=True)
    build_shim()
    classes = os.path.join(BUILD, "classes")
    shutil.rmtree(classes, ignore_errors=True)
    lib = glob.glob(os.path.join(KT_LIB, "**", "*.kt"), recursive=True)
    t = time.time()
    log("kotlinc " + " ".join(os.path.basename(f) for f in files))
    r = subprocess.run([kotlinc()] + KOTLINC_FLAGS + ["-d", classes] + list(files) + lib,
                       capture_output=True, text=True)
    msgs = "\n".join(l for l in (r.stdout + r.stderr).splitlines() if "warning:" not in l or verbose)
    if r.returncode:
        raise Fail("compilation failed:\n" + msgs)
    hint = os.path.splitext(os.path.basename(files[0]))[0]
    hint = hint[0].upper() + hint[1:] + "Kt"
    try:
        img, lk = linker.link_dirs(classes, SHIM_OUT, entry_hint=hint)
    except linker.LinkError as e:
        raise Fail(f"link error: {e}")
    for w in lk.warnings:
        log(f"\033[33mwarning\033[0m {w}")
    out = out or os.path.join(BUILD, hint[:-2] + ".ktv")
    with open(out, "wb") as fh:
        fh.write(img)
    st = lk.stats
    log(f"image {os.path.relpath(out)}: {st['bytes']} bytes, {st['classes']} classes, "
        f"{st['methods']} methods, {st['code']} bytes of bytecode ({time.time() - t:.1f}s)")
    return out


# ------------------------------------------------------------------ serial
def find_port(port):
    if port:
        return port
    cands = sorted(glob.glob("/dev/cu.usbserial*") + glob.glob("/dev/cu.SLAB_USBtoUART*") +
                   glob.glob("/dev/cu.wchusbserial*") + glob.glob("/dev/cu.usbmodem*") + glob.glob("/dev/ttyUSB*"))
    if not cands:
        raise Fail("no ESP32 serial port found (use --port)")
    return cands[0]


def open_serial(port):
    import serial
    s = serial.Serial()
    s.port = port
    s.baudrate = BAUD
    s.timeout = 0.05
    s.dtr = False  # don't reset the board when opening
    s.rts = False
    s.open()
    return s


_pending = {}


def read_until(ser, token, timeout):
    """Read until `token` is seen; bytes after the token stay buffered for the next call."""
    buf = _pending.pop(id(ser), b"")
    end = time.time() + timeout
    while True:
        i = buf.find(token)
        if i >= 0:
            _pending[id(ser)] = buf[i + len(token):]
            return buf[:i + len(token)]
        if time.time() > end:
            raise Fail(f"timeout waiting for device ({token.decode(errors='replace').strip()!r}); "
                       "is the KTVM firmware flashed? (./ktesp flash)")
        buf += ser.read(4096)


def connect_device(port):
    """Opens the board's serial port. Opening it often resets the board (DTR/RTS glitch): if it is booting,
    wait for the banner so the next command is not lost."""
    ser = open_serial(find_port(port))
    t = time.time()
    boot = b""
    quiet_until = time.time() + 0.4
    while time.time() < quiet_until:
        d = ser.read(4096)
        if d:
            boot += d
            if b"KTVM ready" in boot:
                time.sleep(0.3)
                break
            quiet_until = max(quiet_until, min(time.time() + 0.5, t + 5))
    if boot:
        _pending[id(ser)] = boot   # keep the boot messages for whoever reads next
    return ser


def upload(path, port):
    data = open(path, "rb").read()
    ser = connect_device(port)
    log(f"uploading {len(data)} bytes to {ser.port}")
    t = time.time()
    ser.write(MAGIC_UPLOAD + struct.pack("<II", len(data), zlib.crc32(data) & 0xFFFFFFFF))
    read_until(ser, b"[ktvm] ready", 6)
    chunk = 512
    for off in range(0, len(data), chunk):
        ser.write(data[off:off + chunk])
        read_until(ser, b"\x06", 3)
    read_until(ser, b"[ktvm] upload ", 5)
    line = read_until(ser, b"\n", 2).decode(errors="replace").strip()
    if not line.startswith("ok"):
        raise Fail("upload failed: " + line)
    log(f"upload ok in {time.time() - t:.1f}s")
    return ser


def monitor(ser=None, port=None):
    ser = ser or open_serial(find_port(port))
    log(f"monitor on {ser.port} @ {BAUD} (Ctrl-C to exit)")
    rest = _pending.pop(id(ser), b"")
    if rest:
        sys.stdout.write(rest.replace(b"\x06", b"").decode("utf-8", "replace"))
    try:
        while True:
            d = ser.read(4096)
            if d:
                sys.stdout.write(d.replace(b"\x06", b"").decode("utf-8", "replace"))
                sys.stdout.flush()
    except KeyboardInterrupt:
        print()


def flash(port):
    pio = shutil.which("pio", path=os.path.join(ROOT, ".pio-venv", "bin")) or shutil.which("pio")
    if not pio:
        raise Fail("PlatformIO not found (.pio-venv/bin/pio)")
    args = [pio, "run", "-d", FIRMWARE, "-t", "upload"]
    if port:
        args += ["--upload-port", port]
    env = dict(os.environ)
    ca = os.path.join(ROOT, ".ca-bundle.pem")  # corporate TLS proxies: trust the system keychain CAs
    if os.path.exists(ca):
        env.setdefault("SSL_CERT_FILE", ca)
        env.setdefault("REQUESTS_CA_BUNDLE", ca)
    log("building & flashing firmware")
    if subprocess.run(args, env=env).returncode:
        raise Fail("firmware flash failed")


def screen_cmd(a):
    import screen
    img = None
    if a.files:
        img = a.files[0] if a.files[0].endswith(".ktv") else compile_and_link(a.files)
    parser = screen.make_parser()
    if a.sim:
        if not img:
            raise Fail("--sim needs a program (.kt files, folder or .ktv)")
        build_sim()
        extra = sim_args(a)
        if not any(kv.startswith("api=") for kv in (a.config or [])):
            extra += ["--config", f"api=http://127.0.0.1:{a.http_port}"]   # the mock API served by this app
        src = screen.SimSource(SIM, img, parser, extra)
    else:
        # Real board, with hot-plug: the window shows a black (unpowered) display until an ESP32 is plugged in.
        to_upload = [img] if img else []

        def find():
            try:
                return find_port(a.port) if not a.port or os.path.exists(a.port) else None
            except Fail:
                return None

        def connect(port):
            if to_upload:   # upload the program the first time a board shows up
                ser = upload(to_upload.pop(), port)
            else:
                ser = connect_device(port)
            return ser, _pending.pop(id(ser), b"")

        if not find():
            log("no ESP32 connected: the screen stays off until you plug one in (or use --sim)")
        src = screen.DeviceSource(parser, find, connect)
    screen.serve(src, a.http_port, not a.no_open)
    return 0


def device_config(settings, port):
    """Sets key=value pairs stored on the board (or just shows them), e.g. station=8501120."""
    ser = connect_device(port)
    _pending.pop(id(ser), None)   # ignore the boot banner (it also prints the config)
    time.sleep(0.2)
    ser.reset_input_buffer()
    for kv in settings or [""]:
        if kv and "=" not in kv:
            raise Fail(f"expected key=value, got {kv!r}")
        data = kv.encode()
        ser.write(b"\x7fKTC" + struct.pack("<H", len(data)) + data)
        read_until(ser, b"[ktvm] config", 4)
        line = read_until(ser, b"\n", 2).decode(errors="replace").strip()
        if line.startswith("error"):
            raise Fail("config " + line)
    print("device config" + line)
    ser.close()


def sim_args(a):
    return sum((["--config", kv] for kv in (getattr(a, "config", None) or [])), [])


# ------------------------------------------------------------------ main
def main(argv=None):
    ap = argparse.ArgumentParser(prog="ktesp", description="Run Kotlin on an ESP32")
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("build", help="compile + link .kt files into a .ktv image")
    p.add_argument("files", nargs="+"); p.add_argument("-o", "--out"); p.add_argument("-v", "--verbose", action="store_true")
    p = sub.add_parser("sim", help="run .kt files (or a .ktv image) in the desktop simulator")
    p.add_argument("files", nargs="+"); p.add_argument("-q", "--quiet", action="store_true", help="hide GPIO trace")
    p.add_argument("--config", action="append", metavar="KEY=VALUE", help="device setting seen by the program")
    p = sub.add_parser("run", help="compile, upload to the ESP32 and show its output")
    p.add_argument("files", nargs="+"); p.add_argument("--port"); p.add_argument("--no-monitor", action="store_true")
    p = sub.add_parser("screen", help="screen simulator: shows the program's display (and a mock incidents API)")
    p.add_argument("files", nargs="*", help=".kt files / program folder / .ktv (none: watch the running ESP32)")
    p.add_argument("--sim", action="store_true", help="run in the desktop simulator instead of the ESP32")
    p.add_argument("--port"); p.add_argument("--http-port", type=int, default=8765)
    p.add_argument("--no-open", action="store_true", help="don't open the browser window")
    p.add_argument("--config", action="append", metavar="KEY=VALUE", help="device setting (simulator only)")
    p = sub.add_parser("config", help="show or set settings stored on the board (e.g. station=8501120)")
    p.add_argument("settings", nargs="*", metavar="KEY=VALUE", help="KEY= removes a setting"); p.add_argument("--port")
    p = sub.add_parser("upload", help="upload a .ktv image"); p.add_argument("image"); p.add_argument("--port")
    p = sub.add_parser("monitor", help="show ESP32 serial output"); p.add_argument("--port")
    p = sub.add_parser("flash", help="build & flash the KTVM firmware (once)"); p.add_argument("--port")
    a = ap.parse_args(argv)
    try:
        if a.cmd == "build":
            compile_and_link(a.files, a.out, a.verbose)
        elif a.cmd == "sim":
            img = a.files[0] if a.files[0].endswith(".ktv") else compile_and_link(a.files)
            build_sim()
            return subprocess.run([SIM] + (["-q"] if a.quiet else []) + sim_args(a) + [img]).returncode
        elif a.cmd == "run":
            img = compile_and_link(a.files)
            ser = upload(img, a.port)
            if not a.no_monitor:
                monitor(ser)
        elif a.cmd == "screen":
            return screen_cmd(a)
        elif a.cmd == "config":
            device_config(a.settings, a.port)
        elif a.cmd == "upload":
            upload(a.image, a.port)
        elif a.cmd == "monitor":
            monitor(port=a.port)
        elif a.cmd == "flash":
            flash(a.port)
    except Fail as e:
        print(f"\033[31m[ktesp] {e}\033[0m", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
