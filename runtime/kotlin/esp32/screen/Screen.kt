// TFT display API for KTVM programs: an ILI9341 controller (240x320, RGB565), the screen of the
// ESP32-2432S028R "Cheap Yellow Display" and of most 2.4"/2.8" SPI modules.
//
// The firmware turns every call into the ILI9341 command stream (CASET/PASET/RAMWR + pixels, the same bytes as
// Adafruit_ILI9341) and sends it either
//   - over SPI to a real module:            Screen.ili9341(TftPins.CYD)
//   - over USB to the emulator on the Mac:  Screen.simulated()   (./ktesp screen)
// The program is identical in both cases. Text uses the classic 6x8 Adafruit GFX font (accents via CP437).
package esp32.screen

external fun tftBegin(mode: Int, rotation: Int, sck: Int, mosi: Int, miso: Int, cs: Int, dc: Int, rst: Int, bl: Int,
                      spiHz: Int): Boolean
external fun tftWidth(): Int
external fun tftHeight(): Int
external fun tftFillRect(x: Int, y: Int, w: Int, h: Int, rgb: Int)
external fun tftDrawPixel(x: Int, y: Int, rgb: Int)
external fun tftDrawLine(x0: Int, y0: Int, x1: Int, y1: Int, rgb: Int)
external fun tftDrawRect(x: Int, y: Int, w: Int, h: Int, rgb: Int)
external fun tftFillCircle(x: Int, y: Int, r: Int, rgb: Int)
external fun tftFillRoundRect(x: Int, y: Int, w: Int, h: Int, r: Int, rgb: Int)
external fun tftText(x: Int, y: Int, text: String, rgb: Int, bgRgb: Int, size: Int)
external fun tftInvert(on: Boolean)
external fun tftBacklight(on: Boolean)

object Color {
    /** "No colour": transparent text background. */
    const val NONE = -1
    const val BLACK = 0x000000
    const val WHITE = 0xFFFFFF
    const val GRAY = 0x808080
    const val DARK_GRAY = 0x303030
    const val LIGHT_GRAY = 0xC0C0C0
    const val RED = 0xE53935
    const val ORANGE = 0xFB8C00
    const val YELLOW = 0xFDD835
    const val GREEN = 0x43A047
    const val BLUE = 0x1E88E5
    const val CYAN = 0x00ACC1
    const val PURPLE = 0x8E24AA
    fun rgb(r: Int, g: Int, b: Int) = ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
}

/** SPI wiring of an ILI9341 module (-1 = not connected). */
class TftPins(val sck: Int, val mosi: Int, val miso: Int, val cs: Int, val dc: Int, val rst: Int, val bl: Int) {
    companion object {
        /** ESP32-2432S028R "Cheap Yellow Display" (ILI9341 on HSPI, backlight on GPIO21). */
        val CYD = TftPins(sck = 14, mosi = 13, miso = 12, cs = 15, dc = 2, rst = -1, bl = 21)
        /** Typical ESP32 DevKit + ILI9341 module wiring (VSPI pins; backlight LED pin to 3.3V). */
        val DEVKIT = TftPins(sck = 18, mosi = 23, miso = 19, cs = 15, dc = 2, rst = 4, bl = -1)
    }
}

/**
 * A 16-bit colour TFT. Colours are 0xRRGGBB (converted to RGB565 by the firmware).
 * Drawing goes straight to the panel: to avoid flicker, repaint regions instead of clearing the whole screen.
 */
class Screen private constructor(mode: Int, rotation: Int, pins: TftPins, spiHz: Int) {
    val width: Int
    val height: Int

    init {
        if (!tftBegin(mode, rotation, pins.sck, pins.mosi, pins.miso, pins.cs, pins.dc, pins.rst, pins.bl, spiHz))
            throw IllegalStateException("display not available (SPI needs a real ESP32)")
        width = tftWidth()
        height = tftHeight()
    }

    fun clear(c: Int = Color.BLACK) = tftFillRect(0, 0, width, height, c)
    fun fillRect(x: Int, y: Int, w: Int, h: Int, c: Int) = tftFillRect(x, y, w, h, c)
    fun drawRect(x: Int, y: Int, w: Int, h: Int, c: Int) = tftDrawRect(x, y, w, h, c)
    fun fillRoundRect(x: Int, y: Int, w: Int, h: Int, r: Int, c: Int) = tftFillRoundRect(x, y, w, h, r, c)
    fun drawLine(x0: Int, y0: Int, x1: Int, y1: Int, c: Int) = tftDrawLine(x0, y0, x1, y1, c)
    fun drawPixel(x: Int, y: Int, c: Int) = tftDrawPixel(x, y, c)
    fun fillCircle(x: Int, y: Int, r: Int, c: Int) = tftFillCircle(x, y, r, c)
    fun invert(on: Boolean) = tftInvert(on)
    fun backlight(on: Boolean) = tftBacklight(on)

    /**
     * Text with its top-left corner at (x, y); each character is a 6x8 cell scaled by [size].
     * With a [bg] colour the cells are painted opaque (much faster: one address window per string).
     */
    fun text(x: Int, y: Int, text: String, c: Int = Color.WHITE, size: Int = 1, bg: Int = Color.NONE) =
        tftText(x, y, text, c, bg, size)

    /** Text centred horizontally inside [x, x + w). */
    fun textCentered(x: Int, w: Int, y: Int, text: String, c: Int = Color.WHITE, size: Int = 1, bg: Int = Color.NONE) =
        text(x + (w - textWidth(text, size)) / 2, y, text, c, size, bg)

    fun textRight(right: Int, y: Int, text: String, c: Int = Color.WHITE, size: Int = 1, bg: Int = Color.NONE) =
        text(right - textWidth(text, size), y, text, c, size, bg)

    /** clear + body */
    inline fun frame(background: Int = Color.BLACK, body: Screen.() -> Unit) {
        clear(background)
        body()
    }

    companion object {
        /** ILI9341 on the emulator of `./ktesp screen`, through the USB serial link. */
        fun simulated(rotation: Int = 1): Screen = Screen(0, rotation, TftPins(-1, -1, -1, -1, -1, 0, 0), 0)

        /** A real ILI9341 module on SPI. rotation 1 = landscape 320x240. */
        fun ili9341(pins: TftPins, rotation: Int = 1, spiHz: Int = 40000000): Screen = Screen(1, rotation, pins, spiHz)

        const val CHAR_W = 6
        const val CHAR_H = 8

        /** Number of visible characters (UTF-8 aware: works with KTVM byte strings and JVM strings). */
        fun chars(s: String): Int {
            var n = 0
            for (k in 0 until s.length) if ((s[k].code and 0xC0) != 0x80) n++
            return n
        }

        fun textWidth(s: String, size: Int = 1) = chars(s) * CHAR_W * size

        /** Cuts [s] so it fits in [maxWidth] pixels, adding "..." when it is shortened. */
        fun ellipsize(s: String, maxWidth: Int, size: Int = 1): String {
            val max = maxWidth / (CHAR_W * size)
            if (chars(s) <= max) return s
            if (max <= 3) return prefix(s, maxOf(max, 0))
            var head = prefix(s, max - 3)
            while (head.endsWith(" ")) head = head.substring(0, head.length - 1)
            return head + "..."
        }

        /** First [n] visible characters of [s]. */
        fun prefix(s: String, n: Int): String {
            var seen = 0
            for (k in 0 until s.length) {
                if ((s[k].code and 0xC0) != 0x80) {
                    if (seen == n) return s.substring(0, k)
                    seen++
                }
            }
            return s
        }

        /** Word-wraps [s] into lines of at most [maxWidth] pixels (at most [maxLines]; the last one ellipsized). */
        fun wrap(s: String, maxWidth: Int, size: Int = 1, maxLines: Int = 100): List<String> {
            val max = maxWidth / (CHAR_W * size)
            val out = ArrayList<String>()
            var line = ""
            val words = s.split(" ")
            var w = 0
            while (w < words.size) {
                val word = words[w]
                val cand = if (line.isEmpty()) word else "$line $word"
                if (chars(cand) <= max || line.isEmpty()) {
                    line = cand
                    w++
                } else {
                    out.add(ellipsize(line, maxWidth, size))
                    line = ""
                    if (out.size == maxLines) break
                }
            }
            if (out.size == maxLines) {
                val rest = ArrayList<String>()
                rest.add(out[maxLines - 1])
                while (w < words.size) { rest.add(words[w]); w++ }
                out[maxLines - 1] = ellipsize(rest.joinToString(" "), maxWidth, size)
            } else if (line.isNotEmpty()) {
                out.add(ellipsize(line, maxWidth, size))
            }
            return out
        }
    }
}
