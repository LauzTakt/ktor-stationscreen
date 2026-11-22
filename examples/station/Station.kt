// Station departure board for a 320x240 TFT, in the visual language of SBB (docs/STYLE.md of ktor_app):
// a white surface, charcoal text, greys for structure, red only for disruptions and delays, sentence case.
//
//   GET /api/v1/stations/{stopId}/screen   -> next departures and the incidents that concern the station
//
// The display has one 6x8 bitmap font, so hierarchy comes from size (time and destination at 2x, details at 1x), a
// doubled stroke for bold, and the layout: time | line | destination, the delay under the time.
package station

import esp32.*
import esp32.json.*
import esp32.net.*
import esp32.screen.*

// ------------------------------------------------------------------ SBB palette (Lyne tokens)
const val RED = 0xEB0000
const val CHARCOAL = 0x212121
const val GRANITE = 0x686868
const val METAL = 0x767676
const val SMOKE = 0x8D8D8D
const val SILVER = 0xDCDCDC
const val MILK = 0xF6F6F6
const val WHITE = 0xFFFFFF
const val BLUE = 0x2D327D
const val GREEN = 0x00973B
const val ORANGE = 0xF27E00

// ------------------------------------------------------------------ layout (320x240, landscape)
const val HEADER_H = 30
const val BAND_H = 14
const val ROW_H = 32
const val ROWS = 5
const val FOOTER_H = 36
const val MARGIN = 8

// ------------------------------------------------------------------ model
class Departure(
    val planned: Long, val delaySec: Int, val line: String, val to: String, val cancelled: Boolean, val atStation: Boolean,
)

class Incident(val id: String, val severity: String, val title: String, val atStation: Boolean, val trains: Int)

class Panel {
    var name = ""
    var departures: List<Departure> = ArrayList()
    var incidents: List<Incident> = ArrayList()
    private var clockOffset = 0L          // server epoch seconds - local seconds
    var lastUpdateMs = 0
    var hasData = false
    var live = false
    var demo = false
    var status = "Connecting"

    fun now(): Long = clockOffset + millis() / 1000

    fun load(json: String) {
        val o = Json.parseObject(json)
        val serverNow = o.long("now") / 1000
        if (serverNow > 0) clockOffset = serverNow - millis() / 1000
        name = o.str("name")
        val d = ArrayList<Departure>()
        for (x in o.arr("departures").objects()) {
            d.add(Departure(
                planned = x.long("plannedTime") / 1000, delaySec = x.int("delaySec"), line = x.str("line", "?"),
                to = x.str("to"), cancelled = x.bool("cancelled"), atStation = x.bool("atStation"),
            ))
        }
        val i = ArrayList<Incident>()
        for (x in o.arr("incidents").objects()) {
            i.add(Incident(x.str("id"), x.str("severity", "info"), x.str("title"), x.bool("atStation"), x.int("trains")))
        }
        departures = d
        incidents = i
        lastUpdateMs = millis()
        hasData = true
    }
}

// ------------------------------------------------------------------ time (Swiss local time, with daylight saving)
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era.toLong() * 146097 + doe - 719468
}

private fun yearOf(days: Long): Int {
    val z = days + 719468
    val era = (if (z >= 0) z else z - 146096) / 146097
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val month = if (mp < 10) mp + 3 else mp - 9
    return (yoe + era * 400 + (if (month <= 2) 1 else 0)).toInt()
}

private fun lastSunday(year: Int, month: Int): Long {
    val last = daysFromCivil(year, month, 31)
    val dow = (((last + 4) % 7) + 7) % 7        // 0 = Sunday
    return last - dow
}

/** Minutes since midnight in Switzerland (CET, CEST from the last Sunday of March to the last of October, 01:00 UTC). */
fun localMinutes(epoch: Long): Int {
    val year = yearOf(epoch / 86400)
    val start = lastSunday(year, 3) * 86400 + 3600
    val end = lastSunday(year, 10) * 86400 + 3600
    val offset = if (epoch >= start && epoch < end) 7200L else 3600L
    return ((((epoch + offset) % 86400) + 86400) % 86400 / 60).toInt()
}

private fun two(n: Int) = if (n < 10) "0$n" else "$n"
fun clock(epoch: Long): String { val m = localMinutes(epoch); return "${two(m / 60)}:${two(m % 60)}" }

fun ago(seconds: Long): String = when {
    seconds < 5 -> "now"
    seconds < 60 -> "${seconds}s ago"
    else -> "${seconds / 60} min ago"
}

// ------------------------------------------------------------------ drawing
/** Bold from a doubled stroke: the 6x8 font has one weight. */
fun bold(s: Screen, x: Int, y: Int, text: String, c: Int, size: Int, bg: Int) {
    s.text(x, y, text, c, size, bg)
    s.text(x + 1, y, text, c, size, Color.NONE)
}

fun boldRight(s: Screen, right: Int, y: Int, text: String, c: Int, size: Int, bg: Int) =
    bold(s, right - Screen.textWidth(text, size) - 1, y, text, c, size, bg)

/** Line label: long-distance products filled red, regional ones outlined (as in SBB timetables). */
fun badge(s: Screen, x: Int, y: Int, line: String, dim: Boolean) {
    val w = 40
    val h = 18
    val long = line.startsWith("IC") || line.startsWith("IR") || line.startsWith("EC") || line.startsWith("TGV") ||
        line.startsWith("RJX") || line.startsWith("ICE")
    val label = Screen.ellipsize(line, w - 4)
    if (dim) {
        s.fillRoundRect(x, y, w, h, 4, WHITE)
        s.drawRect(x, y, w, h, SILVER)
        s.textCentered(x, w, y + 5, label, SMOKE, 1, WHITE)
    } else if (long) {
        s.fillRoundRect(x, y, w, h, 4, RED)
        bold(s, x + (w - Screen.textWidth(label)) / 2, y + 5, label, WHITE, 1, RED)
    } else {
        s.fillRoundRect(x, y, w, h, 4, CHARCOAL)
        s.fillRoundRect(x + 1, y + 1, w - 2, h - 2, 3, WHITE)
        bold(s, x + (w - Screen.textWidth(label)) / 2, y + 5, label, CHARCOAL, 1, WHITE)
    }
}

fun header(s: Screen, b: Panel) {
    val w = s.width
    s.fillRect(0, 0, w, HEADER_H, WHITE)
    val name = if (b.hasData && b.name.isNotEmpty()) b.name else "Departures"
    bold(s, MARGIN, 7, Screen.ellipsize(name, w - 2 * MARGIN - 70, 2), CHARCOAL, 2, WHITE)
    if (b.hasData) boldRight(s, w - MARGIN, 7, clock(b.now()), CHARCOAL, 2, WHITE)
    // column band
    s.fillRect(0, HEADER_H, w, BAND_H, MILK)
    s.text(MARGIN, HEADER_H + 3, "Time", METAL, 1, MILK)
    s.text(72, HEADER_H + 3, "Train", METAL, 1, MILK)
    s.text(120, HEADER_H + 3, "To", METAL, 1, MILK)
}

fun row(s: Screen, d: Departure, y: Int, separator: Boolean) {
    val w = s.width
    s.fillRect(0, y, w, ROW_H, WHITE)
    val ink = if (d.cancelled) SMOKE else CHARCOAL
    bold(s, MARGIN, y + 3, clock(d.planned), ink, 2, WHITE)
    val late = (d.delaySec + 59) / 60
    when {
        d.cancelled -> s.text(MARGIN, y + 21, "Cancelled", RED, 1, WHITE)
        d.atStation -> s.text(MARGIN, y + 21, "At station", BLUE, 1, WHITE)
        d.delaySec >= 60 -> bold(s, MARGIN, y + 21, "+$late'", RED, 1, WHITE)
    }
    badge(s, 72, y + 7, d.line, d.cancelled)
    // the destination at 2x; a name that does not fit is shown whole at 1x rather than cut short
    val room = w - 120 - MARGIN
    val big = Screen.textWidth(d.to, 2) <= room
    val size = if (big) 2 else 1
    val to = Screen.ellipsize(d.to, room, size)
    val ty = if (big) y + 8 else y + 12
    bold(s, 120, ty, to, ink, size, WHITE)
    if (d.cancelled) s.drawLine(120, ty + 4 * size, 120 + Screen.textWidth(to, size), ty + 4 * size, SMOKE)
    if (separator) s.drawLine(MARGIN, y + ROW_H - 1, w - MARGIN, y + ROW_H - 1, SILVER)
}

fun footer(s: Screen, b: Panel, page: Int) {
    val w = s.width
    val fy = s.height - FOOTER_H
    s.fillRect(0, fy, w, FOOTER_H, WHITE)
    s.drawLine(0, fy, w, fy, SILVER)
    val source = if (b.demo) "Demo" else if (b.live) "Live" else b.status
    val upd = if (b.hasData) "$source · ${ago((millis() - b.lastUpdateMs) / 1000L)}" else source
    s.textRight(w - MARGIN, fy + 5, upd, if (b.live || b.demo) METAL else RED, 1, WHITE)
    if (b.incidents.isEmpty()) {
        s.fillCircle(MARGIN + 3, fy + 8, 3, GREEN)
        s.text(MARGIN + 12, fy + 5, "No disruptions", GREEN, 1, WHITE)
        return
    }
    val n = b.incidents.size
    val inc = b.incidents[page % n]
    // a shape and a word, not colour alone: a red disc and "Disruption"
    s.fillCircle(MARGIN + 3, fy + 8, 3, if (inc.severity == "critical" || inc.severity == "major") RED else ORANGE)
    bold(s, MARGIN + 12, fy + 5, if (n > 1) "Disruption ${page % n + 1} of $n" else "Disruption", CHARCOAL, 1, WHITE)
    val lines = Screen.wrap(inc.title, w - 2 * MARGIN, 1, 2)
    for (k in 0 until lines.size) s.text(MARGIN, fy + 16 + k * 10, lines[k], CHARCOAL, 1, WHITE)
}

fun render(s: Screen, b: Panel, page: Int) {
    header(s, b)
    val top = HEADER_H + BAND_H
    val list = b.departures
    if (!b.hasData || list.isEmpty()) {
        s.fillRect(0, top, s.width, s.height - top - FOOTER_H, WHITE)
        s.textCentered(0, s.width, top + 62, if (b.hasData) "No departures" else b.status, CHARCOAL, 2, WHITE)
    } else {
        for (slot in 0 until ROWS) {
            val y = top + slot * ROW_H
            if (slot < list.size) row(s, list[slot], y, slot < ROWS - 1 && slot + 1 < list.size)
            else s.fillRect(0, y, s.width, ROW_H, WHITE)
        }
    }
    footer(s, b, page)
}

fun splash(s: Screen, line1: String, line2: String) {
    s.clear(WHITE)
    s.textCentered(0, s.width, 100, line1, CHARCOAL, 2, WHITE)
    s.textCentered(0, s.width, 126, line2, METAL, 1, WHITE)
}

// ------------------------------------------------------------------ main
fun main() {
    val screen = createScreen()
    screen.clear(WHITE)
    val board = Panel()
    if (DEMO) return runDemo(screen, board)
    val sim = Board.name == "simulator"
    val host = if (sim) "127.0.0.1" else API_HOST
    val url = "http://$host:$API_PORT/api/v1/stations/$STATION_ID/screen"

    if (!sim) {
        splash(screen, "Connecting", WIFI_SSID)
        while (!wifiConnect(WIFI_SSID, WIFI_PASSWORD, 20000)) {
            println("WiFi: cannot connect, retrying...")
            splash(screen, "No WiFi", "retrying $WIFI_SSID")
        }
        println("WiFi OK, IP ${wifiIp()}")
    }

    var page = 0
    var nextPageMs = millis() + 6000
    var nextPollMs = 0
    var nextRenderMs = 0
    while (true) {
        val now = millis()
        if (now >= nextPollMs) {
            val body = Http.getOrNull(url)
            if (body != null) {
                try {
                    board.load(body)
                    board.live = true
                } catch (e: JsonException) {
                    println("ignored response: ${e.message}")
                }
            } else {
                board.live = false
                board.status = "Offline"
                println("API not reachable ($url)")
            }
            nextPollMs = now + 5000
            nextRenderMs = 0
        }
        if (now >= nextPageMs) { page++; nextPageMs = now + 6000; nextRenderMs = 0 }
        if (now >= nextRenderMs) {
            render(screen, board, page)
            nextRenderMs = now + 1000
        }
        delay(100)
    }
}
