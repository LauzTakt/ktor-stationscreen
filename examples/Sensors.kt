import esp32.*

// Reads the ADC (GPIO34), the T0 touch sensor (GPIO4) and the internal temperature,
// and computes a moving average with a Kotlin deque.
class MovingAverage(private val size: Int) {
    private val values = ArrayDeque<Int>()
    fun add(v: Int): Double {
        values.addLast(v)
        if (values.size > size) values.removeFirst()
        return values.average()
    }
}

fun main() {
    val avg = MovingAverage(8)
    val led = Led(LED_BUILTIN)
    for (i in 1..20) {
        val raw = analogRead(34)
        val touch = touchRead(4)
        led.set(touch < 30)  // touch GPIO4 to switch the LED on
        println("adc=%4d avg=%7.1f touch=%3d temp=%.1fC free=%d".format(raw, avg.add(raw), touch, chipTemperature(), freeMemory()))
        delay(250)
    }
    println("done")
}
