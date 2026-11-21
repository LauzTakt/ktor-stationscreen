class Sensor(val id: Int) {
    fun read(values: IntArray): Int = values[id]
}
fun process(s: Sensor) = s.read(intArrayOf(1, 2, 3)) * 2
fun main() {
    println("reading sensors...")
    for (i in 0..5) println("sensor $i = ${process(Sensor(i))}")
}
