// Offline mode: sample disruptions and a scripted sequence of live events, all inside the board.
// They go through the same JSON parsing and enrichment as real API messages.
package trains

import esp32.screen.Screen

private const val T0 = 1763750000L   // fake server clock (epoch seconds)

// Station IDs (UIC-style numbers) used by the demo.
private const val LAUSANNE = "8501120"
private const val GENEVE = "8501008"
private const val GENEVE_AEROPORT = "8501026"
private const val RENENS = "8501118"
private const val MORGES = "8501037"
private const val YVERDON = "8504200"
private const val FRIBOURG = "8504100"
private const val BERN = "8507000"
private const val LUZERN = "8505000"
private const val NEUCHATEL = "8504221"
private const val BIEL = "8504300"
private const val VEVEY = "8501200"
private const val MONTREUX = "8501300"

private val STATION_NAMES = mapOf(
    LAUSANNE to "Lausanne", GENEVE to "Genève", GENEVE_AEROPORT to "Genève-Aéroport", RENENS to "Renens VD",
    MORGES to "Morges", YVERDON to "Yverdon-les-Bains", FRIBOURG to "Fribourg", BERN to "Bern", LUZERN to "Luzern",
    NEUCHATEL to "Neuchâtel", BIEL to "Biel/Bienne", VEVEY to "Vevey", MONTREUX to "Montreux",
)

private fun inc(id: String, line: String, from: String, to: String, station: String, cause: String,
                severity: String, delay: Int, minutesAgo: Int, message: String, vararg stations: String) =
    """{"id":"$id","line":"$line","from":"$from","to":"$to","station":"$station","cause":"$cause",""" +
        """"severity":"$severity","delayMin":$delay,"since":${T0 - minutesAgo * 60},"message":"$message",""" +
        """"stations":[${stations.joinToString(",") { "\"$it\"" }}]}"""

private val IC1 = arrayOf(GENEVE_AEROPORT, GENEVE, LAUSANNE, FRIBOURG, BERN)
private val S1 = arrayOf(YVERDON, MORGES, RENENS, LAUSANNE, VEVEY, MONTREUX)
private val IR15 = arrayOf(GENEVE, LAUSANNE, FRIBOURG, BERN, LUZERN)
private val IC5 = arrayOf(LAUSANNE, YVERDON, NEUCHATEL, BIEL)
private val RE33 = arrayOf(GENEVE, MORGES, LAUSANNE, VEVEY, MONTREUX)

private val SNAPSHOT = """{"now":$T0,"incidents":[""" + listOf(
    inc("INC-1041", "IC1", "Genève-Aéroport", "St. Gallen", "Lausanne", "Overhead line fault", "high", 20, 34,
        "Single-track operation between Lausanne and Fribourg", *IC1),
    inc("INC-1042", "S1", "Yverdon-les-Bains", "Villeneuve", "Renens VD", "Defective train", "medium", 10, 12,
        "Some trains do not stop at Renens VD", *S1),
    inc("INC-1043", "IR15", "Genève", "Luzern", "Bern", "Planned engineering works", "low", 5, 95,
        "Modified timetable between Bern and Luzern", *IR15),
).joinToString(",") + "]}"

/** Events replayed in a loop: (seconds after the previous one, WebSocket message). */
private val SCRIPT = listOf(
    8 to """{"type":"upsert","incident":${inc("INC-1044", "IC5", "Lausanne", "Zürich HB", "Neuchâtel",
        "Signal box failure", "critical", 0, 0, "No service between Neuchâtel and Biel/Bienne. Replacement buses.", *IC5)}}""",
    10 to """{"type":"upsert","incident":${inc("INC-1042", "S1", "Yverdon-les-Bains", "Villeneuve", "Renens VD",
        "Defective train", "high", 25, 12, "Reduced frequency between Morges and Lausanne", *S1)}}""",
    10 to """{"type":"resolved","id":"INC-1043"}""",
    12 to """{"type":"upsert","incident":${inc("INC-1045", "RE33", "Annemasse", "St-Maurice", "Montreux",
        "Rolling stock shortage", "medium", 15, 0, "Shorter trains, high occupancy expected", *RE33)}}""",
    12 to """{"type":"upsert","incident":${inc("INC-1044", "IC5", "Lausanne", "Zürich HB", "Neuchâtel",
        "Signal box failure", "high", 30, 2, "Service resumed on one track", *IC5)}}""",
    10 to """{"type":"resolved","id":"INC-1044"}""",
    10 to """{"type":"resolved","id":"INC-1045"}""",
    10 to """{"type":"upsert","incident":${inc("INC-1043", "IR15", "Genève", "Luzern", "Bern",
        "Planned engineering works", "low", 5, 95, "Modified timetable between Bern and Luzern", *IR15)}}""",
    6 to """{"type":"upsert","incident":${inc("INC-1042", "S1", "Yverdon-les-Bains", "Villeneuve", "Renens VD",
        "Defective train", "medium", 10, 12, "Some trains do not stop at Renens VD", *S1)}}""",
)

fun runDemo(screen: Screen, stationId: String?) {
    val panel = Panel(stationId)
    panel.stationName = stationId?.let { STATION_NAMES[it] }
    panel.load(SNAPSHOT)
    panel.live = true
    panel.demo = true
    println("demo: ${panel.incidents.size} sample disruptions for ${panel.title} (no network)")
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
