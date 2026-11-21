import esp32.*

// Blinks the built-in blue LED (GPIO2) and prints to the serial port.
fun main() {
    val led = Led(LED_BUILTIN)
    var count = 0
    loop(periodMs = 500) {
        led.toggle()
        count++
        println("blink #$count -> ${if (led.isOn) "ON" else "OFF"}")
    }
}
