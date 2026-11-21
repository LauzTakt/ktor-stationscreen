fun main() {
    val a = 2147483647; println(a + 1); println(-7 / 2); println(-7 % 2); println(7 shr 1); println(-8 ushr 28)
    val l = 1L shl 40; println(l); println(l * 3 + 7); println(Long.MAX_VALUE); println(l / 7); println(-l % 9)
    println(1.0 / 3); println(2.5f * 4); println(0.1 + 0.2); println(1e10); println(1.5e-5); println(100.0); println(-0.0)
    println(Math.sqrt(2.0)); println(Math.pow(2.0, 10.0)); println(kotlin.math.sin(1.0)); println(kotlin.math.abs(-3))
    println(3.7.toInt()); println((-3.7).toInt()); println(Double.NaN.toInt()); println(1e20.toInt()); println(255.toByte()); println(65.toChar())
    println(3.14159f); println(1.0f / 3); println(123456789.0f); println(0.001); println(0.0001); println(1234567.0); println(12345678.0)
    println(10.0.compareTo(3.0)); println(Int.MIN_VALUE / -1); println(0x7f and 0x0f); println(5 xor 3); println(5.inv())
    println("%d items at %.2f each = %08.3f".format(3, 1.5, 4.5)); println(Integer.toHexString(255)); println(255.toString(2))
    var x = 0L; for (i in 1..100000) x += i; println(x)
    var f = 1.0; for (i in 1..20) f *= i; println(f)
    println(kotlin.math.round(2.5).toInt()); println(maxOf(3, 9)); println(minOf(2.0, -1.0)); println(17.coerceIn(0, 10))
    println(Int.MAX_VALUE.toLong() * 2); println((1 shl 31)); println(-1 ushr 1)
}
