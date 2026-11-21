package ktshim.java.lang;

import ktshim.ktvm.Native;

public final class Double extends Number implements Comparable {
    public static final double NaN = 0.0d / 0.0;
    public static final double POSITIVE_INFINITY = 1.0 / 0.0;
    public static final double NEGATIVE_INFINITY = -1.0 / 0.0;
    public static final double MAX_VALUE = 1.7976931348623157e+308;
    public static final double MIN_VALUE = 4.9e-324;
    private final double value;

    public Double(double v) { value = v; }
    public static Double valueOf(double v) { return new Double(v); }
    public static Double valueOf(String s) { return new Double(parseDouble(s)); }
    public static double parseDouble(String s) { return Native.parseDouble(s); }
    public int intValue() { return (int) value; }
    public long longValue() { return (long) value; }
    public float floatValue() { return (float) value; }
    public double doubleValue() { return value; }
    public String toString() { return Native.doubleToString(value); }
    public static String toString(double v) { return Native.doubleToString(v); }
    public int hashCode() { return hashCode(value); }
    public static int hashCode(double v) { long b = Native.doubleToLongBits(v); return (int) (b ^ (b >>> 32)); }
    public boolean equals(Object o) { return o instanceof Double && Native.doubleToLongBits(((Double) o).value) == Native.doubleToLongBits(value); }
    public int compareTo(Object o) { return compare(value, ((Double) o).value); }
    public static int compare(double a, double b) {
        if (a < b) return -1;
        if (a > b) return 1;
        long x = Native.doubleToLongBits(a), y = Native.doubleToLongBits(b);
        return x == y ? 0 : (x < y ? -1 : 1);
    }
    public static boolean isNaN(double v) { return v != v; }
    public boolean isNaN() { return value != value; }
    public static boolean isInfinite(double v) { return v == POSITIVE_INFINITY || v == NEGATIVE_INFINITY; }
    public static boolean isFinite(double v) { return !isNaN(v) && !isInfinite(v); }
    public static long doubleToLongBits(double v) { return Native.doubleToLongBits(v); }
    public static long doubleToRawLongBits(double v) { return Native.doubleToLongBits(v); }
    public static double longBitsToDouble(long v) { return Native.longBitsToDouble(v); }
    public static double max(double a, double b) { return a > b ? a : b; }
    public static double min(double a, double b) { return a < b ? a : b; }
    public static double sum(double a, double b) { return a + b; }
}
