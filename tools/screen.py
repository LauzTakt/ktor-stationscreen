"""KTVM screen simulator: an emulated ILI9341 TFT for the ESP32.

The ESP32 firmware drives the display with the real ILI9341 SPI protocol (firmware/src/display.cpp). With
Screen.simulated() that byte stream (plus the RST/backlight lines) travels over the USB serial port in "link"
frames; this program decodes them, feeds them to a model of the ILI9341 controller and shows its glass in a web
page, next to the console text.

It also serves a mock train-incidents API so the whole chain can be tested without a real backend:
    GET  /api/incidents         -> {"now": <epoch s>, "incidents": [...]}
    WS   /ws/incidents          -> {"type": "upsert", "incident": {...}} / {"type": "resolved", "id": ...}
    POST /api/mock/<action>     -> new | update | resolve | clear | auto-on | auto-off (used by the page)

Only the Python standard library is used (plus pyserial for the ESP32).
"""
import base64
import hashlib
import json
import os
import queue
import random
import select
import socket
import struct
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from mock_api import MockTwinApi, WsSession

HERE = os.path.dirname(os.path.abspath(__file__))
UI_FILE = os.path.join(HERE, "screen_ui.html")
CMD = 0x1E


def log(msg):
    print(f"\033[35m[screen]\033[0m {msg}", file=sys.stderr, flush=True)


def lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


# ------------------------------------------------------------------ ILI9341 emulator
CMD_NAMES = {
    0x00: "NOP", 0x01: "SWRESET", 0x10: "SLPIN", 0x11: "SLPOUT", 0x12: "PTLON", 0x13: "NORON", 0x20: "INVOFF",
    0x21: "INVON", 0x26: "GAMSET", 0x28: "DISPOFF", 0x29: "DISPON", 0x2A: "CASET", 0x2B: "PASET", 0x2C: "RAMWR",
    0x33: "VSCRDEF", 0x36: "MADCTL", 0x37: "VSCRSADD", 0x38: "IDMOFF", 0x39: "IDMON", 0x3A: "COLMOD",
    0x3C: "RAMWRC", 0xB1: "FRMCTR1", 0xB6: "DFUNCTR", 0xC0: "PWCTR1", 0xC1: "PWCTR2", 0xC5: "VMCTR1",
    0xC7: "VMCTR2", 0xE0: "PGAMCTRL", 0xE1: "NGAMCTRL", 0xCF: "PWCTRB", 0xED: "PWONSEQ", 0xE8: "DTCTRA",
    0xCB: "PWCTRA", 0xF7: "PUMPRC", 0xEA: "DTCTRB", 0xEF: "(vendor EF)", 0xF2: "EN3G",
}
GW, GH = 240, 320   # GRAM / glass: 240 columns x 320 rows (portrait)


class Ili9341:
    """Behavioural model of the ILI9341 controller, from its datasheet:
    command/parameter decoding, CASET/PASET address window, RAMWR/RAMWRC with auto-increment and wrap,
    MADCTL (MY/MX/MV/BGR), COLMOD 16/18 bpp, SLPIN/SLPOUT, DISPON/DISPOFF, INVON/INVOFF, VSCRSADD, SWRESET,
    hardware reset and backlight lines. The GRAM is kept in glass coordinates (what you see on the panel)."""

    def __init__(self, on_log):
        self.log = on_log
        self.gram = bytearray(os.urandom(GW * GH * 2))   # power-on: undefined memory contents
        self.dirty = None
        self.version = 0
        self.spi_bytes = 0
        self.stats = {"cmds": 0, "pixels": 0, "resets": 0, "unknown": set()}
        self.backlight = 0
        self.powered = True
        self.reset_state("power-on")

    def power(self, on):
        """Supply voltage of the module: it follows the ESP32 being plugged in."""
        if on == self.powered:
            return
        self.powered = on
        if on:
            self.gram[:] = os.urandom(GW * GH * 2)   # power-on: undefined memory contents
            self.backlight = 0
            self.reset_state("power-on")
        else:
            self.backlight = 0
            self.log("power off")
            self.touch()

    def reset_state(self, why):
        self.madctl = 0x00
        self.colmod = 0x66             # 18 bit/pixel after reset (datasheet default)
        self.sleep = True
        self.display_on = False
        self.inverted = False
        self.vscroll = 0
        self.col = [0, GW - 1]
        self.page = [0, GH - 1]
        self.cmd = None
        self.params = bytearray()
        self.writing = False
        self.cur = None
        self.partial = b""
        self.stats["resets"] += 1
        self.log(f"reset ({why}): MADCTL=00 COLMOD=66 sleep-in display-off")
        self.touch()

    def touch(self, box=None):
        self.version += 1
        if box is None:
            box = (0, 0, GW - 1, GH - 1)
        if self.dirty is None:
            self.dirty = list(box)
        else:
            d = self.dirty
            d[0] = min(d[0], box[0]); d[1] = min(d[1], box[1]); d[2] = max(d[2], box[2]); d[3] = max(d[3], box[3])

    def state(self):
        return {"power": self.powered, "madctl": self.madctl, "colmod": self.colmod, "sleep": self.sleep, "on": self.display_on,
                "inv": self.inverted, "bgr": bool(self.madctl & 0x08), "bl": self.backlight, "vscroll": self.vscroll,
                "window": [self.col[0], self.page[0], self.col[1], self.page[1]], "spiBytes": self.spi_bytes,
                "cmds": self.stats["cmds"], "pixels": self.stats["pixels"]}

    # -- lines
    def hw_reset(self, level):
        if level == 0:
            self._rst_low = True
        elif getattr(self, "_rst_low", False):
            self._rst_low = False
            self.reset_state("RST pin")

    def set_backlight(self, level):
        self.backlight = 1 if level else 0
        self.touch()

    # -- command / data
    def command(self, c):
        self.spi_bytes += 1
        self._finish_cmd()
        self.stats["cmds"] += 1
        self.cmd, self.params, self.writing = c, bytearray(), False
        if c == 0x01:
            self.reset_state("SWRESET")
        elif c == 0x11:
            self.sleep = False; self.touch(); self.log("SLPOUT: sleep out")
        elif c == 0x10:
            self.sleep = True; self.touch(); self.log("SLPIN: sleep in")
        elif c == 0x29:
            self.display_on = True; self.touch(); self.log("DISPON: display on")
        elif c == 0x28:
            self.display_on = False; self.touch(); self.log("DISPOFF: display off")
        elif c == 0x21:
            self.inverted = True; self.touch(); self.log("INVON")
        elif c == 0x20:
            self.inverted = False; self.touch(); self.log("INVOFF")
        elif c == 0x2C:
            self.writing, self.cur, self.partial = True, [self.col[0], self.page[0]], b""
        elif c == 0x3C:
            self.writing, self.partial = True, b""
            if self.cur is None:
                self.cur = [self.col[0], self.page[0]]
        elif c not in CMD_NAMES:
            self.stats["unknown"].add(c)

    def _finish_cmd(self):
        c, p = self.cmd, self.params
        if c is None:
            return
        if c == 0x2A and len(p) >= 4:
            self.col = [p[0] << 8 | p[1], p[2] << 8 | p[3]]
        elif c == 0x2B and len(p) >= 4:
            self.page = [p[0] << 8 | p[1], p[2] << 8 | p[3]]
        elif c == 0x36 and p:
            if self.madctl != p[0]:
                self.log(f"MADCTL={p[0]:02X} (MY={p[0] >> 7 & 1} MX={p[0] >> 6 & 1} MV={p[0] >> 5 & 1} "
                         f"BGR={p[0] >> 3 & 1})")
            self.madctl = p[0]; self.touch()
        elif c == 0x3A and p:
            self.colmod = p[0]
            self.log(f"COLMOD={p[0]:02X} ({'16' if (p[0] & 7) == 5 else '18' if (p[0] & 7) == 6 else '?'} bit/pixel)")
        elif c == 0x37 and len(p) >= 2:
            self.vscroll = (p[0] << 8 | p[1]) % GH; self.touch()
        elif c not in (0x2C, 0x3C) and c in CMD_NAMES and p and c not in (0x2A, 0x2B):
            pass  # power/gamma/timing registers: accepted, no visible effect in the model
        self.cmd = None

    def data(self, b):
        self.spi_bytes += len(b)
        if self.writing:
            if (self.colmod & 7) == 6:  # 18 bpp: 3 bytes per pixel (R, G, B in the top 6 bits)
                b = self.partial + bytes(b)
                n = len(b) // 3
                self.partial = b[n * 3:]
                hi = bytearray(n); lo = bytearray(n)
                for i in range(n):
                    r, g, bl = b[3 * i] >> 3, b[3 * i + 1] >> 2, b[3 * i + 2] >> 3
                    v = r << 11 | g << 5 | bl
                    hi[i], lo[i] = v >> 8, v & 0xFF
                self._pixels(bytes(hi), bytes(lo))
            else:
                b = self.partial + bytes(b)
                n = len(b) // 2
                self.partial = b[n * 2:]
                if n:
                    self._pixels(b[0:2 * n:2], b[1:2 * n:2])
        else:
            self.params += b

    def fill(self, hi, lo, count):
        if not self.writing or (self.colmod & 7) != 5 or self.partial:
            # not the 16-bit fast path: replay the exact byte stream
            chunk = bytes([hi, lo]) * 4096
            while count > 0:
                k = min(count, 4096)
                self.data(chunk[:2 * k])
                count -= k
            return
        self.spi_bytes += 2 * count
        while count > 0:
            k = min(count, 65536)
            self._pixels(bytes([hi]) * k, bytes([lo]) * k)
            count -= k

    def mono(self, fg, bg, n, bits):
        expanded = bytes(n)  # 1 byte per pixel: 0/1
        tbl = MONO_TABLE
        expanded = b"".join(tbl[x] for x in bits)[:n]
        hi = expanded.translate(bytes([bg >> 8, fg >> 8]) + bytes(254))
        lo = expanded.translate(bytes([bg & 0xFF, fg & 0xFF]) + bytes(254))
        if not self.writing or (self.colmod & 7) != 5 or self.partial:
            raw = bytearray(2 * n)
            raw[0::2] = hi
            raw[1::2] = lo
            self.data(bytes(raw))
            return
        self.spi_bytes += 2 * n
        self._pixels(hi, lo)

    # -- GRAM writes
    def _limits(self):
        mv = self.madctl & 0x20
        return (GH, GW) if mv else (GW, GH)   # logical columns, pages

    def _phys(self, c, p):
        m = self.madctl
        a, b = (p, c) if m & 0x20 else (c, p)
        x = a if m & 0x40 else GW - 1 - a
        y = GH - 1 - b if m & 0x80 else b
        return x, y

    def _pixels(self, hi, lo):
        n = len(hi)
        self.stats["pixels"] += n
        c0, c1 = self.col
        p0, p1 = self.page
        if c1 < c0 or p1 < p0:
            return
        maxc, maxp = self._limits()
        i = 0
        cur = self.cur
        while i < n:
            c, p = cur
            k = min(n - i, c1 - c + 1)
            if k <= 0:
                cur[0], cur[1] = c0, (p + 1 if p < p1 else p0)
                continue
            if p < maxp and c < maxc:
                kk = min(k, maxc - c)
                x0, y0 = self._phys(c, p)
                x1, y1 = self._phys(c + kk - 1, p)
                step = (x1 - x0) + (y1 - y0) * GW
                step = 0 if kk == 1 else step // (kk - 1)
                self._put(y0 * GW + x0, step, hi[i:i + kk], lo[i:i + kk])
                self.touch((min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1)))
            i += k
            c += k
            if c > c1:
                c = c0
                p = p + 1 if p < p1 else p0
            cur[0], cur[1] = c, p

    def _put(self, first, step, hi, lo):
        n = len(hi)
        if n == 1 or step == 0:
            self.gram[2 * first] = hi[0]; self.gram[2 * first + 1] = lo[0]
            return
        if step < 0:
            first, step, hi, lo = first + step * (n - 1), -step, hi[::-1], lo[::-1]
        a = 2 * first
        end = a + 2 * step * (n - 1) + 1
        self.gram[a:end:2 * step] = hi
        self.gram[a + 1:end + 1:2 * step] = lo

    def take_dirty(self):
        """Returns (x, y, w, h, rgb565 big-endian bytes) of the changed region, or None."""
        d, self.dirty = self.dirty, None
        if d is None:
            return None
        x0, y0, x1, y1 = d
        w = x1 - x0 + 1
        rows = [bytes(self.gram[2 * (y * GW + x0): 2 * (y * GW + x1 + 1)]) for y in range(y0, y1 + 1)]
        return x0, y0, w, y1 - y0 + 1, b"".join(rows)


MONO_TABLE = [bytes((v >> (7 - i)) & 1 for i in range(8)) for v in range(256)]


class LinkDecoder:
    """Decodes the link frames (see firmware/src/display.cpp) into the ILI9341's SPI stream."""

    def __init__(self, lcd, on_error):
        self.lcd, self.err = lcd, on_error
        self.frames = 0
        self.bad = 0

    def frame(self, p):
        self.frames += 1
        lcd, i, n = self.lcd, 0, len(p)
        try:
            while i < n:
                op = p[i]
                if op == 1:
                    lcd.command(p[i + 1]); i += 2
                elif op == 2:
                    k = p[i + 1]; lcd.data(p[i + 2:i + 2 + k]); i += 2 + k
                elif op == 3:
                    cnt = int.from_bytes(p[i + 3:i + 7], "little"); lcd.fill(p[i + 1], p[i + 2], cnt); i += 7
                elif op == 4:
                    fg = p[i + 1] << 8 | p[i + 2]; bg = p[i + 3] << 8 | p[i + 4]
                    k = p[i + 5] | p[i + 6] << 8; nb = (k + 7) // 8
                    lcd.mono(fg, bg, k, p[i + 7:i + 7 + nb]); i += 7 + nb
                elif op == 5:
                    lcd.hw_reset(p[i + 1]); i += 2
                elif op == 6:
                    lcd.set_backlight(p[i + 1]); i += 2
                elif op == 7:
                    i += 3
                else:
                    self.bad += 1; self.err(f"unknown link op {op:#x}"); return
        except IndexError:
            self.bad += 1
            self.err("truncated link frame")


# ------------------------------------------------------------------ hub: device output -> browsers
class Hub:
    def __init__(self):
        self.lock = threading.Lock()
        self.clients = []
        self.console = []
        self.buslog = []
        self.lcd = Ili9341(self._buslog)
        self.link = LinkDecoder(self.lcd, lambda m: self._buslog("error: " + m))
        threading.Thread(target=self._present_loop, daemon=True).start()

    def subscribe(self):
        q = queue.Queue(maxsize=500)
        with self.lock:
            lcd = self.lcd
            q.put(("full", {"data": base64.b64encode(bytes(lcd.gram)).decode(), "state": lcd.state()}))
            if self.console:
                q.put(("console", "".join(self.console)))
            if self.buslog:
                q.put(("bus", self.buslog[-200:]))
            self.clients.append(q)
        return q

    def unsubscribe(self, q):
        with self.lock:
            if q in self.clients:
                self.clients.remove(q)

    def _send(self, kind, data):
        for q in list(self.clients):
            try:
                q.put_nowait((kind, data))
            except queue.Full:
                pass

    def _buslog(self, msg):
        self.buslog.append(msg)
        if len(self.buslog) > 500:
            self.buslog = self.buslog[-300:]
        self._send("bus", [msg])

    def power(self, on):
        with self.lock:
            self.lcd.power(on)

    def link_frame(self, payload):
        with self.lock:
            self.link.frame(payload)

    def text(self, s):
        if not s:
            return
        with self.lock:
            self.console.append(s)
            if len(self.console) > 400:
                self.console = self.console[-300:]
            self._send("console", s)

    def _present_loop(self):
        """~30 Hz: send the changed GRAM region and the controller state to the browsers."""
        last_state = None
        while True:
            time.sleep(1 / 30)
            with self.lock:
                d = self.lcd.take_dirty()
                st = self.lcd.state()
            if d:
                x, y, w, h, data = d
                self._send("gram", {"x": x, "y": y, "w": w, "h": h, "data": base64.b64encode(data).decode(),
                                    "state": st})
            elif st != last_state:
                self._send("state", st)
            last_state = st

    @property
    def stats(self):
        st = self.lcd.state()
        return {"frames": self.link.frames, "badFrames": self.link.bad, "spiBytes": st["spiBytes"]}


class StreamParser:
    """Splits the serial byte stream into link frames (0x1E 'L' len16 payload xor8) and console text."""

    def __init__(self, hub):
        self.hub = hub
        self.buf = bytearray()
        self.skip = True   # the first partial line after connecting is usually the tail of a frame

    def feed(self, data):
        b = self.buf
        b += data
        text = bytearray()
        i = 0
        while i < len(b):
            ch = b[i]
            if ch != 0x1E:
                # Console text. Binary bytes (e.g. the tail of a frame when we connected mid-stream) mark the
                # line as garbage and it is dropped up to the next newline.
                if ch == 0x0A:
                    if not self.skip:
                        text.append(ch)
                    self.skip = False
                elif ch == 0x06:   # upload acks
                    pass
                elif ch < 0x20 and ch not in (0x09, 0x0D):
                    self.skip = True
                elif not self.skip:
                    text.append(ch)
                i += 1
                continue
            if len(b) - i < 4:
                break            # wait for the header
            if b[i + 1] != ord("L"):
                i += 1
                continue
            n = b[i + 2] | b[i + 3] << 8
            if n == 0 or n > 4096:
                i += 1
                continue
            if len(b) - i < 5 + n:
                break            # wait for the rest of the frame
            payload = bytes(b[i + 4:i + 4 + n])
            x = 0
            for v in payload:
                x ^= v
            if x != b[i + 4 + n]:
                self.hub.link.bad += 1
                j = b.find(b"\x1eL", i + 1)
                i = j if j >= 0 else len(b)
                continue
            if text:
                self.hub.text(text.decode("utf-8", "ignore")); text.clear()
            self.hub.link_frame(payload)
            i += 5 + n
        del b[:i]
        if text:
            self.hub.text(text.decode("utf-8", "ignore"))


# ------------------------------------------------------------------ sources
class SimSource:
    def __init__(self, sim, image, parser, extra_args=()):
        self.sim, self.image, self.parser = sim, image, parser
        self.extra_args = list(extra_args)
        self.proc = None
        self.lock = threading.Lock()

    def start(self):
        with self.lock:
            self.stop_locked()
            self.parser.skip = False
            self.proc = subprocess.Popen([self.sim, "-q"] + self.extra_args + [self.image], stdout=subprocess.PIPE,
                                         stderr=subprocess.STDOUT, bufsize=0)
            threading.Thread(target=self._pump, args=(self.proc,), daemon=True).start()
        self.parser.hub.text(f"[screen] simulator: {os.path.basename(self.image)}\n")

    def _pump(self, proc):
        while True:
            d = os.read(proc.stdout.fileno(), 4096)
            if not d:
                break
            self.parser.feed(d)
        code = proc.wait()
        self.parser.feed(f"\n[screen] program exited (code {code})\n".encode())

    def stop_locked(self):
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(2)
            except subprocess.TimeoutExpired:
                self.proc.kill()

    def stop(self):
        with self.lock:
            self.stop_locked()

    def restart(self):
        self.start()

    label = property(lambda self: "simulator (Mac)")


class DeviceSource:
    """The ESP32 on USB, with hot-plug: while no board is connected the emulated display has no power (black);
    when one is plugged in it is opened (which boots it), and the display powers up with it."""

    def __init__(self, parser, find, connect):
        self.parser = parser
        self.find = find          # () -> port name or None
        self.connect = connect    # (port) -> (serial, bytes already read)
        self.ser = None
        self.port = None
        self.wlock = threading.Lock()

    def start(self):
        self.parser.hub.power(False)
        threading.Thread(target=self._supervise, daemon=True).start()

    def _supervise(self):
        waiting_said = False
        while True:
            port = self.find()
            if not port:
                if not waiting_said:
                    self.parser.hub.text("[screen] waiting for an ESP32 on USB...\n")
                    waiting_said = True
                time.sleep(0.5)
                continue
            waiting_said = False
            try:
                time.sleep(0.8)   # let the USB-serial device settle after plugging
                ser, pending = self.connect(port)
            except Exception as e:
                self.parser.hub.text(f"[screen] cannot open {port}: {e}\n")
                time.sleep(2)
                continue
            self.ser, self.port = ser, port
            self.parser.hub.text(f"[screen] ESP32 connected on {port}\n")
            self.parser.hub.power(True)
            self.parser.skip = not pending
            if pending:
                self.parser.feed(pending)
            self._pump(ser)
            # unplugged
            self.ser = None
            self.parser.hub.power(False)
            self.parser.hub.text(f"\n[screen] ESP32 disconnected from {port}\n")
            try:
                ser.close()
            except Exception:
                pass

    def _pump(self, ser):
        # The reader only reads: parsing/emulation runs in another thread so the UART never overflows.
        q = queue.Queue()
        done = threading.Event()

        def parse():
            while not (done.is_set() and q.empty()):
                try:
                    d = q.get(timeout=0.2)
                except queue.Empty:
                    continue
                while not q.empty() and len(d) < 65536:
                    d += q.get()
                self.parser.feed(d)

        t = threading.Thread(target=parse, daemon=True)
        t.start()
        while True:
            try:
                d = ser.read(ser.in_waiting or 1)
            except Exception:
                break
            if d:
                q.put(d)
            elif not os.path.exists(self.port):
                break
        done.set()
        t.join(2)

    def _write(self, b):
        with self.wlock:
            if self.ser:
                try:
                    self.ser.write(b)
                except Exception:
                    pass

    def stop(self):
        self._write(b"\x7fKTS")

    def restart(self):
        self._write(b"\x7fKTR")

    label = property(lambda self: f"ESP32 ({self.port})" if self.ser else "no ESP32 connected: waiting for USB")


# ------------------------------------------------------------------ WebSocket server (minimal)
def ws_send(sock, text):
    data = text.encode()
    n = len(data)
    if n < 126:
        hdr = struct.pack("!BB", 0x81, n)
    elif n < 65536:
        hdr = struct.pack("!BBH", 0x81, 126, n)
    else:
        hdr = struct.pack("!BBQ", 0x81, 127, n)
    sock.sendall(hdr + data)


def _recv_exact(sock, n):
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            return None
        buf += chunk
    return buf


def ws_read_frame(sock):
    """Reads one client frame: (opcode, payload) or (None, None) if the peer went away."""
    h = _recv_exact(sock, 2)
    if not h:
        return None, None
    n = h[1] & 0x7F
    if n == 126:
        n = struct.unpack("!H", _recv_exact(sock, 2))[0]
    elif n == 127:
        n = struct.unpack("!Q", _recv_exact(sock, 8))[0]
    mask = _recv_exact(sock, 4) if h[1] & 0x80 else None
    data = _recv_exact(sock, n) if n else b""
    if data is None:
        return None, None
    if mask:
        data = bytes(b ^ mask[i & 3] for i, b in enumerate(data))
    return h[0] & 0x0F, data


# ------------------------------------------------------------------ HTTP server
class App:
    def __init__(self, source, hub, api, port):
        self.source, self.hub, self.api, self.port = source, hub, api, port


def make_handler(app):
    class H(BaseHTTPRequestHandler):
        server_version = "ktvm-screen"

        def log_message(self, *a):
            pass

        def _json(self, obj, code=200):
            body = json.dumps(obj, ensure_ascii=False).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            path = self.path.split("?")[0]
            if path == "/":
                body = open(UI_FILE, "rb").read()
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            elif path == "/events":
                self._events()
            elif path == "/api/v1/ws" and self.headers.get("Upgrade", "").lower() == "websocket":
                self._websocket()
            elif path.startswith("/api/v1/"):
                code, obj = app.api.get(path, parse_qs(urlparse(self.path).query))
                self._json(obj, code)
            elif path == "/api/info":
                self._json({"source": app.source.label, "lanIp": lan_ip(), "port": app.port,
                            "auto": app.api.auto, "ws": len(app.api.sessions), **app.hub.stats})
            else:
                self._json({"error": "not found"}, 404)

        def do_POST(self):
            path = self.path.split("?")[0]
            n = int(self.headers.get("Content-Length") or 0)
            if n:
                self.rfile.read(n)
            if path.startswith("/api/mock/"):
                self._json(app.api.action(path.rsplit("/", 1)[1]))
            elif path == "/api/device/restart":
                app.source.restart()
                self._json({"ok": True})
            elif path == "/api/device/stop":
                app.source.stop()
                self._json({"ok": True})
            else:
                self._json({"error": "not found"}, 404)

        def _events(self):
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.end_headers()
            q = app.hub.subscribe()
            try:
                while True:
                    try:
                        kind, data = q.get(timeout=15)
                        msg = f"event: {kind}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"
                    except queue.Empty:
                        msg = ": ping\n\n"
                    self.wfile.write(msg.encode())
                    self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError, OSError):
                pass
            finally:
                app.hub.unsubscribe(q)

        def _websocket(self):
            key = self.headers.get("Sec-WebSocket-Key", "")
            acc = base64.b64encode(hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest())
            self.wfile.write(b"HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                             b"Sec-WebSocket-Accept: " + acc + b"\r\n\r\n")
            self.wfile.flush()
            sock = self.connection
            wlock = threading.Lock()

            def send(obj):
                with wlock:
                    try:
                        ws_send(sock, json.dumps(obj, ensure_ascii=False))
                    except OSError:
                        pass

            session = WsSession(app.api, send)
            app.api.sessions.append(session)
            peer = self.client_address[0]
            app.hub.text(f"[api] websocket connected from {peer}\n")
            try:
                while True:
                    r, _, _ = select.select([sock], [], [], 0.2)
                    if r:
                        op, data = ws_read_frame(sock)
                        if op is None or op == 0x8:
                            break
                        if op == 0x9:
                            with wlock:
                                sock.sendall(bytes([0x8A, len(data)]) + data)
                        elif op == 0x1:
                            session.on_text(data.decode("utf-8", "replace"))
                    session.tick()
            except OSError:
                pass
            finally:
                app.api.sessions.remove(session)
                app.hub.text(f"[api] websocket disconnected ({peer})\n")
            self.close_connection = True

    return H


def serve(source, port=8765, open_browser=True):
    hub = source.parser.hub
    api = MockTwinApi(hub.text)
    app = App(source, hub, api, port)
    ThreadingHTTPServer.daemon_threads = True
    ThreadingHTTPServer.allow_reuse_address = True
    httpd = ThreadingHTTPServer(("0.0.0.0", port), make_handler(app))
    url = f"http://localhost:{port}/"
    log(f"screen emulator at {url}")
    log(f"mock twin API: http://{lan_ip()}:{port}/api/v1  ws://{lan_ip()}:{port}/api/v1/ws")
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    source.start()
    if open_browser:
        open_window(url)
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print()
    finally:
        if isinstance(source, SimSource):
            source.stop()
        httpd.shutdown()


def open_window(url):
    """Opens the page as an app window (Chrome/Edge --app) when available, else in the default browser."""
    for app in ("Google Chrome", "Microsoft Edge", "Brave Browser", "Chromium"):
        if os.path.isdir(f"/Applications/{app}.app"):
            subprocess.Popen(["open", "-na", app, "--args", f"--app={url}", "--window-size=1180,820"],
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            return
    subprocess.Popen(["open", url], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def make_parser():
    return StreamParser(Hub())
