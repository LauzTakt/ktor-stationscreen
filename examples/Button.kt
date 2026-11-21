import esp32.*

// Press the BOOT button to change the LED pattern.
enum class Mode(val periodMs: Int) { SLOW(600), FAST(120), SOS(0) }

fun sos(led: Led) {
    for (d in listOf(150, 150, 150, 450, 450, 450, 150, 150, 150)) {
        led.on(); delay(d); led.off(); delay(150)
    }
    delay(600)
}

fun main() {
    val led = Led(LED_BUILTIN)
    val button = Button(BOOT_BUTTON)
    var mode = Mode.SLOW
    var wasPressed = false
    var last = millis()
    println("Press BOOT to change mode (${Mode.entries.joinToString()})")
    loop(periodMs = 10) {
        val pressed = button.isPressed
        if (pressed && !wasPressed) {
            mode = Mode.entries[(mode.ordinal + 1) % Mode.entries.size]
            println("mode: $mode")
        }
        wasPressed = pressed
        when (mode) {
            Mode.SOS -> sos(led)
            else -> if (millis() - last >= mode.periodMs) { led.toggle(); last = millis() }
        }
    }
}
