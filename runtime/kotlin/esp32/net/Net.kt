// Networking for KTVM programs: WiFi, HTTP GET and WebSocket client.
// On the ESP32 this uses the WiFi radio; in the simulator it uses the computer's network.
package esp32.net

/** Connects to a WiFi network (no-op in the simulator). Returns false on timeout. */
external fun wifiConnect(ssid: String, password: String, timeoutMs: Int): Boolean
/** Current IP address, "" when not connected. */
external fun wifiIp(): String
/** HTTP(S) GET. Returns the body or null on network error; see [httpStatus]. */
external fun httpGet(url: String, timeoutMs: Int): String?
/** Status code of the last [httpGet] (-1 = network error). */
external fun httpStatus(): Int
external fun wsOpen(url: String, timeoutMs: Int): Int
external fun wsPoll(handle: Int, timeoutMs: Int): String?
external fun wsSend(handle: Int, text: String): Boolean
external fun wsConnected(handle: Int): Boolean
external fun wsClose(handle: Int)

fun wifiConnect(ssid: String, password: String): Boolean = wifiConnect(ssid, password, 20000)

class HttpException(val status: Int, message: String) : Exception(message)

object Http {
    /** GET [url] and return the body; throws [HttpException] on network errors or non-2xx status. */
    fun get(url: String, timeoutMs: Int = 8000): String {
        val body = httpGet(url, timeoutMs)
        val st = httpStatus()
        if (body == null) throw HttpException(st, "GET $url failed (network error)")
        if (st < 200 || st > 299) throw HttpException(st, "GET $url -> HTTP $st")
        return body
    }

    fun getOrNull(url: String, timeoutMs: Int = 8000): String? {
        val body = httpGet(url, timeoutMs)
        val st = httpStatus()
        return if (body != null && st >= 200 && st <= 299) body else null
    }
}

/** A WebSocket client. Single-threaded: call [receive] regularly from your loop. */
class WebSocket(val url: String) {
    private var handle = -1

    val isOpen: Boolean get() = handle >= 0 && wsConnected(handle)

    /** (Re)connects. Returns true when the connection is open. */
    fun connect(timeoutMs: Int = 8000): Boolean {
        if (handle >= 0) wsClose(handle)
        handle = wsOpen(url, timeoutMs)
        return handle >= 0
    }

    /** Waits up to [timeoutMs] for a text message; null if none arrived (or the socket is closed). */
    fun receive(timeoutMs: Int = 0): String? = if (handle >= 0) wsPoll(handle, timeoutMs) else null

    fun send(text: String): Boolean = handle >= 0 && wsSend(handle, text)

    fun close() {
        if (handle >= 0) wsClose(handle)
        handle = -1
    }
}
