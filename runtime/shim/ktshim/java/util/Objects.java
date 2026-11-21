package ktshim.java.util;

public final class Objects {
    public static boolean equals(Object a, Object b) { return a == b || (a != null && a.equals(b)); }
    public static int hashCode(Object o) { return o == null ? 0 : o.hashCode(); }
    public static String toString(Object o) { return String.valueOf(o); }
    public static Object requireNonNull(Object o) { if (o == null) throw new NullPointerException(); return o; }
    public static Object requireNonNull(Object o, String m) { if (o == null) throw new NullPointerException(m); return o; }
    public static int hash(Object[] vals) {
        int h = 1;
        for (int i = 0; i < vals.length; i++) h = 31 * h + hashCode(vals[i]);
        return h;
    }
}
