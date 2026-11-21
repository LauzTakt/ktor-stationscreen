package ktshim.kotlin;

public final class Pair {
    private final Object first, second;
    public Pair(Object first, Object second) { this.first = first; this.second = second; }
    public Object getFirst() { return first; }
    public Object getSecond() { return second; }
    public Object component1() { return first; }
    public Object component2() { return second; }
    public String toString() { return "(" + first + ", " + second + ")"; }
    public int hashCode() { return (first == null ? 0 : first.hashCode()) * 31 + (second == null ? 0 : second.hashCode()); }
    public boolean equals(Object o) {
        if (!(o instanceof Pair)) return false;
        Pair p = (Pair) o;
        return ktshim.java.util.Objects.equals(first, p.first) && ktshim.java.util.Objects.equals(second, p.second);
    }
}
