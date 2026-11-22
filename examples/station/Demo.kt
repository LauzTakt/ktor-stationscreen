// Offline mode: sample departures and disruptions in the shape of ktor_app's `Screen` JSON, anchored to the board's own
// clock so the "next departures" never go stale. They go through the same parsing as a real response.
package station

import esp32.screen.Screen

private fun dep(plannedMs: Long, delaySec: Int, line: String, to: String, cancelled: Boolean = false, atStation: Boolean = false) =
    """{"plannedTime":$plannedMs,"delaySec":$delaySec,"line":"$line","category":null,"to":"$to",""" +
        """"cancelled":$cancelled,"atStation":$atStation}"""

private fun inc(id: String, severity: String, title: String, trains: Int, atStation: Boolean) =
    """{"id":"$id","severity":"$severity","title":"$title","where":null,"atStation":$atStation,"trains":$trains,"updatedAt":0}"""

/** Same shape as `GET /api/v1/stations/{stopId}/screen`; [nowMs] is the board's clock. */
private fun snapshot(nowMs: Long, withIncidents: Boolean): String {
    val m = 60_000L
    val deps = listOf(
        dep(nowMs - 1 * m, 0, "IC1", "Genève-Aéroport", atStation = true),
        dep(nowMs + 3 * m, 420, "IC5", "Zürich HB"),
        dep(nowMs + 6 * m, 0, "S1", "Yverdon-les-Bains"),
        dep(nowMs + 9 * m, 0, "IR15", "Luzern", cancelled = true),
        dep(nowMs + 14 * m, 180, "RE33", "St-Maurice"),
        dep(nowMs + 19 * m, 0, "S2", "Vallorbe"),
    ).joinToString(",")
    val incs = if (withIncidents) listOf(
        inc("a", "major", "Limited service: Neuchâtel - Biel/Bienne", 2, false),
        inc("b", "minor", "Interrupted service: Morges - Allaman", 1, false),
    ).joinToString(",") else ""
    return """{"stopId":"8501120","name":"Lausanne","now":$nowMs,"departures":[$deps],"incidents":[$incs]}"""
}

fun runDemo(screen: Screen, board: Panel) {
    board.demo = true
    board.live = true
    println("demo: sample departures (no network)")
    val start = 1763750400000L     // a fake server clock, ms
    var page = 0
    var nextPageMs = esp32.millis() + 6000
    var nextLoadMs = 0
    var nextRenderMs = 0
    while (true) {
        val now = esp32.millis()
        if (now >= nextLoadMs) {
            val serverNow = start + now.toLong()
            // the first 20 s show the disruptions, then 20 s calm, so both footers can be seen
            board.load(snapshot(serverNow, (now / 20000) % 2 == 0))
            nextLoadMs = now + 5000
            nextRenderMs = 0
        }
        if (now >= nextPageMs) { page++; nextPageMs = now + 6000; nextRenderMs = 0 }
        if (now >= nextRenderMs) {
            render(screen, board, page)
            nextRenderMs = now + 1000
        }
        esp32.delay(100)
    }
}
