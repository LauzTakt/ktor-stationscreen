package ktshim.java.util;

public class LinkedHashSet extends HashSet {
    public LinkedHashSet() { super(); }
    public LinkedHashSet(int capacity) { super(capacity); }
    public LinkedHashSet(int capacity, float load) { super(capacity); }
    public LinkedHashSet(Collection c) { super(c); }
}
