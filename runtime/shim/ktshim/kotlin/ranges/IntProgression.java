package ktshim.kotlin.ranges;

import ktshim.kotlin.internal.ProgressionUtilKt;

public class IntProgression implements ktshim.java.lang.Iterable {
    final int first, last, step;

    public IntProgression(int start, int end, int step) {
        if (step == 0) throw new IllegalArgumentException("Step must be non-zero.");
        this.first = start;
        this.last = ProgressionUtilKt.getProgressionLastElement(start, end, step);
        this.step = step;
    }
    public final int getFirst() { return first; }
    public final int getLast() { return last; }
    public final int getStep() { return step; }
    public boolean isEmpty() { return step > 0 ? first > last : first < last; }
    public ktshim.java.util.Iterator iterator() { return new IntProgressionIterator(first, last, step); }
    public String toString() { return step > 0 ? first + ".." + last + " step " + step : first + " downTo " + last + " step " + (-step); }
    public boolean equals(Object o) {
        if (!(o instanceof IntProgression)) return false;
        IntProgression p = (IntProgression) o;
        return (isEmpty() && p.isEmpty()) || (first == p.first && last == p.last && step == p.step);
    }
    public int hashCode() { return isEmpty() ? -1 : 31 * (31 * first + last) + step; }
}
