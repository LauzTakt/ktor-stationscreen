class Node(val v: Int, var next: Node?)
fun main() {
    // allocate far more than the heap: the GC must reclaim garbage
    var total = 0L
    for (round in 1..200) {
        var head: Node? = null
        for (i in 1..500) head = Node(i, head)
        var p = head; while (p != null) { total += p.v; p = p.next }
        val s = (1..50).joinToString(",") { "item$it" }
        total += s.length
    }
    println("total=$total")
    val keep = ArrayList<String>()
    for (i in 1..2000) { keep.add("k$i"); if (keep.size > 100) keep.removeAt(0) }
    println("kept=${keep.size} first=${keep.first()} last=${keep.last()}")
    val m = HashMap<Int, String>()
    for (i in 1..3000) { m[i % 257] = "v$i" }
    println("map=${m.size} ${m[0]} ${m[256]}")
    fun depth(n: Int): Int = if (n == 0) 0 else 1 + depth(n - 1)
    println("depth=${depth(100)}")
}
