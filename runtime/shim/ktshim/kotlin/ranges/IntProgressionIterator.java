package ktshim.kotlin.ranges;

public final class IntProgressionIterator extends ktshim.kotlin.collections.IntIterator {
    private final int step, finalElement;
    private boolean hasNext;
    private int next;

    public IntProgressionIterator(int first, int last, int step) {
        this.step = step;
        this.finalElement = last;
        this.hasNext = step > 0 ? first <= last : first >= last;
        this.next = hasNext ? first : last;
    }
    public boolean hasNext() { return hasNext; }
    public int nextInt() {
        int v = next;
        if (v == finalElement) {
            if (!hasNext) throw new ktshim.java.util.NoSuchElementException();
            hasNext = false;
        } else next += step;
        return v;
    }
}
