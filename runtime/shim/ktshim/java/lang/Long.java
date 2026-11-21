package ktshim.java.lang;

import ktshim.ktvm.Native;

public final class Long extends Number implements Comparable {
    public static final long MIN_VALUE = 0x8000000000000000L;
    public static final long MAX_VALUE = 0x7fffffffffffffffL;
    private final long value;

    public Long(long v) { value = v; }
    public static Long valueOf(long v) { return new Long(v); }
    public static Long valueOf(String s) { return new Long(parseLong(s, 10)); }
    public int intValue() { return (int) value; }
    public long longValue() { return value; }
    public float floatValue() { return value; }
    public double doubleValue() { return value; }
    public String toString() { return Native.longToString(value); }
    public static String toString(long v) { return Native.longToString(v); }
    public int hashCode() { return (int) (value ^ (value >>> 32)); }
    public static int hashCode(long v) { return (int) (v ^ (v >>> 32)); }
    public boolean equals(Object o) { return o instanceof Long && ((Long) o).value == value; }
    public int compareTo(Object o) { return compare(value, ((Long) o).value); }
    public static int compare(long a, long b) { return a < b ? -1 : (a == b ? 0 : 1); }
    public static int signum(long v) { return v > 0 ? 1 : (v < 0 ? -1 : 0); }
    public static long sum(long a, long b) { return a + b; }
    public static long max(long a, long b) { return a > b ? a : b; }
    public static long min(long a, long b) { return a < b ? a : b; }
    public static String toHexString(long v) {
        if (v == 0) return "0";
        StringBuilder sb = new StringBuilder();
        while (v != 0) { sb.append(Integer.digit((int) (v & 15))); v >>>= 4; }
        return sb.reverse().toString();
    }

    public static long parseLong(String s) { return parseLong(s, 10); }
    public static long parseLong(String s, int radix) {
        if (s == null) Native.raise(new java.lang.NumberFormatException("null"));
        int n = s.length(), i = 0;
        boolean neg = false;
        if (n > 0 && (s.charAt(0) == '-' || s.charAt(0) == '+')) { neg = s.charAt(0) == '-'; i = 1; }
        if (i >= n) Native.raise(new java.lang.NumberFormatException("For input string: \"" + s + "\""));
        long v = 0;
        for (; i < n; i++) {
            char c = s.charAt(i);
            int d = c >= '0' && c <= '9' ? c - '0' : c >= 'a' && c <= 'z' ? c - 'a' + 10 : c >= 'A' && c <= 'Z' ? c - 'A' + 10 : 99;
            if (d >= radix) Native.raise(new java.lang.NumberFormatException("For input string: \"" + s + "\""));
            v = v * radix + d;
        }
        return neg ? -v : v;
    }
}
