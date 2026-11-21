interface Animal { val name: String; fun sound(): String; fun describe() = "$name says ${sound()}" }
class Dog(override val name: String) : Animal { override fun sound() = "woof" }
class Cat(override val name: String) : Animal { override fun sound() = "meow" }
open class Base(val v: Int) { open fun f() = v * 2; override fun toString() = "Base($v)" }
class Derived(v: Int) : Base(v) { override fun f() = super.f() + 1 }
object Counter { var n = 0; fun inc() = ++n }
class Box<T>(var item: T) { fun <R> map(f: (T) -> R) = Box(f(item)) }
data class Person(val name: String, val age: Int)
class MyError(msg: String) : RuntimeException(msg)
fun thrower(n: Int): Int { if (n > 3) throw MyError("too big: $n"); return n }
fun applyTwice(f: (Int) -> Int, x: Int) = f(f(x))
lateinit var late: String

fun main() {
    val animals: List<Animal> = listOf(Dog("Rex"), Cat("Tom"))
    animals.forEach { println(it.describe()) }
    println(Derived(5).f()); println(Base(3))
    repeat(3) { Counter.inc() }; println("counter=${Counter.n}")
    println(Box(21).map { it * 2 }.map { "v=$it" }.item)
    val people = listOf(Person("Ana", 31), Person("Luis", 25), Person("Eva", 40))
    println(people.sortedBy { it.age }.map { it.name })
    println(people.maxByOrNull { it.age }); println(people.filter { it.age > 30 }.size)
    println(people[0] == Person("Ana", 31)); println(people[0].copy(age = 32))
    val (n, a) = people[1]; println("$n is $a")
    var captured = 0
    val inc: () -> Unit = { captured += 10 }
    inc(); inc(); println("captured=$captured")
    println(applyTwice({ it * 3 }, 2))
    for (i in 0..12) { try { thrower(i) } catch (e: MyError) { println("caught ${e.message}"); break } }
    try { late.length } catch (e: Exception) { println("lateinit: ${e.message}") }
    val nul: String? = if (captured > 100) "x" else null
    println(nul?.length ?: -1)
    try { nul!!.length } catch (e: NullPointerException) { println("NPE ok") }
    val arr = IntArray(10) { it * it }; println(arr.sum()); println(arr.joinToString("-"))
    val grid = Array(3) { r -> IntArray(3) { c -> r * 3 + c } }; println(grid[2][1])
    val m = Array(2) { arrayOfNulls<String>(2) }; m[1][1] = "z"; println(m[1][1])
    println(when (val x = 7) { in 1..5 -> "low"; 6, 7 -> "seven-ish"; else -> "high" })
    val s = "Hello, World"
    println("${s.uppercase()} ${s.lowercase()} ${s.length} ${s.substring(7)} ${s.indexOf("World")} ${s.contains("lo,")}")
    println(s.split(", ").reversed()); println(s.replace("l", "L")); println("  trim  ".trim() + "|")
    println("abc".padStart(6, '*') + " " + "42".toInt() * 2 + " " + "x1".toIntOrNull())
    println(when (s) { "nope" -> 0; "Hello, World" -> 1; else -> 2 })
    println(listOf(3, 1, 2).sorted().joinToString(prefix = "<", postfix = ">"))
    val set = mutableSetOf(1, 2, 3, 2, 1); set.remove(2); println(set)
    val map = mapOf("a" to 1, "b" to 2); for ((k, v) in map) print("$k=$v "); println()
    println(map.getOrDefault("z", 99)); println(map.keys); println(map.values)
    println((1..20 step 3).toList()); println((10 downTo 1 step 4).toList())
    val sb = buildString { for (c in 'a'..'e') append(c) }; println(sb.reversed())
}
