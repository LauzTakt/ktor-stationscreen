package ktshim.kotlin.collections;

public abstract class IntIterator implements ktshim.java.util.Iterator {
    public abstract int nextInt();
    public final Object next() { return Integer.valueOf(nextInt()); }
    public void remove() { throw new UnsupportedOperationException(); }
}
