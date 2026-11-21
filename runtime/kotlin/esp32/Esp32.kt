// Hardware API for Kotlin programs running on the ESP32 (KTVM).
// `external` functions are implemented natively by the firmware.
package esp32

const val LOW = 0
const val HIGH = 1
const val INPUT = 0x01
const val OUTPUT = 0x03
const val INPUT_PULLUP = 0x05
const val INPUT_PULLDOWN = 0x09
/** Built-in blue LED on most ESP32 DevKit boards. */
const val LED_BUILTIN = 2
/** "BOOT" push button on most ESP32 DevKit boards (active LOW). */
const val BOOT_BUTTON = 0

external fun pinMode(pin: Int, mode: Int)
external fun digitalWrite(pin: Int, value: Int)
external fun digitalRead(pin: Int): Int
/** 12-bit ADC reading (0..4095). */
external fun analogRead(pin: Int): Int
/** PWM duty 0..255. */
external fun analogWrite(pin: Int, value: Int)
external fun touchRead(pin: Int): Int
external fun delay(ms: Int)
external fun millis(): Int
external fun micros(): Int
/** Hardware random number in 0 until bound. */
external fun randomInt(bound: Int): Int
/** Free bytes in the Kotlin heap. */
external fun freeMemory(): Int
external fun chipTemperature(): Float
/** Square wave on [pin] at [frequency] Hz for [durationMs] (0 = until noTone). */
external fun tone(pin: Int, frequency: Int, durationMs: Int)

/** Name of the board running the program: "esp32", "raspberrypi", "linux", "simulator". */
external fun boardName(): String
/** GPIO of the board's user LED (ESP32 DevKit: 2, Raspberry Pi: 17 by default, configurable on the agent). */
external fun boardLedPin(): Int

/** UTC wall-clock time in epoch milliseconds, or 0 if the clock was never set (see esp32.net.ntpSync). */
external fun epochMillis(): Long

/** A setting stored on the board (set with `./ktesp config key=value`), or null if it is not set. */
external fun deviceConfig(key: String): String?
/** Unique hardware id of this board (from its MAC address); "SIMULATOR" in the desktop simulator. */
external fun deviceId(): String

/**
 * Per-device settings, kept on the board outside the program, so the same program image can run on many
 * boards that each know who they are (e.g. which station they serve):
 *
 *     ./ktesp config station=8501120        # set
 *     ./ktesp config station=               # remove
 *     ./ktesp config                        # show
 */
object Device {
    val id: String get() = deviceId()
    fun config(key: String): String? = deviceConfig(key)
    fun config(key: String, default: String): String = deviceConfig(key) ?: default
}

/** Board information, so one program can run on an ESP32 and on a Raspberry Pi. */
object Board {
    val name: String get() = boardName()
    val ledPin: Int get() = boardLedPin()
    val isEsp32: Boolean get() = boardName() == "esp32"
    val isRaspberryPi: Boolean get() = boardName() == "raspberrypi"
}

fun digitalWrite(pin: Int, on: Boolean) = digitalWrite(pin, if (on) HIGH else LOW)
fun isHigh(pin: Int): Boolean = digitalRead(pin) == HIGH
fun noTone(pin: Int) = tone(pin, 0, 0)

/** A digital output pin, e.g. `val led = Led(LED_BUILTIN); led.on()`. */
class Led(val pin: Int) {
    var isOn: Boolean = false
        private set

    init { pinMode(pin, OUTPUT) }

    fun on() { set(true) }
    fun off() { set(false) }
    fun toggle() { set(!isOn) }
    fun set(value: Boolean) {
        isOn = value
        digitalWrite(pin, value)
    }
}

/** A push button with internal pull-up (pressed = LOW). */
class Button(val pin: Int) {
    init { pinMode(pin, INPUT_PULLUP) }
    val isPressed: Boolean get() = digitalRead(pin) == LOW
}

/** Runs [body] forever, sleeping [periodMs] between iterations. */
inline fun loop(periodMs: Int = 0, body: () -> Unit) {
    while (true) {
        body()
        if (periodMs > 0) delay(periodMs)
    }
}
