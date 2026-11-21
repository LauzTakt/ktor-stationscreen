data class Point(val x: Int, val y: Int) {
    operator fun plus(o: Point) = Point(x + o.x, y + o.y)
}

sealed class Shape {
    abstract fun area(): Double
    class Circle(val r: Double) : Shape() { override fun area() = Math.PI * r * r }
    class Rect(val w: Double, val h: Double) : Shape() { override fun area() = w * h }
}

enum class Color { RED, GREEN, BLUE }

fun fib(n: Int): Int = if (n < 2) n else fib(n - 1) + fib(n - 2)

fun main() {
    println("Hello from Kotlin on the ESP32!")
    val p = Point(1, 2) + Point(3, 4)
    println("p = $p, x*y = ${p.x * p.y}")

    val shapes = listOf(Shape.Circle(1.5), Shape.Rect(2.0, 3.5))
    for (s in shapes) println("${if (s is Shape.Circle) "circle" else "rect"} area = ${s.area()}")

    val squares = (1..10).map { it * it }.filter { it % 2 == 0 }
    println("even squares: $squares, sum = ${squares.sum()}")

    val counts = mutableMapOf<String, Int>()
    for (w in "the quick brown fox jumps over the lazy dog the end".split(" ")) {
        counts[w] = (counts[w] ?: 0) + 1
    }
    println("counts: $counts")

    for (c in Color.entries) {
        val hex = when (c) {
            Color.RED -> "#f00"
            Color.GREEN -> "#0f0"
            Color.BLUE -> "#00f"
        }
        println("${c.name} (${c.ordinal}) -> $hex")
    }

    try {
        val zero = squares.size - squares.size
        println(10 / zero)
    } catch (e: ArithmeticException) {
        println("caught: ${e.message}")
    }

    val sb = StringBuilder()
    repeat(5) { sb.append(it).append(',') }
    println("sb = $sb, fib(20) = ${fib(20)}, pi ~ ${"%.3f".format(Math.PI)}")
    println("sorted: ${listOf(5, 3, 9, 1).sorted()} by length: ${listOf("ccc", "a", "bb").sortedBy { it.length }}")
}
