package ktshim.kotlin.ranges;

public final class IntRange extends IntProgression {
    public IntRange(int start, int endInclusive) { super(start, endInclusive, 1); }
    public Integer getStart() { return Integer.valueOf(first); }
    public Integer getEndInclusive() { return Integer.valueOf(last); }
    public boolean contains(int v) { return first <= v && v <= last; }
    public boolean isEmpty() { return first > last; }
    public String toString() { return first + ".." + last; }
}
