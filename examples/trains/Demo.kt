// Offline mode: sample disruptions and a scripted sequence of live events, all inside the board.
// They go through the same JSON parsing and enrichment as real API messages.
package trains

import esp32.screen.Screen

private const val T0 = 1763750000L   // fake server clock (epoch seconds)

private fun inc(id: String, line: String, from: String, to: String, station: String, cause: String,
                severity: String, delay: Int, minutesAgo: Int, message: String) =
    """{"id":"$id","line":"$line","from":"$from","to":"$to","station":"$station","cause":"$cause",""" +
        """"severity":"$severity","delayMin":$delay,"since":${T0 - minutesAgo * 60},"message":"$message"}"""

private val SNAPSHOT = """{"now":$T0,"incidents":[""" + listOf(
    inc("INC-1041", "IC1", "Genève-Aéroport", "St. Gallen", "Lausanne", "Overhead line fault", "high", 20, 34,
        "Single-track operation between Lausanne and Fribourg"),
    inc("INC-1042", "S1", "Yverdon-les-Bains", "Villeneuve", "Renens VD", "Defective train", "medium", 10, 12,
        "Some trains do not stop at Renens VD"),
    inc("INC-1043", "IR15", "Genève", "Luzern", "Bern", "Planned engineering works", "low", 5, 95,
        "Modified timetable between Bern and Luzern"),
).joinToString(",") + "]}"

/** Events replayed in a loop: (seconds after the previous one, WebSocket message). */
private val SCRIPT = listOf(
    8 to """{"type":"upsert","incident":${inc("INC-1044", "IC5", "Lausanne", "Zürich HB", "Neuchâtel",
        "Signal box failure", "critical", 0, 0, "No service between Neuchâtel and Biel/Bienne. Replacement buses.")}}""",
    10 to """{"type":"upsert","incident":${inc("INC-1042", "S1", "Yverdon-les-Bains", "Villeneuve", "Renens VD",
        "Defective train", "high", 25, 12, "Reduced frequency between Morges and Lausanne")}}""",
    10 to """{"type":"resolved","id":"INC-1043"}""",
    12 to """{"type":"upsert","incident":${inc("INC-1045", "RE33", "Annemasse", "St-Maurice", "Montreux",
        "Rolling stock shortage", "medium", 15, 0, "Shorter trains, high occupancy expected")}}""",
    12 to """{"type":"upsert","incident":${inc("INC-1044", "IC5", "Lausanne", "Zürich HB", "Neuchâtel",
        "Signal box failure", "high", 30, 2, "Service resumed on one track")}}""",
    10 to """{"type":"resolved","id":"INC-1044"}""",
    10 to """{"type":"resolved","id":"INC-1045"}""",
    10 to """{"type":"upsert","incident":${inc("INC-1043", "IR15", "Genève", "Luzern", "Bern",
        "Planned engineering works", "low", 5, 95, "Modified timetable between Bern and Luzern")}}""",
    6 to """{"type":"upsert","incident":${inc("INC-1042", "S1", "Yverdon-les-Bains", "Villeneuve", "Renens VD",
        "Defective train", "medium", 10, 12, "Some trains do not stop at Renens VD")}}""",
)

fun runDemo(screen: Screen) {
    val panel = Panel()
    panel.load(SNAPSHOT)
    panel.live = true
    panel.demo = true
    println("demo: ${panel.incidents.size} sample disruptions (no network)")
    var step = 0
    var nextEventMs = esp32.millis() + SCRIPT[0].first * 1000
    var page = 0
    var nextPageMs = esp32.millis() + 6000
    var nextRenderMs = 0
    var dirty = true
    while (true) {
        val now = esp32.millis()
        if (now >= nextEventMs) {
            if (panel.apply(SCRIPT[step].second)) dirty = true
            step = (step + 1) % SCRIPT.size
            nextEventMs = now + SCRIPT[step].first * 1000
        }
        if (now >= nextPageMs) { page++; nextPageMs = now + 6000; dirty = true }
        if (dirty || now >= nextRenderMs) {
            render(screen, panel, page)
            dirty = false
            nextRenderMs = now + 5000
        }
        esp32.delay(100)
    }
}
