package ktshim.java.util;

/** Array-backed stand-in for LinkedList / ArrayDeque. */
public class LinkedList extends ArrayList {
    public LinkedList() { super(); }
    public LinkedList(Collection c) { super(c); }
    public void addFirst(Object o) { add(0, o); }
    public void addLast(Object o) { add(o); }
    public Object removeFirst() { if (size == 0) throw new NoSuchElementException(); return remove(0); }
    public Object removeLast() { if (size == 0) throw new NoSuchElementException(); return remove(size - 1); }
    public Object getFirst() { if (size == 0) throw new NoSuchElementException(); return get(0); }
    public Object getLast() { if (size == 0) throw new NoSuchElementException(); return get(size - 1); }
    public Object peek() { return size == 0 ? null : get(0); }
    public Object poll() { return size == 0 ? null : remove(0); }
    public boolean offer(Object o) { return add(o); }
    public void push(Object o) { add(0, o); }
    public Object pop() { return removeFirst(); }
}
