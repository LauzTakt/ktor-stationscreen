package ktshim.java.lang;

import ktshim.ktvm.Native;

public final class Integer extends Number implements Comparable {
    public static final int MIN_VALUE = 0x80000000;
    public static final int MAX_VALUE = 0x7fffffff;
    private static Integer[] cache;
    private final int value;

    public Integer(int v) { value = v; }

    public static Integer valueOf(int v) {
        if (v >= -128 && v < 128) {
            if (cache == null) cache = new Integer[256];
            Integer c = cache[v + 128];
            if (c == null) { c = new Integer(v); cache[v + 128] = c; }
            return c;
        }
        return new Integer(v);
    }
    public static Integer valueOf(String s) { return valueOf(parseInt(s, 10)); }

    public int intValue() { return value; }
    public long longValue() { return value; }
    public float floatValue() { return value; }
    public double doubleValue() { return value; }

    public String toString() { return Native.intToString(value); }
    public int hashCode() { return value; }
    public boolean equals(Object o) { return o instanceof Integer && ((Integer) o).value == value; }
    public int compareTo(Integer o) { return compare(value, o.value); }
    public int compareTo(Object o) { return compare(value, ((Integer) o).value); }

    public static int hashCode(int v) { return v; }
    public static int compare(int a, int b) { return a < b ? -1 : (a == b ? 0 : 1); }
    public static int signum(int v) { return v > 0 ? 1 : (v < 0 ? -1 : 0); }
    public static int sum(int a, int b) { return a + b; }
    public static int max(int a, int b) { return a > b ? a : b; }
    public static int min(int a, int b) { return a < b ? a : b; }
    public static int abs(int a) { return a < 0 ? -a : a; }
    public static String toString(int v) { return Native.intToString(v); }

    public static String toString(int v, int radix) {
        if (radix < 2 || radix > 36) radix = 10;
        if (radix == 10) return Native.intToString(v);
        char[] buf = new char[33];
        boolean neg = v < 0;
        int p = 32;
        if (!neg) v = -v;
        while (v <= -radix) { buf[p--] = digit(-(v % radix)); v = v / radix; }
        buf[p] = digit(-v);
        if (neg) buf[--p] = '-';
        return String.valueOf(copy(buf, p, 33 - p));
    }

    private static String unsigned(int v, int shift) {
        char[] buf = new char[32];
        int p = 32, mask = (1 << shift) - 1;
        do { buf[--p] = digit(v & mask); v >>>= shift; } while (v != 0);
        return String.valueOf(copy(buf, p, 32 - p));
    }
    public static String toHexString(int v) { return unsigned(v, 4); }
    public static String toBinaryString(int v) { return unsigned(v, 1); }
    public static String toOctalString(int v) { return unsigned(v, 3); }

    static char digit(int d) { return (char) (d < 10 ? '0' + d : 'a' + d - 10); }
    static char[] copy(char[] b, int off, int n) {
        char[] r = new char[n];
        Native.arraycopy(b, off, r, 0, n);
        return r;
    }

    public static int parseInt(String s) { return parseInt(s, 10); }
    public static int parseInt(String s, int radix) {
        long v = Long.parseLong(s, radix);
        if (v < MIN_VALUE || v > MAX_VALUE) Native.raise(new java.lang.NumberFormatException("For input string: \"" + s + "\""));
        return (int) v;
    }

    public static int bitCount(int i) {
        i = i - ((i >>> 1) & 0x55555555);
        i = (i & 0x33333333) + ((i >>> 2) & 0x33333333);
        i = (i + (i >>> 4)) & 0x0f0f0f0f;
        i = i + (i >>> 8);
        i = i + (i >>> 16);
        return i & 0x3f;
    }
    public static int numberOfLeadingZeros(int i) {
        if (i == 0) return 32;
        int n = 0;
        while ((i & 0x80000000) == 0) { n++; i <<= 1; }
        return n;
    }
    public static int numberOfTrailingZeros(int i) {
        if (i == 0) return 32;
        int n = 0;
        while ((i & 1) == 0) { n++; i >>>= 1; }
        return n;
    }
    public static int highestOneBit(int i) { return i == 0 ? 0 : (0x80000000 >>> numberOfLeadingZeros(i)); }
    public static int lowestOneBit(int i) { return i & -i; }
    public static int rotateLeft(int i, int d) { return (i << d) | (i >>> -d); }
    public static int rotateRight(int i, int d) { return (i >>> d) | (i << -d); }
    public static int reverse(int i) {
        int r = 0;
        for (int k = 0; k < 32; k++) { r = (r << 1) | (i & 1); i >>>= 1; }
        return r;
    }
}
