package ktshim.java.util;

public final class Collections {
    static final Comparator NATURAL = new Comparator() {
        public int compare(Object a, Object b) { return ((Comparable) a).compareTo(b); }
    };

    public static void sort(List l) { sort(l, null); }

    /** Stable insertion/merge sort. */
    public static void sort(List l, Comparator c) {
        if (c == null) c = NATURAL;
        Object[] a = l.toArray();
        Object[] tmp = new Object[a.length];
        mergeSort(a, tmp, 0, a.length, c);
        for (int i = 0; i < a.length; i++) l.set(i, a[i]);
    }

    static void mergeSort(Object[] a, Object[] tmp, int lo, int hi, Comparator c) {
        if (hi - lo < 12) {
            for (int i = lo + 1; i < hi; i++) {
                Object x = a[i];
                int j = i - 1;
                while (j >= lo && c.compare(a[j], x) > 0) { a[j + 1] = a[j]; j--; }
                a[j + 1] = x;
            }
            return;
        }
        int mid = (lo + hi) >>> 1;
        mergeSort(a, tmp, lo, mid, c);
        mergeSort(a, tmp, mid, hi, c);
        int i = lo, j = mid, k = lo;
        while (i < mid && j < hi) tmp[k++] = c.compare(a[j], a[i]) < 0 ? a[j++] : a[i++];
        while (i < mid) tmp[k++] = a[i++];
        while (j < hi) tmp[k++] = a[j++];
        System.arraycopy(tmp, lo, a, lo, hi - lo);
    }

    public static void reverse(List l) {
        for (int i = 0, j = l.size() - 1; i < j; i++, j--) { Object t = l.get(i); l.set(i, l.get(j)); l.set(j, t); }
    }
    public static void swap(List l, int i, int j) { Object t = l.get(i); l.set(i, l.get(j)); l.set(j, t); }
    public static void shuffle(List l) {
        for (int i = l.size() - 1; i > 0; i--) swap(l, i, (int) (Math.random() * (i + 1)));
    }
    public static List emptyList() { return new ArrayList(0); }
    public static Set emptySet() { return new LinkedHashSet(); }
    public static Map emptyMap() { return new LinkedHashMap(); }
    public static List singletonList(Object o) { ArrayList l = new ArrayList(1); l.add(o); return l; }
    public static Set singleton(Object o) { LinkedHashSet s = new LinkedHashSet(); s.add(o); return s; }
    public static List unmodifiableList(List l) { return l; }
    public static Set unmodifiableSet(Set s) { return s; }
    public static Map unmodifiableMap(Map m) { return m; }
    public static Comparator reverseOrder() { return NATURAL.reversed(); }
}
