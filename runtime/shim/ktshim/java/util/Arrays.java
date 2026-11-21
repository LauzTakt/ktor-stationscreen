package ktshim.java.util;

public final class Arrays {
    public static List asList(Object[] a) {
        ArrayList l = new ArrayList(a.length);
        for (int i = 0; i < a.length; i++) l.add(a[i]);
        return l;
    }
    public static void fill(int[] a, int v) { for (int i = 0; i < a.length; i++) a[i] = v; }
    public static void fill(int[] a, int from, int to, int v) { for (int i = from; i < to; i++) a[i] = v; }
    public static void fill(byte[] a, byte v) { for (int i = 0; i < a.length; i++) a[i] = v; }
    public static void fill(char[] a, char v) { for (int i = 0; i < a.length; i++) a[i] = v; }
    public static void fill(boolean[] a, boolean v) { for (int i = 0; i < a.length; i++) a[i] = v; }
    public static void fill(float[] a, float v) { for (int i = 0; i < a.length; i++) a[i] = v; }
    public static void fill(double[] a, double v) { for (int i = 0; i < a.length; i++) a[i] = v; }
    public static void fill(long[] a, long v) { for (int i = 0; i < a.length; i++) a[i] = v; }
    public static void fill(Object[] a, Object v) { for (int i = 0; i < a.length; i++) a[i] = v; }
    public static void fill(Object[] a, int from, int to, Object v) { for (int i = from; i < to; i++) a[i] = v; }

    public static void sort(int[] a) { sort(a, 0, a.length); }
    public static void sort(int[] a, int from, int to) {
        for (int i = from + 1; i < to; i++) {
            int x = a[i], j = i - 1;
            while (j >= from && a[j] > x) { a[j + 1] = a[j]; j--; }
            a[j + 1] = x;
        }
    }
    public static void sort(Object[] a) { sort(a, null); }
    public static void sort(Object[] a, Comparator c) {
        if (c == null) c = Collections.NATURAL;
        Collections.mergeSort(a, new Object[a.length], 0, a.length, c);
    }
    public static int[] copyOf(int[] a, int n) { int[] r = new int[n]; System.arraycopy(a, 0, r, 0, Math.min(n, a.length)); return r; }
    public static byte[] copyOf(byte[] a, int n) { byte[] r = new byte[n]; System.arraycopy(a, 0, r, 0, Math.min(n, a.length)); return r; }
    public static char[] copyOf(char[] a, int n) { char[] r = new char[n]; System.arraycopy(a, 0, r, 0, Math.min(n, a.length)); return r; }
    public static double[] copyOf(double[] a, int n) { double[] r = new double[n]; System.arraycopy(a, 0, r, 0, Math.min(n, a.length)); return r; }
    public static float[] copyOf(float[] a, int n) { float[] r = new float[n]; System.arraycopy(a, 0, r, 0, Math.min(n, a.length)); return r; }
    public static long[] copyOf(long[] a, int n) { long[] r = new long[n]; System.arraycopy(a, 0, r, 0, Math.min(n, a.length)); return r; }
    public static boolean[] copyOf(boolean[] a, int n) { boolean[] r = new boolean[n]; System.arraycopy(a, 0, r, 0, Math.min(n, a.length)); return r; }
    public static Object[] copyOf(Object[] a, int n) { Object[] r = new Object[n]; System.arraycopy(a, 0, r, 0, Math.min(n, a.length)); return r; }
    public static int[] copyOfRange(int[] a, int from, int to) { int[] r = new int[to - from]; System.arraycopy(a, from, r, 0, to - from); return r; }
    public static Object[] copyOfRange(Object[] a, int from, int to) { Object[] r = new Object[to - from]; System.arraycopy(a, from, r, 0, to - from); return r; }
    public static boolean equals(int[] a, int[] b) {
        if (a == b) return true;
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }
    public static boolean equals(Object[] a, Object[] b) {
        if (a == b) return true;
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (!ArrayList.eq(a[i], b[i])) return false;
        return true;
    }
    public static int hashCode(int[] a) { int h = 1; for (int i = 0; i < a.length; i++) h = 31 * h + a[i]; return h; }
    public static String toString(int[] a) {
        if (a == null) return "null";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(", "); sb.append(a[i]); }
        return sb.append(']').toString();
    }
    public static String toString(Object[] a) {
        if (a == null) return "null";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(", "); sb.append(a[i]); }
        return sb.append(']').toString();
    }
}
