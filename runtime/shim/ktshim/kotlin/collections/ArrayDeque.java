package ktshim.kotlin.collections;

import ktshim.java.util.Collection;
import ktshim.java.util.LinkedList;
import ktshim.java.util.NoSuchElementException;

/** kotlin.collections.ArrayDeque (array-backed). */
public final class ArrayDeque extends LinkedList {
    public ArrayDeque() { super(); }
    public ArrayDeque(int capacity) { super(); }
    public ArrayDeque(Collection c) { super(c); }
    public int getSize() { return size(); }
    public Object first() { if (isEmpty()) throw new NoSuchElementException("ArrayDeque is empty."); return get(0); }
    public Object last() { if (isEmpty()) throw new NoSuchElementException("ArrayDeque is empty."); return get(size() - 1); }
    public Object firstOrNull() { return isEmpty() ? null : get(0); }
    public Object lastOrNull() { return isEmpty() ? null : get(size() - 1); }
    public Object removeFirstOrNull() { return isEmpty() ? null : remove(0); }
    public Object removeLastOrNull() { return isEmpty() ? null : remove(size() - 1); }
}
