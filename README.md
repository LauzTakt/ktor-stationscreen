# KTVM: Kotlin on the ESP32

Run **real Kotlin programs** (compiled with the official `kotlinc`) on an ESP32. The firmware is flashed once;
after that, each program is compiled, linked and uploaded over USB in a few seconds.

```kotlin
import esp32.*

fun main() {
    val led = Led(LED_BUILTIN)
    loop(periodMs = 500) { led.toggle() }
}
```

```sh
./ktesp run examples/Blink.kt      # compile + upload + show the serial output
```

## How it works

```
 Main.kt ──kotlinc──▶ .class ──linker.py──▶ .ktv image ──USB──▶ ESP32: KTVM (JVM bytecode interpreter)
                         ▲                      │
           runtime/shim (mini Java stdlib) ─────┘
```

1. **`kotlinc`** compiles your code, plus `runtime/kotlin/esp32/` (the device API), to JVM bytecode.
   The flags `-Xlambdas=class -Xsam-conversions=class -jvm-target 1.8` avoid `invokedynamic`.
2. **`runtime/shim/`** is a mini standard library written in Java (`String`, `StringBuilder`, `Integer`,
   `ArrayList`, `HashMap`, ranges, Kotlin `Intrinsics`...) under the `ktshim/` package. The linker strips that
   prefix, so `ktshim/java/util/ArrayList` becomes `java/util/ArrayList`.
3. **`tools/linker.py`**:
   - runs *Rapid Type Analysis* from `main()`, so only reachable methods are included
     (`Hello.kt` goes from 801 methods to 162, and from 111 KB to 25 KB);
   - compacts and resolves the constant pool to numeric ids. The bytecode stays as it is: only operands are
     renumbered, keeping their width, so jumps are untouched;
   - computes vtables, field layouts, line numbers, etc.
4. **`firmware/`** (PlatformIO + Arduino) is the VM in C++:
   - an interpreter for almost every JVM opcode, including `long` and `double`, and exceptions with `try/catch`;
   - a conservative mark-and-sweep GC over a heap of about 80 KB;
   - natives for `String`, the hardware, WiFi/HTTP/WebSocket and the TFT display;
   - a FreeRTOS task for the VM and a serial receiver that accepts new programs while running;
   - the last program is stored in the second app partition (`app1`, no OTA is used) and runs straight from flash
     through the MMU, so it takes no RAM; it starts automatically at power-on;
   - the WiFi driver is started at boot, before the Kotlin heap is allocated, so it always has the ~50 KB it needs;
   - https/wss need ~40 KB more (TLS), which is only kept free when the firmware is built with `TLS_RESERVE`.
5. **`host/`** builds the same VM for the Mac (`./ktesp sim`), so you can test without a board.

## Usage

```sh
./ktesp flash                       # (once) build and flash the KTVM firmware
./ktesp run examples/Hello.kt       # compile, upload and open the monitor (Ctrl-C to quit)
./ktesp sim examples/Hello.kt       # run in the desktop simulator
./ktesp build a.kt b.kt -o p.ktv    # only build the image
./ktesp upload p.ktv | ./ktesp monitor
./ktesp screen examples/trains      # upload + open the TFT emulator (see below)
./ktesp config station=8501120      # store a setting on the board (see "Device settings")
```

A folder can be passed instead of files: all its `.kt` files are compiled, and the one containing `fun main(`
names the program.

## Hardware API (`import esp32.*`)

| Function | Description |
|---|---|
| `pinMode(pin, OUTPUT/INPUT/INPUT_PULLUP)` | configure a GPIO |
| `digitalWrite(pin, HIGH/LOW or Boolean)`, `digitalRead(pin)` | digital I/O |
| `analogRead(pin)` (0..4095), `analogWrite(pin, 0..255)` | ADC and PWM |
| `touchRead(pin)`, `chipTemperature()` | touch sensor and internal temperature |
| `delay(ms)`, `millis()`, `micros()`, `Thread.sleep(ms)` | time |
| `tone(pin, hz, ms)`, `noTone(pin)` | buzzer |
| `randomInt(n)`, `freeMemory()` | utilities |
| `Led(pin)`, `Button(pin)`, `loop(periodMs) { }` | helper classes |
| `Board.name`, `Board.ledPin` | board the program is running on |
| `Device.id`, `Device.config(key)` | unique hardware id and per-device settings |

### Device settings

Each board can store `key=value` settings in its flash, outside the program, so many boards can run the same
program image and still know who they are (for example, which station they serve):

```sh
./ktesp config station=8501120      # set
./ktesp config station=             # remove
./ktesp config                      # show: "device config: station=8501120 [id 004B12307914]"
```

The program reads them with `Device.config("station")`; the board restarts the program when a setting changes.
In the simulator, pass them with `--config station=8501120` (`./ktesp sim` and `./ktesp screen --sim`).

| Package | API | Description |
|---|---|---|
| `esp32.net` | `wifiConnect`, `wifiIp`, `ntpSync(server)`, `Http.get(url)`, `ConditionalGet(url).poll()` (ETag, 304), `WebSocket(url)` (`connect`/`receive`/`send`/`close`) | WiFi, NTP clock, HTTP/1.1 and WebSocket client (also `https`/`wss` on the ESP32, without certificate validation); WebSocket messages too large for the board are skipped |
| `esp32` | `epochMillis()` | UTC wall clock (after `ntpSync`), 0 if never set |
| `esp32.json` | `Json.parseObject(text)`, `JsonObject.str/int/long/obj/arr`, `Json.stringify` | JSON without reflection |
| `esp32.screen` | `Screen.simulated()` / `Screen.ili9341(pins)`, `fillRect`, `fillRoundRect`, `fillCircle`, `drawLine`, `text(x, y, s, color, size, bg)`, `textCentered`, `wrap`, `ellipsize`... | 320x240 ILI9341 TFT |

To add a native: append its signature **at the end** of `runtime/natives.txt` (the position is the id), declare it
with `external fun` in Kotlin, run `python3 tools/natives.py` (regenerates `natives_gen.h`), implement it in
`firmware/src/natives.cpp` and run `./ktesp flash`.

## Example: train disruption board

```sh
./ktesp screen examples/trains          # ESP32 plugged in: upload the program and open the screen emulator
./ktesp screen --sim examples/trains    # no board: run it in the Mac simulator
./ktesp screen                          # just watch the program already running on the ESP32
```

`examples/trains/Trains.kt` is a station departure board. Each board serves one station, whose UIC/DIDOK ID is a
device setting (`./ktesp config station=8501008`; without it, `DEFAULT_STATION` in `Config.kt`). At start-up it
connects to WiFi, **sets its clock with NTP** (`NTP_SERVER` in `Config.kt`, an IP address) and then polls the twin API
of `ktor_app` (`docs/API.md`, "Station screen, text form"):

```
GET /api/v1/stations/8501008/screen.txt?limit=6&incidents=3      every 30 s, with If-None-Match: <ETag>

S|Genève|04:23|1795317787                    station, Swiss local time, epoch seconds
D|04:46|0|RE33|RE|Genève-Aéroport|0|0        planned time, delay (min), line, category, destination, cancelled, at station
I|minor|0|1|RE33 to Genève-Aéroport: late|…  severity, at the station, trains affected, title, where
```

The server sends only what the screen draws, as text (about 100-300 bytes, no JSON parser on the board), and an
unchanged board answers 304 with no body. Every byte of RAM counts on the board:
- plain `http://` to an IP address (`API_BASE`): TLS would need ~40 KB of contiguous RAM next to the Kotlin heap, and
  the IP avoids the DNS lookup;
- one short HTTP request per poll, no WebSocket; the program image is 33 KB and runs from flash;
- the firmware keeps no RAM for TLS by default (`TLS_RESERVE`, build with `-DTLS_RESERVE=28672` for https programs).

The API address can also be a device setting (`./ktesp config api=http://...`) that overrides `API_BASE`. Left
empty, the board uses the mock API of `./ktesp screen` on the computer (`MOCK_HOST`).

Enrichment on the board: Swiss local time (for the clock; departure times come ready from the server), delay in minutes, cancelled trains, line
pictograms (long-distance products in red), the worst incident first in a rotating banner. Drawing is incremental:
each zone (header, banner, every row, footer) remembers what it shows and is only repainted when that changes.

With `DEMO = true` in `Config.kt` the board needs no API: `Demo.kt` builds the same `screen.txt` documents from a
clock-face timetable around Lausanne and a scripted sequence of incidents.

`./ktesp screen` (`tools/screen.py` + `tools/screen_ui.html`) is the Mac app, opened as a Chrome app window:
- it follows the USB connection: with no ESP32 plugged in the emulated display has no power and stays black;
  when a board is plugged in it powers up with it (the board boots and its stored program draws the screen),
  and it goes black again when the board is unplugged;
- it reads the serial port (or the simulator), separates the display traffic from the console text and shows the
  emulated TFT;
- it serves a **mock of the twin API v1** (`tools/mock_api.py`: stations, boards, incidents, the `screen` and
  `screen.txt` views and the WebSocket topics `station` and `screen`) on port 8765, with buttons to create, update and resolve incidents.
  `./ktesp screen --sim` points the simulated board at it automatically.

The configuration (WiFi, API, NTP server, display) lives in `examples/trains/Config.kt`, which is not committed to
git. Use `Config.kt.example` as a template.

### Display: real or emulated ILI9341

The firmware includes an **ILI9341** driver (`firmware/src/display.cpp`): the 240x320 SPI TFT of the
"Cheap Yellow Display" (ESP32-2432S028R) and of most 2.4" and 2.8" modules. Every `Screen` call becomes the
controller's protocol, the same bytes `Adafruit_ILI9341` sends:
- a reset pulse on the RST pin and the init sequence (`PWCTR`, `VMCTR`, gamma, `COLMOD=55`, `SLPOUT`, `DISPON`);
- `MADCTL` for the rotation;
- for each drawing, the address window with `CASET`/`PASET`, then `RAMWR` with RGB565 pixels.

That byte stream goes to one of two buses:

| | Kotlin | Bus |
|---|---|---|
| Real display | `Screen.ili9341(TftPins.CYD)` or `TftPins.DEVKIT` | SPI at 40 MHz (with the CS, D/C, RST and LED lines) |
| Mac emulator | `Screen.simulated()` | USB serial: the same bytes in `0x1E 'L' len payload xor` frames, with D/C, RST and LED encoded |

On the USB link, fills and text travel compressed (run-length and 1 bit per pixel). The encoding is lossless: the
Mac rebuilds exactly the bytes that would go over SPI. `tools/screen.py` models the controller after its datasheet:
- 240x320 GRAM, address window with auto-increment;
- `MADCTL` (MY/MX/MV/BGR), 16 and 18-bit `COLMOD`;
- sleep, display on/off, inversion, vertical scroll, `SWRESET`, reset pin and backlight.

The window shows the glass as it would look, the controller registers, the decoded bus commands and the equivalent
SPI traffic. If the program sent a sequence a real display would not accept, the emulator shows it too: without
`SLPOUT`/`DISPON` the panel stays white, and without the BGR bit red and blue are swapped.

Moving to a physical display only means changing `createScreen()` in `Config.kt`; the rest of the program stays
the same. Text uses the classic 6x8 Adafruit GFX font (`firmware/src/font5x7.h`, BSD licence), including the
accented letters of the CP437 character set. A real TFT shows every write immediately, so to avoid flicker,
repaint by zones instead of clearing the whole screen.

## What works

Classes, inheritance, interfaces (with default methods), `object`/`companion`, `data class`, `sealed`, `enum`
(`entries`, `when`), generics, lambdas and closures, higher-order and `inline` functions, custom exceptions,
`lateinit`, null safety (`?.`, `?:`, `!!`), string templates, `String.format`, arrays (including multidimensional
ones), mutable and immutable `List`/`Set`/`Map`, `map`/`filter`/`sortedBy`/`joinToString`/`sum`..., ranges (`..`,
`until`, `downTo`, `step`), `Int`/`Long`/`Float`/`Double`/`Char` with exact Java semantics.

The tests in `tests/` give **the same output on the ESP32, in the simulator and on a real JVM**.

## Limitations

- No reflection (`::class`, `Enum.valueOf`), coroutines or threads.
- `String`s are UTF-8 bytes: `length` and `charAt` count bytes, which is fine for ASCII.
- The standard library is partial. If you use something that is missing, the linker warns
  (`warning ... is not available on the device`) and, if that line runs, the VM stops with a clear error.
  To add it, put it in `runtime/shim/`.
- It is an interpreter: about 10-50 times slower than native C, with a heap of about 80 KB.

## Setup notes

Requires `brew install kotlin openjdk@21`. PlatformIO lives in `.pio-venv/` and the CLI uses `.venv/` (pyserial).
On networks with TLS inspection (corporate proxy), `.ca-bundle.pem` holds the CAs of the system keychain so that
PlatformIO can download packages.
