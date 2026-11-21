// Train disruption board for a 320x240 TFT, running in Kotlin on the ESP32.
//
// Each board serves one station: its station ID is a device setting (`./ktesp config station=8501120`),
// so every screen runs the same program image.
//
//   1. GET /api/incidents?station=ID   -> initial state + station name
//   2. WS  /ws/incidents?station=ID    -> live changes (upsert / resolved)
//   3. enrichment on the board: keep only disruptions affecting this station, severity -> colour,
//      relative times, worst delay, sorting by severity
//   4. drawing on an ILI9341 TFT: a real one over SPI, or the one emulated on the Mac (./ktesp screen examples/trains)
package trains

import esp32.*
import esp32.json.*
import esp32.net.*
import esp32.screen.*

// ------------------------------------------------------------------ model
enum class Severity(val label: String, val rank: Int) {
    CRITICAL("critical", 0),
    HIGH("high", 1),
    MEDIUM("medium", 2),
    LOW("low", 3);

    companion object {
        fun of(s: String) = when (s) {
            "critical" -> CRITICAL
            "high" -> HIGH
            "medium" -> MEDIUM
            else -> LOW
        }
    }
}

class Incident(
    val id: String, val line: String, val from: String, val to: String, val station: String,
    val cause: String, val severity: Severity, val delayMin: Int, val since: Long, val message: String,
    /** IDs of the stations affected by this disruption. */
    val stations: List<String>,
)

fun parseIncident(o: JsonObject) = Incident(
    id = o.str("id"), line = o.str("line", "?"), from = o.str("from"), to = o.str("to"),
    station = o.str("station"), cause = o.str("cause"), severity = Severity.of(o.str("severity")),
    delayMin = o.int("delayMin"), since = o.long("since"), message = o.str("message"),
    stations = o.arr("stations").list.mapNotNull { it as? String },
)

// ------------------------------------------------------------------ enrichment
fun ago(seconds: Long): String = when {
    seconds < 60 -> "just now"
    seconds < 3600 -> "${seconds / 60} min ago"
    else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min ago"
}

/** Central European time (no daylight saving) from a Unix epoch. */
fun clock(epoch: Long): String {
    val t = ((epoch + 3600) % 86400 + 86400) % 86400
    val h = (t / 3600).toInt()
    val m = ((t % 3600) / 60).toInt()
    return "${if (h < 10) "0" else ""}$h:${if (m < 10) "0" else ""}$m"
}

// ------------------------------------------------------------------ state
/** State of the board for one station ([stationId] null = show the whole network). */
class Panel(val stationId: String?) {
    var stationName: String? = null
    val incidents = LinkedHashMap<String, Incident>()
    val firstSeen = HashMap<String, Int>()        // millis() when an incident appeared / changed (highlight)
    private var clockOffset = 0L                   // server epoch - local seconds
    var lastUpdateMs = 0
    var hasData = false
    var live = false
    var demo = false
    var status = "Connecting"

    /** Enrichment step 1: does this disruption concern our station? */
    fun affects(inc: Incident) = stationId == null || inc.stations.contains(stationId)

    val title: String get() = stationName ?: if (stationId != null) "Station $stationId" else "Disruptions"

    fun now(): Long = clockOffset + millis() / 1000

    fun syncClock(serverNow: Long) {
        if (serverNow > 0) clockOffset = serverNow - millis() / 1000
    }

    fun load(json: String) {
        val o = Json.parseObject(json)
        syncClock(o.long("now"))
        o.obj("station")?.strOrNull("name")?.let { stationName = it }
        incidents.clear()
        for (it in o.arr("incidents").objects()) {
            val inc = parseIncident(it)
            if (affects(inc)) incidents[inc.id] = inc
        }
        lastUpdateMs = millis()
        hasData = true
    }

    /** Applies a WebSocket event. Returns true if something changed. */
    fun apply(json: String): Boolean {
        val o = Json.parseObject(json)
        syncClock(o.long("now"))
        lastUpdateMs = millis()
        when (o.str("type")) {
            "upsert" -> {
                val inc = parseIncident(o.obj("incident") ?: return false)
                if (!affects(inc)) {   // not (or no longer) relevant for this station
                    firstSeen.remove(inc.id)
                    return incidents.remove(inc.id) != null
                }
                val isNew = !incidents.containsKey(inc.id)
                incidents[inc.id] = inc
                firstSeen[inc.id] = millis()
                println(if (isNew) "+ ${inc.id} ${inc.line} ${inc.cause}" else "~ ${inc.id} ${inc.severity.label} +${inc.delayMin} min")
                return true
            }
            "resolved" -> {
                val id = o.str("id")
                firstSeen.remove(id)
                if (incidents.remove(id) != null) { println("- $id resolved"); return true }
            }
        }
        return false
    }

    fun sorted(): List<Incident> =
        incidents.values.sortedWith(Comparator { a, b ->
            when {
                a.severity.rank != b.severity.rank -> a.severity.rank - b.severity.rank
                a.delayMin != b.delayMin -> b.delayMin - a.delayMin
                else -> b.since.compareTo(a.since)
            }
        })
}

// ------------------------------------------------------------------ drawing (320x240)
// Swiss railway board style: red header, white background, grey scale, hairline separators.
// No full-screen clears: a real TFT shows every write immediately, so each zone repaints its own background.
const val RED = 0xEB0000          // brand red
const val CHARCOAL = 0x212121     // primary text
const val GRANITE = 0x686868      // secondary text
const val METAL = 0x767676        // tertiary text
const val SILVER = 0xDCDCDC       // separators
const val MILK = 0xF6F6F6         // bands and subtle highlight
const val WHITE = 0xFFFFFF
const val BLUE = 0x2D327D

const val HEADER_H = 28
const val BAND_H = 16
const val ROW_H = 60
const val ROWS_PER_PAGE = 3
const val FOOTER_H = 16
const val SCREEN_BG = WHITE

fun severityColor(s: Severity) = when (s) {
    Severity.CRITICAL -> RED
    Severity.HIGH -> 0xF27E00
    Severity.MEDIUM -> 0xFCBB00
    Severity.LOW -> BLUE
}

fun render(s: Screen, p: Panel, page: Int) {
    val list = p.sorted()
    val pages = maxOf(1, (list.size + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE)
    val pg = page % pages
    val w = s.width

    // red header: title and clock
    s.fillRect(0, 0, w, HEADER_H, RED)
    val clockW = if (p.hasData) Screen.textWidth("00:00", 2) + 12 else 0
    s.text(10, 7, Screen.ellipsize(p.title, w - 20 - clockW, 2), WHITE, 2, RED)
    if (p.hasData) s.textRight(w - 10, 7, clock(p.now()), WHITE, 2, RED)

    // summary band
    val by = HEADER_H
    s.fillRect(0, by, w, BAND_H, MILK)
    if (list.isEmpty()) {
        s.text(10, by + 4, "Normal service", GRANITE, 1, MILK)
    } else {
        val cut = list.count { it.severity == Severity.CRITICAL }
        var summary = "${list.size} disruption${if (list.size == 1) "" else "s"}"
        if (cut > 0) summary += " · $cut cancelled"
        s.text(10, by + 4, summary, CHARCOAL, 1, MILK)
        val maxDelay = list.maxOf { it.delayMin }
        if (maxDelay > 0) s.textRight(w - 10, by + 4, "max. +$maxDelay'", RED, 1, MILK)
    }

    // rows
    val top = HEADER_H + BAND_H
    if (list.isEmpty()) {
        s.fillRect(0, top, w, s.height - top - FOOTER_H, SCREEN_BG)
        s.textCentered(0, w, 96, "No disruptions", CHARCOAL, 2, SCREEN_BG)
        s.textCentered(0, w, 122, if (p.stationId != null) "All trains at this station are running normally"
            else "All lines are running normally", GRANITE, 1, SCREEN_BG)
    } else {
        val start = pg * ROWS_PER_PAGE
        for (slot in 0 until ROWS_PER_PAGE) {
            val y = top + slot * ROW_H
            val i = start + slot
            if (i < list.size) row(s, list[i], p, y, slot < ROWS_PER_PAGE - 1 && i + 1 < list.size)
            else s.fillRect(0, y, w, ROW_H, SCREEN_BG)
        }
    }

    // footer
    val fy = s.height - FOOTER_H
    s.fillRect(0, fy, w, FOOTER_H, SCREEN_BG)
    s.drawLine(0, fy, w, fy, SILVER)
    var left = if (p.stationId != null) "ID ${p.stationId}" else "All stations"
    if (pages > 1) left += " · ${pg + 1}/$pages"
    s.text(10, fy + 5, left, METAL, 1, SCREEN_BG)
    val source = if (p.demo) "Demo" else if (p.live) "Live" else p.status
    val upd = (millis() - p.lastUpdateMs) / 1000
    val right = if (!p.hasData) source else "$source · ${ago(upd.toLong())}"
    s.textRight(w - 10, fy + 5, right, if (p.live || p.demo) METAL else RED, 1, SCREEN_BG)
}

/** Line pictogram: long-distance products filled red, regional ones outlined (timetable style). */
fun badge(s: Screen, x: Int, y: Int, line: String) {
    val bw = 34
    val bh = 18
    val longDistance = line.startsWith("IC") || line.startsWith("IR") || line.startsWith("EC") || line.startsWith("TGV")
    if (longDistance) {
        s.fillRect(x, y, bw, bh, RED)
        s.textCentered(x, bw, y + 5, line, WHITE, 1, RED)
    } else {
        s.fillRect(x, y, bw, bh, WHITE)
        s.drawRect(x, y, bw, bh, CHARCOAL)
        s.textCentered(x, bw, y + 5, line, CHARCOAL, 1, WHITE)
    }
}

fun row(s: Screen, inc: Incident, p: Panel, y: Int, separator: Boolean) {
    val w = s.width
    val fresh = p.firstSeen[inc.id]?.let { millis() - it < 8000 } ?: false
    val bg = if (fresh) MILK else SCREEN_BG
    s.fillRect(0, y, w, ROW_H, bg)
    if (fresh) s.fillRect(0, y, 3, ROW_H, RED)       // discreet "new" mark

    badge(s, 10, y + 8, inc.line)

    val tx = 54
    val right = w - 10
    // delay / status, right-aligned like on a departure board
    val delay = when {
        inc.severity == Severity.CRITICAL -> "Cancelled"
        inc.delayMin > 0 -> "+${inc.delayMin}'"
        else -> ""
    }
    val dw = if (delay.isEmpty()) 0 else Screen.textWidth(delay) + 8
    if (delay.isNotEmpty()) s.textRight(right, y + 9, delay, RED, 1, bg)

    s.text(tx, y + 9, Screen.ellipsize("${inc.from} → ${inc.to}", right - tx - dw), CHARCOAL, 1, bg)

    // severity: coloured dot + cause
    s.fillCircle(tx + 2, y + 25, 2, severityColor(inc.severity))
    s.text(tx + 9, y + 22, Screen.ellipsize(inc.cause, right - tx - 9), CHARCOAL, 1, bg)
    s.text(tx, y + 34, Screen.ellipsize(inc.message, right - tx), GRANITE, 1, bg)
    s.text(tx, y + 46, Screen.ellipsize("${inc.station} · ${ago(p.now() - inc.since)}", right - tx), METAL, 1, bg)

    if (separator) s.drawLine(tx, y + ROW_H - 1, w, y + ROW_H - 1, SILVER)
}

fun splash(s: Screen, line1: String, line2: String) {
    s.clear(SCREEN_BG)
    s.fillRect(0, 0, s.width, HEADER_H, RED)
    s.text(10, 7, "Disruptions", WHITE, 2, RED)
    s.textCentered(0, s.width, 106, line1, CHARCOAL, 1, SCREEN_BG)
    s.textCentered(0, s.width, 122, line2, METAL, 1, SCREEN_BG)
}

// ------------------------------------------------------------------ main
fun main() {
    val screen = createScreen()
    screen.clear(SCREEN_BG)
    val station = Device.config("station")
    println("device ${Device.id}, station ${station ?: "(not set: showing all stations)"}")
    if (DEMO) return runDemo(screen, station)
    val sim = Board.name == "simulator"
    val host = if (sim) "127.0.0.1" else API_HOST
    val base = "http://$host:$API_PORT"

    if (!sim) {
        splash(screen, "Connecting to WiFi", WIFI_SSID)
        while (!wifiConnect(WIFI_SSID, WIFI_PASSWORD, 20000)) {
            println("WiFi: cannot connect, retrying...")
            splash(screen, "WiFi not available", "retrying $WIFI_SSID")
        }
        println("WiFi OK, IP ${wifiIp()}")
    }

    val query = if (station != null) "?station=$station" else ""
    val panel = Panel(station)
    val ws = WebSocket("ws://$host:$API_PORT/ws/incidents$query")
    var page = 0
    var nextPageMs = millis() + 6000
    var nextRenderMs = 0
    var nextRetryMs = 0
    var dirty = true

    while (true) {
        if (!ws.isOpen && millis() >= nextRetryMs) {
            panel.live = false
            panel.status = "Connecting"
            // WebSocket first, then the snapshot: no event is lost in between
            if (ws.connect(5000)) {
                val body = Http.getOrNull("$base/api/incidents$query")
                if (body != null) {
                    panel.load(body)
                    panel.live = true
                    println("connected: ${panel.incidents.size} disruptions for ${panel.title}")
                } else {
                    ws.close()
                }
            }
            if (!panel.live) {
                panel.status = "Offline"
                println("API not reachable ($base), retrying in 3 s")
                nextRetryMs = millis() + 3000
            }
            dirty = true
        }

        val msg = ws.receive(200)
        if (msg != null) {
            try {
                if (panel.apply(msg)) dirty = true
            } catch (e: JsonException) {
                println("ignored message: ${e.message}")
            }
        }

        val now = millis()
        if (now >= nextPageMs) { page++; nextPageMs = now + 6000; dirty = true }
        if (dirty || now >= nextRenderMs) {
            render(screen, panel, page)
            dirty = false
            nextRenderMs = now + 5000
        }
    }
}
