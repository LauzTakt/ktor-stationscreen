// Offline mode: no API. The board builds the same `screen.txt` documents the twin API sends
// (docs/API.md "Station screen, text form") from a clock-face timetable and a scripted sequence of incidents, and feeds them
// through the same parsing, enrichment and drawing code.
package trains

import esp32.screen.Screen

private const val T0 = 1795294228000L   // fake clock when NTP is not available (epoch ms)

private val STATION_NAMES = mapOf(
    "8501120" to "Lausanne", "8501008" to "Genève", "8501026" to "Genève-Aéroport", "8501118" to "Renens VD",
    "8501037" to "Morges", "8504200" to "Yverdon-les-Bains", "8504100" to "Fribourg/Freiburg", "8507000" to "Bern",
    "8505000" to "Luzern", "8504221" to "Neuchâtel", "8504300" to "Biel/Bienne", "8501200" to "Vevey",
    "8501300" to "Montreux", "8503000" to "Zürich HB",
)

/** Clock-face timetable: departures every hour at these minutes. */
private class Service(val minute: Int, val line: String, val category: String, val destination: String)

private val TIMETABLE = listOf(
    Service(2, "IR15", "IR", "Luzern"), Service(6, "S1", "S", "Villeneuve"), Service(9, "RE33", "RE", "St-Maurice"),
    Service(15, "IC1", "IC", "St. Gallen"), Service(20, "IC5", "IC", "Zürich HB"),
    Service(24, "S1", "S", "Yverdon-les-Bains"), Service(32, "IR90", "IR", "Brig"), Service(36, "S1", "S", "Villeneuve"),
    Service(39, "RE33", "RE", "Annemasse"), Service(45, "IC1", "IC", "Genève-Aéroport"),
    Service(50, "IC5", "IC", "Genève-Aéroport"), Service(54, "S1", "S", "Yverdon-les-Bains"),
)

/** An active demo incident and its effect on one line (extra delay or cancellation). */
private class DemoIncident(val id: String, val severity: String, val title: String, val where: String,
                           val atStation: Boolean, val trains: Int, val line: String?, val delaySec: Int,
                           val cancels: Boolean)

private class DemoApi(val stationId: String) {
    val incidents = LinkedHashMap<String, DemoIncident>()
    var nowMs = T0

    fun screen(limit: Int): String {
        val sb = StringBuilder()
        sb.append("S|").append(STATION_NAMES[stationId] ?: "Station $stationId").append('|').append(hhmm(nowMs))
            .append('|').append(nowMs / 1000).append('\n')
        val hourMs = 3600000L
        var hour = (nowMs / hourMs) * hourMs - hourMs
        var n = 0
        while (n < limit && hour < nowMs + 3 * hourMs) {
            for (sv in TIMETABLE) {
                if (n >= limit) break
                val planned = hour + sv.minute * 60000L
                var delay = 0
                var cancelled = false
                for (inc in incidents.values) if (inc.line == sv.line) {
                    delay = maxOf(delay, inc.delaySec)
                    cancelled = cancelled || inc.cancels
                }
                if (planned + delay * 1000L < nowMs - 60000) continue
                sb.append("D|").append(hhmm(planned)).append('|').append(delay / 60).append('|').append(sv.line)
                    .append('|').append(sv.category).append('|').append(sv.destination)
                    .append(if (cancelled) "|1|0\n" else "|0|0\n")
                n++
            }
            hour += hourMs
        }
        for (inc in incidents.values.sortedBy { rank(it.severity) }) {
            sb.append("I|").append(inc.severity).append(if (inc.atStation) "|1|" else "|0|").append(inc.trains)
                .append('|').append(inc.title).append('|').append(inc.where).append('\n')
        }
        return sb.toString()
    }

    private fun rank(s: String) = when (s) { "critical" -> 0; "major" -> 1; "minor" -> 2; else -> 3 }
}

private val IC1_FAULT = DemoIncident("i-demo-ic1", "major", "Overhead line fault: Lausanne – Fribourg/Freiburg",
    "Lausanne – Fribourg/Freiburg", true, 3, "IC1", 20 * 60, false)

/** Scripted changes, one every few seconds, in a loop: (seconds to wait, change). */
private class Step(val afterSec: Int, val run: (DemoApi) -> Unit)

private val SCRIPT = listOf(
    Step(8) { it.incidents["i-demo-ic5"] = DemoIncident("i-demo-ic5", "critical",
        "Signal box failure: Neuchâtel – Biel/Bienne", "Neuchâtel – Biel/Bienne", false, 4, "IC5", 0, true) },
    Step(12) { it.incidents["i-demo-s1"] = DemoIncident("i-demo-s1", "minor",
        "Delays on line S1: late running of a previous train", "S1", false, 6, "S1", 240, false) },
    Step(14) { it.incidents["i-demo-ic5"] = DemoIncident("i-demo-ic5", "major",
        "Single-track operation: Neuchâtel – Biel/Bienne", "Neuchâtel – Biel/Bienne", false, 4, "IC5", 25 * 60, false) },
    Step(12) { it.incidents["i-demo-lift"] = DemoIncident("i-demo-lift", "info", "Lift out of service on platform 5",
        "", true, 0, null, 0, false) },
    Step(14) { it.incidents.remove("i-demo-ic1") },
    Step(12) { it.incidents.remove("i-demo-ic5") },
    Step(12) { it.incidents.remove("i-demo-s1") },
    Step(10) { it.incidents.remove("i-demo-lift") },
    Step(10) { it.incidents[IC1_FAULT.id] = IC1_FAULT },
)

fun runDemo(screen: Screen, stationId: String) {
    val api = DemoApi(stationId)
    api.incidents[IC1_FAULT.id] = IC1_FAULT
    val board = StationBoard(stationId)
    board.demo = true
    val renderer = Renderer(screen)
    val start = esp32.millis()
    val clockStart = if (esp32.epochMillis() > 0) esp32.epochMillis() else T0
    api.nowMs = clockStart
    board.load(api.screen(ROWS_MAX))
    println("demo: ${board.departures.size} departures at ${board.title} (no API)")

    var step = 0
    var nextStepMs = start + SCRIPT[0].afterSec * 1000
    var nextUpdateMs = start + POLL_SEC * 1000
    var nextTickMs = start + 6000
    var nextRenderMs = 0
    var tick = 0
    var dirty = true
    while (true) {
        val now = esp32.millis()
        api.nowMs = clockStart + (now - start)
        var update = now >= nextUpdateMs
        if (now >= nextStepMs) {         // incident change: pushed immediately, like the screen topic does
            SCRIPT[step].run(api)
            step = (step + 1) % SCRIPT.size
            nextStepMs = now + SCRIPT[step].afterSec * 1000
            update = true
        }
        if (update) {
            board.load(api.screen(ROWS_MAX))
            nextUpdateMs = now + POLL_SEC * 1000
            dirty = true
        }
        if (now >= nextTickMs) { tick++; nextTickMs = now + 6000; dirty = true }
        if (dirty || now >= nextRenderMs) {
            renderer.render(board, tick)
            dirty = false
            nextRenderMs = now + 1000
        }
        esp32.delay(100)
    }
}
