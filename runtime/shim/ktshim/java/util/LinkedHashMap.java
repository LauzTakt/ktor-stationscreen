package ktshim.java.util;

public class LinkedHashMap extends HashMap {
    public LinkedHashMap() { super(); }
    public LinkedHashMap(int capacity) { super(capacity); }
    public LinkedHashMap(int capacity, float load) { super(capacity); }
    public LinkedHashMap(Map m) { super(m); }
}
