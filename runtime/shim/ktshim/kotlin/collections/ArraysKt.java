package ktshim.kotlin.collections;

import ktshim.java.util.*;
import ktshim.kotlin.jvm.functions.Function1;
import ktshim.kotlin.ranges.IntRange;

public final class ArraysKt {
    private ArraysKt() {}

    static List box(int[] a) { ArrayList l = new ArrayList(a.length); for (int i = 0; i < a.length; i++) l.add(Integer.valueOf(a[i])); return l; }
    static List box(double[] a) { ArrayList l = new ArrayList(a.length); for (int i = 0; i < a.length; i++) l.add(Double.valueOf(a[i])); return l; }
    static List box(float[] a) { ArrayList l = new ArrayList(a.length); for (int i = 0; i < a.length; i++) l.add(Float.valueOf(a[i])); return l; }

    public static List asList(Object[] a) { return Arrays.asList(a); }
    public static List toList(Object[] a) { return Arrays.asList(a); }
    public static List toMutableList(Object[] a) { return Arrays.asList(a); }
    public static List toList(int[] a) { return box(a); }
    public static List toMutableList(int[] a) { return box(a); }
    public static List toList(double[] a) { return box(a); }
    public static List toList(float[] a) { return box(a); }
    public static Set toSet(Object[] a) { return CollectionsKt.toSet(Arrays.asList(a)); }

    public static int sum(int[] a) { int s = 0; for (int i = 0; i < a.length; i++) s += a[i]; return s; }
    public static long sum(long[] a) { long s = 0; for (int i = 0; i < a.length; i++) s += a[i]; return s; }
    public static double sum(double[] a) { double s = 0; for (int i = 0; i < a.length; i++) s += a[i]; return s; }
    public static float sum(float[] a) { float s = 0; for (int i = 0; i < a.length; i++) s += a[i]; return s; }
    public static double average(int[] a) { return a.length == 0 ? Double.NaN : (double) sum(a) / a.length; }
    public static double average(double[] a) { return a.length == 0 ? Double.NaN : sum(a) / a.length; }
    public static int maxOrThrow(int[] a) { if (a.length == 0) throw new NoSuchElementException(); int m = a[0]; for (int i = 1; i < a.length; i++) if (a[i] > m) m = a[i]; return m; }
    public static int minOrThrow(int[] a) { if (a.length == 0) throw new NoSuchElementException(); int m = a[0]; for (int i = 1; i < a.length; i++) if (a[i] < m) m = a[i]; return m; }
    public static Integer maxOrNull(int[] a) { return a.length == 0 ? null : Integer.valueOf(maxOrThrow(a)); }
    public static Integer minOrNull(int[] a) { return a.length == 0 ? null : Integer.valueOf(minOrThrow(a)); }
    public static double maxOrThrow(double[] a) { if (a.length == 0) throw new NoSuchElementException(); double m = a[0]; for (int i = 1; i < a.length; i++) m = Math.max(m, a[i]); return m; }
    public static double minOrThrow(double[] a) { if (a.length == 0) throw new NoSuchElementException(); double m = a[0]; for (int i = 1; i < a.length; i++) m = Math.min(m, a[i]); return m; }

    public static IntRange getIndices(int[] a) { return new IntRange(0, a.length - 1); }
    public static IntRange getIndices(Object[] a) { return new IntRange(0, a.length - 1); }
    public static int getLastIndex(int[] a) { return a.length - 1; }
    public static int getLastIndex(Object[] a) { return a.length - 1; }
    public static int getLastIndex(double[] a) { return a.length - 1; }
    public static int getLastIndex(byte[] a) { return a.length - 1; }
    public static int first(int[] a) { if (a.length == 0) throw new NoSuchElementException("Array is empty."); return a[0]; }
    public static int last(int[] a) { if (a.length == 0) throw new NoSuchElementException("Array is empty."); return a[a.length - 1]; }
    public static Object first(Object[] a) { if (a.length == 0) throw new NoSuchElementException("Array is empty."); return a[0]; }
    public static Object last(Object[] a) { if (a.length == 0) throw new NoSuchElementException("Array is empty."); return a[a.length - 1]; }
    public static int indexOf(int[] a, int v) { for (int i = 0; i < a.length; i++) if (a[i] == v) return i; return -1; }
    public static int indexOf(Object[] a, Object v) { for (int i = 0; i < a.length; i++) if (Objects.equals(a[i], v)) return i; return -1; }
    public static boolean contains(int[] a, int v) { return indexOf(a, v) >= 0; }
    public static boolean contains(Object[] a, Object v) { return indexOf(a, v) >= 0; }
    public static boolean contains(char[] a, char v) { for (int i = 0; i < a.length; i++) if (a[i] == v) return true; return false; }

    public static void sort(int[] a) { Arrays.sort(a); }
    public static void sort(Object[] a) { Arrays.sort(a); }
    public static List sorted(int[] a) { int[] c = Arrays.copyOf(a, a.length); Arrays.sort(c); return box(c); }
    public static void reverse(int[] a) { for (int i = 0, j = a.length - 1; i < j; i++, j--) { int t = a[i]; a[i] = a[j]; a[j] = t; } }
    public static List reversed(int[] a) { List l = box(a); Collections.reverse(l); return l; }
    public static void fill(int[] a, int v, int from, int to) { Arrays.fill(a, from, to, v); }
    public static void fill$default(int[] a, int v, int from, int to, int mask, Object unused) {
        if ((mask & 2) != 0) from = 0;
        if ((mask & 4) != 0) to = a.length;
        Arrays.fill(a, from, to, v);
    }
    public static void fill(Object[] a, Object v, int from, int to) { Arrays.fill(a, from, to, v); }
    public static void fill$default(Object[] a, Object v, int from, int to, int mask, Object unused) {
        if ((mask & 2) != 0) from = 0;
        if ((mask & 4) != 0) to = a.length;
        Arrays.fill(a, from, to, v);
    }

    public static String contentToString(int[] a) { return Arrays.toString(a); }
    public static String contentToString(Object[] a) { return Arrays.toString(a); }
    public static String contentToString(double[] a) { return box(a).toString(); }
    public static String contentToString(float[] a) { return box(a).toString(); }
    public static boolean contentEquals(int[] a, int[] b) { return Arrays.equals(a, b); }
    public static int contentHashCode(int[] a) { return Arrays.hashCode(a); }

    public static String joinToString(Object[] a, java.lang.CharSequence sep, java.lang.CharSequence prefix, java.lang.CharSequence postfix,
                                      int limit, java.lang.CharSequence truncated, Function1 transform) {
        return CollectionsKt.joinToString(Arrays.asList(a), sep, prefix, postfix, limit, truncated, transform);
    }
    public static String joinToString$default(Object[] a, java.lang.CharSequence sep, java.lang.CharSequence prefix, java.lang.CharSequence postfix,
                                              int limit, java.lang.CharSequence truncated, Function1 transform, int mask, Object u) {
        return CollectionsKt.joinToString$default(Arrays.asList(a), sep, prefix, postfix, limit, truncated, transform, mask, u);
    }
    public static String joinToString$default(int[] a, java.lang.CharSequence sep, java.lang.CharSequence prefix, java.lang.CharSequence postfix,
                                              int limit, java.lang.CharSequence truncated, Function1 transform, int mask, Object u) {
        return CollectionsKt.joinToString$default(box(a), sep, prefix, postfix, limit, truncated, transform, mask, u);
    }
    public static String joinToString$default(double[] a, java.lang.CharSequence sep, java.lang.CharSequence prefix, java.lang.CharSequence postfix,
                                              int limit, java.lang.CharSequence truncated, Function1 transform, int mask, Object u) {
        return CollectionsKt.joinToString$default(box(a), sep, prefix, postfix, limit, truncated, transform, mask, u);
    }
    public static String joinToString$default(float[] a, java.lang.CharSequence sep, java.lang.CharSequence prefix, java.lang.CharSequence postfix,
                                              int limit, java.lang.CharSequence truncated, Function1 transform, int mask, Object u) {
        return CollectionsKt.joinToString$default(box(a), sep, prefix, postfix, limit, truncated, transform, mask, u);
    }
}
