import esp32.*

// The same program runs on an ESP32 and on a Raspberry Pi:
// Board tells which board we are on and which GPIO drives the LED.
data class Reading(val n: Int, val temp: Float, val uptimeMs: Int)

fun main() {
    println("Hello from ${Board.name}! LED on GPIO${Board.ledPin}")
    val led = Led(Board.ledPin)
    val readings = mutableListOf<Reading>()
    for (n in 1..10) {
        led.toggle()
        val r = Reading(n, chipTemperature(), millis())
        readings += r
        println("#${r.n} temp=%.1f°C t=%d ms led=%s".format(r.temp, r.uptimeMs, if (led.isOn) "ON" else "OFF"))
        delay(if (Board.isEsp32) 300 else 500)
    }
    led.off()
    val avg = readings.map { it.temp.toDouble() }.average()
    println("average ${"%.2f".format(avg)}°C over ${readings.size} readings (${Board.name})")
}
