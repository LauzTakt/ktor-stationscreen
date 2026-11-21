package ktshim.kotlin.collections;

import ktshim.java.util.*;

public final class SetsKt {
    private SetsKt() {}
    public static Set setOf(Object[] a) { return mutableSetOf(a); }
    public static Set setOf(Object a) { LinkedHashSet s = new LinkedHashSet(); s.add(a); return s; }
    public static Set emptySet() { return new LinkedHashSet(); }
    public static Set mutableSetOf(Object[] a) { LinkedHashSet s = new LinkedHashSet(); for (int i = 0; i < a.length; i++) s.add(a[i]); return s; }
    public static HashSet hashSetOf(Object[] a) { return (HashSet) mutableSetOf(a); }
    public static LinkedHashSet linkedSetOf(Object[] a) { return (LinkedHashSet) mutableSetOf(a); }
    public static Set plus(Set s, Object o) { LinkedHashSet r = new LinkedHashSet(s); r.add(o); return r; }
    public static Set minus(Set s, Object o) { LinkedHashSet r = new LinkedHashSet(s); r.remove(o); return r; }
}
