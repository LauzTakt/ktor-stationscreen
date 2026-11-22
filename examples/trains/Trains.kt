// Station departure board for a 320x240 TFT, running in Kotlin on the ESP32.
//
// Each board serves one station: its UIC/DIDOK stop id is a device setting (`./ktesp config station=8501120`), so
// every screen runs the same program image. The API address can be a device setting too (`./ktesp config api=...`),
// overriding API_BASE in Config.kt.
//
// Start-up: WiFi -> NTP (wall clock) -> twin API. The data comes pre-digested for microcontrollers from the twin API
// (ktor_app, docs/API.md "Station screen, text form"), about 250 bytes of text, no JSON:
//
//   GET /api/v1/stations/{id}/screen.txt?limit=N&incidents=M     every POLL_SEC, with If-None-Match: <ETag>
//
//   S|Genève|04:15|1795317343                       station, Swiss local time, epoch seconds
//   D|04:46|1|RE33|RE|Genève-Aéroport|0|0           planned HH:MM, delay min, line, category, to, cancelled, at station
//   I|minor|0|1|RE33 to Genève-Aéroport: late|...   severity, at station, trains affected, title, where
//
// An unchanged board answers 304 with no body, so most polls cost a few hundred bytes of headers and no parsing.
// Plain HTTP to an IP address: no TLS (it needs ~40 KB of RAM) and no DNS lookup.
// Drawing is incremental: every zone remembers what it shows and is only repainted when that changes.
package trains

import esp32.*
import esp32.net.*
import esp32.screen.*

const val ROWS_MAX = 6
const val INCIDENTS_MAX = 3
const val POLL_SEC = 30
const val RETRY_SEC = 10

// ------------------------------------------------------------------ model
enum class Severity(val rank: Int) {
    CRITICAL(0), MAJOR(1), MINOR(2), INFO(3);

    companion object {
        fun of(s: String?) = when (s) {
            "critical" -> CRITICAL
            "major" -> MAJOR
            "minor" -> MINOR
            else -> INFO
        }
    }
}

class Departure(
    val line: String, val category: String, val destination: String,
    /** Planned departure, Swiss local "HH:MM" (from the server). */
    val planned: String, val delayMin: Int, val cancelled: Boolean, val atStation: Boolean,
)

class Incident(
    val title: String, val where: String, val severity: Severity,
    /** true = at the station itself; false = it only affects trains that will call here. */
    val atStation: Boolean, val trains: Int,
)

// ------------------------------------------------------------------ time (Europe/Zurich)
/** Days since 1970-01-01 -> year (Howard Hinnant's civil_from_days). */
fun yearOf(days: Long): Int {
    val z = days + 719468
    val era = (if (z >= 0) z else z - 146096) / 146097
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val m = if (mp < 10) mp + 3 else mp - 9
    return (yoe + era * 400 + (if (m <= 2) 1 else 0)).toInt()
}

/** Days since 1970-01-01 of the last Sunday of [month] in [year]. */
fun lastSunday(year: Int, month: Int): Long {
    val nextY = if (month == 12) year + 1 else year
    val nextM = if (month == 12) 1 else month + 1
    val y = (if (nextM <= 2) nextY - 1 else nextY).toLong()
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val mp = ((nextM + 9) % 12).toLong()
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + (153 * mp + 2) / 5
    val last = era * 146097 + doe - 719468 - 1          // last day of [month]
    val dow = ((last + 4) % 7 + 7) % 7                   // 0 = Sunday (1970-01-01 was a Thursday)
    return last - dow
}

/** UTC offset of Europe/Zurich in seconds (CET/CEST: last Sunday of March/October at 01:00 UTC). */
fun zurichOffset(epochSec: Long): Int {
    val year = yearOf(Math.floorDiv(epochSec, 86400L))
    val start = lastSunday(year, 3) * 86400 + 3600
    val end = lastSunday(year, 10) * 86400 + 3600
    return if (epochSec >= start && epochSec < end) 7200 else 3600
}

fun hhmm(epochMs: Long): String {
    val s = epochMs / 1000
    val t = Math.floorMod(s + zurichOffset(s), 86400L)
    val h = (t / 3600).toInt()
    val m = ((t % 3600) / 60).toInt()
    return "${if (h < 10) "0" else ""}$h:${if (m < 10) "0" else ""}$m"
}

fun ago(seconds: Long): String = when {
    seconds < 60 -> "${seconds} s ago"
    seconds < 3600 -> "${seconds / 60} min ago"
    else -> "${seconds / 3600} h ago"
}

// ------------------------------------------------------------------ state
class StationBoard(val stationId: String) {
    var stationName: String? = null
    var departures: List<Departure> = emptyList()
    var incidents: List<Incident> = emptyList()
    private var twinOffsetMs = 0L               // twin clock - millis(), used until NTP has set the clock
    var lastUpdateMs = 0
    var hasData = false
    var live = false
    var demo = false
    var status = "Connecting"

    val title: String get() = stationName ?: "Station $stationId"

    /** Wall clock: NTP if it was set, else the twin clock of the last update. */
    fun nowMs(): Long {
        val wall = epochMillis()
        return if (wall > 0) wall else twinOffsetMs + millis()
    }

    /** A `screen.txt` body: one item per line, `|`-separated fields (see the top of this file). */
    fun load(text: String) {
        val deps = ArrayList<Departure>()
        val incs = ArrayList<Incident>()
        var start = 0
        while (start < text.length) {
            var end = text.indexOf('\n', start)
            if (end < 0) end = text.length
            val f = fields(text, start, end)
            when (f[0]) {
                "S" -> if (f.size >= 4) {
                    stationName = f[1]
                    val now = f[3].toLongOrNull() ?: 0L
                    if (now > 0) twinOffsetMs = now * 1000 - millis()
                }
                "D" -> if (f.size >= 8) deps.add(Departure(f[3], f[4], f[5], f[1], f[2].toIntOrNull() ?: 0,
                    f[6] == "1", f[7] == "1"))
                "I" -> if (f.size >= 6) incs.add(Incident(f[4], f[5], Severity.of(f[1]), f[2] == "1",
                    f[3].toIntOrNull() ?: 0))
            }
            start = end + 1
        }
        if (incs.size != incidents.size) println("${incs.size} incident(s), ${deps.size} departures at $title")
        departures = deps
        incidents = incs   // already sorted worst first by the server
        touch()
    }

    /** The server confirmed the board is unchanged (304). */
    fun touch() {
        lastUpdateMs = millis()
        hasData = true
    }
}

/** Splits text[start, end) on '|'. */
fun fields(text: String, start: Int, end: Int): ArrayList<String> {
    val out = ArrayList<String>(8)
    var i = start
    while (true) {
        var j = text.indexOf('|', i)
        if (j < 0 || j > end) j = end
        out.add(text.substring(i, j))
        if (j >= end) return out
        i = j + 1
    }
}

// ------------------------------------------------------------------ drawing (320x240)
// Swiss railway board style: red header, white background, grey scale, hairline separators.
const val RED = 0xEB0000
const val CHARCOAL = 0x212121
const val GRANITE = 0x686868
const val METAL = 0x767676
const val SILVER = 0xDCDCDC
const val MILK = 0xF6F6F6
const val WHITE = 0xFFFFFF
const val BLUE = 0x2D327D
const val SCREEN_BG = WHITE

const val HEADER_H = 28
const val BANNER_H = 34
const val COLS_H = 14
const val ROW_H = 28
const val FOOTER_H = 16

fun severityColor(s: Severity) = when (s) {
    Severity.CRITICAL -> RED
    Severity.MAJOR -> 0xF27E00
    Severity.MINOR -> 0xFCBB00
    Severity.INFO -> BLUE
}

fun bannerBg(s: Severity) = when (s) {
    Severity.CRITICAL, Severity.MAJOR -> 0xFDECEC
    Severity.MINOR -> 0xFFF6DB
    Severity.INFO -> MILK
}

fun isLongDistance(category: String): Boolean {
    val c = category.uppercase()
    return c == "IC" || c == "IR" || c == "EC" || c == "ICE" || c == "TGV" || c == "ICN" || c == "RJX" ||
        c == "EN" || c == "NJ"
}

/**
 * Incremental renderer: each zone keeps a key of what it shows and is repainted only when the key changes,
 * so a typical update touches one or two rows instead of the whole panel.
 */
class Renderer(val s: Screen) {
    private var headerKey = ""
    private var bannerKey = ""
    private var footerKey = ""
    private var layoutBanner: Boolean? = null
    private val rowKeys = arrayOfNulls<String>(ROWS_MAX + 2)

    fun render(b: StationBoard, tick: Int) {
        val w = s.width
        val hasBanner = b.incidents.isNotEmpty()
        if (layoutBanner != hasBanner) {          // layout changes: forget everything below the header
            layoutBanner = hasBanner
            bannerKey = ""
            for (i in rowKeys.indices) rowKeys[i] = null
            val top = HEADER_H + (if (hasBanner) BANNER_H else 0)
            s.fillRect(0, top, w, COLS_H, SCREEN_BG)
            s.text(8, top + 4, "Departure", METAL, 1, SCREEN_BG)
            s.text(98, top + 4, "Line", METAL, 1, SCREEN_BG)
            s.text(138, top + 4, "Destination", METAL, 1, SCREEN_BG)
        }

        // header: station + clock (repainted when the minute changes)
        val clock = if (b.hasData || epochMillis() > 0) hhmm(b.nowMs()) else ""
        val hk = "${b.title}|$clock"
        if (hk != headerKey) {
            headerKey = hk
            s.fillRect(0, 0, w, HEADER_H, RED)
            val clockW = if (clock.isEmpty()) 0 else Screen.textWidth(clock, 2) + 12
            s.text(10, 7, Screen.ellipsize(b.title, w - 20 - clockW, 2), WHITE, 2, RED)
            if (clock.isNotEmpty()) s.textRight(w - 10, 7, clock, WHITE, 2, RED)
        }

        // incident banner (rotates every few seconds when there are several)
        if (hasBanner) {
            val n = b.incidents.size
            val inc = b.incidents[tick % n]
            val counter = if (n > 1) "${tick % n + 1}/$n" else ""
            val bk = "${inc.severity}|${inc.title}|${inc.trains}|$counter"
            if (bk != bannerKey) {
                bannerKey = bk
                val y = HEADER_H
                val bg = bannerBg(inc.severity)
                s.fillRect(0, y, w, BANNER_H, bg)
                s.fillRect(0, y, 4, BANNER_H, severityColor(inc.severity))
                val cw = if (counter.isEmpty()) 0 else Screen.textWidth(counter) + 8
                if (counter.isNotEmpty()) s.textRight(w - 8, y + 5, counter, METAL, 1, bg)
                val lines = Screen.wrap(inc.title, w - 18 - cw, 1, 2)
                s.text(12, y + 5, lines[0], CHARCOAL, 1, bg)
                val trains = "${inc.trains} train${if (inc.trains == 1) "" else "s"}"
                val second = when {
                    lines.size > 1 -> lines[1]
                    !inc.atStation -> "Affects $trains calling here"
                    inc.trains > 0 -> "At this station · $trains affected"
                    else -> "At this station"
                }
                s.text(12, y + 19, Screen.ellipsize(second, w - 20), GRANITE, 1, bg)
            }
        }

        // departures, one zone per row
        val top = HEADER_H + (if (hasBanner) BANNER_H else 0) + COLS_H
        val bottom = s.height - FOOTER_H
        val rows = minOf(ROWS_MAX, (bottom - top) / ROW_H)
        if (!b.hasData || b.departures.isEmpty()) {
            val msg = if (!b.hasData) "Loading departures..." else "No departures in the next 2 hours"
            if (rowKeys[0] != "msg:$msg") {
                for (i in rowKeys.indices) rowKeys[i] = null
                rowKeys[0] = "msg:$msg"
                s.fillRect(0, top, w, bottom - top, SCREEN_BG)
                s.textCentered(0, w, top + (bottom - top) / 2 - 4, msg, GRANITE, 1, SCREEN_BG)
            }
        } else {
            if (rowKeys[0]?.startsWith("msg:") == true) {
                rowKeys[0] = null
                s.fillRect(0, top, w, bottom - top, SCREEN_BG)
            }
            for (i in 0 until rows) {
                val d = if (i < b.departures.size) b.departures[i] else null
                val sep = d != null && i < b.departures.size - 1 && i < rows - 1
                val key = if (d == null) "" else
                    "${d.planned}|${d.delayMin}|${d.line}|${d.category}|${d.destination}|${d.cancelled}|$sep"
                if (key != rowKeys[i]) {
                    rowKeys[i] = key
                    val y = top + i * ROW_H
                    s.fillRect(0, y, w, ROW_H, SCREEN_BG)
                    if (d != null) row(d, y, sep)
                }
            }
            val rest = top + rows * ROW_H
            if (rest < bottom && rowKeys[ROWS_MAX + 1] != "pad$rest") {
                rowKeys[ROWS_MAX + 1] = "pad$rest"
                s.fillRect(0, rest, w, bottom - rest, SCREEN_BG)
            }
        }

        // footer
        val source = if (b.demo) "Demo" else if (b.live) "Live" else b.status
        val right = if (!b.hasData) source else "$source · ${ago(((millis() - b.lastUpdateMs) / 1000).toLong())}"
        val fk = "${b.stationId}|$right|${b.live || b.demo}"
        if (fk != footerKey) {
            footerKey = fk
            s.fillRect(0, bottom, w, FOOTER_H, SCREEN_BG)
            s.drawLine(0, bottom, w, bottom, SILVER)
            s.text(8, bottom + 5, "ID ${b.stationId}", METAL, 1, SCREEN_BG)
            s.textRight(w - 8, bottom + 5, right, if (b.live || b.demo) METAL else RED, 1, SCREEN_BG)
        }
    }

    private fun row(d: Departure, y: Int, separator: Boolean) {
        val w = s.width
        val main = if (d.cancelled) METAL else CHARCOAL
        s.text(8, y + 6, d.planned, main, 2, SCREEN_BG)
        if (!d.cancelled && d.delayMin >= 1) s.text(70, y + 10, "+${d.delayMin}'", RED, 1, SCREEN_BG)
        badge(98, y + 6, d.line, d.category)
        val right = w - 8
        var avail = right - 138
        if (d.cancelled) {
            s.textRight(right, y + 10, "Cancelled", RED, 1, SCREEN_BG)
            avail -= Screen.textWidth("Cancelled") + 6
        }
        // big letters when the destination fits, otherwise small ones (complete rather than cut)
        if (Screen.textWidth(d.destination, 2) <= avail) s.text(138, y + 6, d.destination, main, 2, SCREEN_BG)
        else s.text(138, y + 10, Screen.ellipsize(d.destination, avail), main, 1, SCREEN_BG)
        if (separator) s.drawLine(8, y + ROW_H - 1, w, y + ROW_H - 1, SILVER)
    }

    /** Long-distance products filled red, regional ones outlined (timetable pictogram style). */
    private fun badge(x: Int, y: Int, line: String, category: String) {
        val bw = 34
        val bh = 16
        val text = Screen.ellipsize(line, bw - 4)
        if (isLongDistance(category)) {
            s.fillRect(x, y, bw, bh, RED)
            s.textCentered(x, bw, y + 4, text, WHITE, 1, RED)
        } else {
            s.fillRect(x, y, bw, bh, WHITE)
            s.drawRect(x, y, bw, bh, CHARCOAL)
            s.textCentered(x, bw, y + 4, text, CHARCOAL, 1, WHITE)
        }
    }
}

fun splash(s: Screen, line1: String, line2: String) {
    s.clear(SCREEN_BG)
    s.fillRect(0, 0, s.width, HEADER_H, RED)
    s.text(10, 7, "Departures", WHITE, 2, RED)
    s.textCentered(0, s.width, 100, line1, CHARCOAL, 1, SCREEN_BG)
    s.textCentered(0, s.width, 116, line2, METAL, 1, SCREEN_BG)
}

/** The time just set by NTP, large, while the first departures are fetched. */
fun showClock(s: Screen) {
    s.clear(SCREEN_BG)
    s.fillRect(0, 0, s.width, HEADER_H, RED)
    s.text(10, 7, "Departures", WHITE, 2, RED)
    s.textCentered(0, s.width, 90, hhmm(epochMillis()), CHARCOAL, 6, SCREEN_BG)
    s.textCentered(0, s.width, 150, "Swiss time, set by NTP", METAL, 1, SCREEN_BG)
    s.textCentered(0, s.width, 166, "Loading departures...", METAL, 1, SCREEN_BG)
}

// ------------------------------------------------------------------ main
fun main() {
    val screen = createScreen()
    screen.clear(SCREEN_BG)
    val station = Device.config("station") ?: DEFAULT_STATION
    println("device ${Device.id}, station $station")
    val sim = Board.name == "simulator"

    // 1. network and wall clock first
    var online = sim
    if (!sim) {
        splash(screen, "Connecting to WiFi", WIFI_SSID)
        var tries = 0
        while (!online && (tries < 1 || !DEMO)) {   // demo mode: one try, then carry on offline
            online = wifiConnect(WIFI_SSID, WIFI_PASSWORD, if (DEMO) 10000 else 15000)
            tries++
            if (!online) {
                println("WiFi: cannot connect to $WIFI_SSID")
                splash(screen, "WiFi not available", "retrying $WIFI_SSID")
            }
        }
        if (online) println("WiFi OK, IP ${wifiIp()}")
    }
    if (online) {
        splash(screen, "Setting the clock", NTP_SERVER)
        if (ntpSync(NTP_SERVER, 10000)) {
            println("NTP: clock set from $NTP_SERVER, ${hhmm(epochMillis())} Swiss time")
            showClock(screen)
        } else println("NTP: no answer from $NTP_SERVER, using the twin clock")
    }

    if (DEMO) return runDemo(screen, station)

    // 2. twin API
    val base = (Device.config("api") ?: if (API_BASE.isNotEmpty()) API_BASE
        else "http://${if (sim) "127.0.0.1" else MOCK_HOST}:8765").removeSuffix("/")
    val feed = ConditionalGet("$base/api/v1/stations/$station/screen.txt?limit=$ROWS_MAX&incidents=$INCIDENTS_MAX")
    println("API ${feed.url}")

    val board = StationBoard(station)
    val renderer = Renderer(screen)
    var nextPollMs = 0
    var nextTickMs = millis() + 6000
    var nextRenderMs = 0
    var tick = 0
    var dirty = true

    while (true) {
        if (millis() >= nextPollMs) {
            val body = feed.poll(8000)
            val ok = body != null || feed.status == 304
            if (body != null) board.load(body)
            else if (ok) board.touch()
            else println("API not reachable (HTTP ${feed.status})")
            if (ok != board.live) println(if (ok) "API online" else "API offline")
            board.live = ok
            board.status = if (ok) "Live" else "Offline"
            nextPollMs = millis() + (if (ok) POLL_SEC else RETRY_SEC) * 1000
            dirty = true
        }
        val t = millis()
        if (t >= nextTickMs) { tick++; nextTickMs = t + 6000; dirty = true }
        if (dirty || t >= nextRenderMs) {
            renderer.render(board, tick)
            dirty = false
            nextRenderMs = t + 1000
        }
        delay(100)
    }
}
