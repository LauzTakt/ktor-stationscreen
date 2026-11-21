package ktshim.kotlin.comparisons;

import ktshim.java.util.Comparator;

public final class ComparisonsKt {
    private ComparisonsKt() {}
    public static int compareValues(java.lang.Comparable a, java.lang.Comparable b) {
        if (a == b) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        return a.compareTo(b);
    }
    public static Comparator naturalOrder() { return new Comparator() { public int compare(Object a, Object b) { return ((java.lang.Comparable) a).compareTo(b); } }; }
    public static Comparator reverseOrder() { return naturalOrder().reversed(); }
    public static java.lang.Comparable maxOf(java.lang.Comparable a, java.lang.Comparable b) { return a.compareTo(b) >= 0 ? a : b; }
    public static java.lang.Comparable minOf(java.lang.Comparable a, java.lang.Comparable b) { return a.compareTo(b) <= 0 ? a : b; }
}
