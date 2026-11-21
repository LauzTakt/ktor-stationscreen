package ktshim.kotlin.collections;

import ktshim.java.util.*;
import ktshim.kotlin.Pair;

public final class MapsKt {
    private MapsKt() {}
    public static Map mapOf(Pair[] pairs) { return mutableMapOf(pairs); }
    public static Map mapOf(Pair p) { LinkedHashMap m = new LinkedHashMap(); m.put(p.getFirst(), p.getSecond()); return m; }
    public static Map emptyMap() { return new LinkedHashMap(); }
    public static Map mutableMapOf(Pair[] pairs) {
        LinkedHashMap m = new LinkedHashMap(pairs.length);
        for (int i = 0; i < pairs.length; i++) m.put(pairs[i].getFirst(), pairs[i].getSecond());
        return m;
    }
    public static HashMap hashMapOf(Pair[] pairs) { return (HashMap) mutableMapOf(pairs); }
    public static LinkedHashMap linkedMapOf(Pair[] pairs) { return (LinkedHashMap) mutableMapOf(pairs); }
    public static int mapCapacity(int n) { return n < 3 ? n + 1 : n + n / 3; }
    public static Object getValue(Map m, Object k) {
        Object v = m.get(k);
        if (v == null && !m.containsKey(k)) throw new NoSuchElementException("Key " + k + " is missing in the map.");
        return v;
    }
    public static Map toMap(ktshim.java.lang.Iterable pairs) {
        LinkedHashMap m = new LinkedHashMap();
        Iterator x = pairs.iterator();
        while (x.hasNext()) { Pair p = (Pair) x.next(); m.put(p.getFirst(), p.getSecond()); }
        return m;
    }
    public static Map toMutableMap(Map m) { return new LinkedHashMap(m); }
    public static Map toMap(Map m) { return new LinkedHashMap(m); }
    public static Map plus(Map m, Pair p) { LinkedHashMap r = new LinkedHashMap(m); r.put(p.getFirst(), p.getSecond()); return r; }
    public static List toList(Map m) {
        ArrayList l = new ArrayList(m.size());
        Iterator x = m.entrySet().iterator();
        while (x.hasNext()) { Map.Entry e = (Map.Entry) x.next(); l.add(new Pair(e.getKey(), e.getValue())); }
        return l;
    }
}
