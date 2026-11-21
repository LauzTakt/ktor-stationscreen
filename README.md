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
   - the last program is stored in LittleFS and starts automatically at power-on.
5. **`host/`** builds the same VM for the Mac (`./ktesp sim`), so you can test without a board.

## Usage

```sh
./ktesp flash                       # (once) build and flash the KTVM firmware
./ktesp run examples/Hello.kt       # compile, upload and open the monitor (Ctrl-C to quit)
./ktesp sim examples/Hello.kt       # run in the desktop simulator
./ktesp build a.kt b.kt -o p.ktv    # only build the image
./ktesp upload p.ktv | ./ktesp monitor
./ktesp screen examples/trains      # upload + open the TFT emulator (see below)
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

| Package | API | Description |
|---|---|---|
| `esp32.net` | `wifiConnect`, `wifiIp`, `Http.get(url)`, `WebSocket(url)` (`connect`/`receive`/`send`/`close`) | HTTP/1.1 and WebSocket client (also `https`/`wss` on the ESP32, without certificate validation) |
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

`examples/trains/Trains.kt` fetches the current disruptions with a GET to `/api/incidents` and then listens to a
WebSocket on `/ws/incidents`. The enrichment happens on the board itself: colour by severity, relative times
("12 min ago"), worst delay, cancelled lines, sorting by severity, paging. The result is drawn as a Swiss-style
departure board on a 320x240 TFT: red header with the clock, white background, line pictograms, delays in red.

With `DEMO = true` in `Config.kt` the board needs no network: `Demo.kt` replays built-in sample disruptions
around Lausanne through the same JSON parsing and enrichment code.

`./ktesp screen` (`tools/screen.py` + `tools/screen_ui.html`) is the Mac app, opened as a Chrome app window:
- it reads the serial port (or the simulator), separates the display traffic from the console text and shows the
  emulated TFT;
- it serves a **mock disruptions API** (GET and WebSocket) on port 8765, with buttons to create, update and
  resolve disruptions.

The configuration (WiFi, address of the API, display) lives in `examples/trains/Config.kt`, which is not committed
to git. Use `Config.kt.example` as a template.

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
