package ktshim.java.lang;

import ktshim.ktvm.Native;

public final class Float extends Number implements Comparable {
    public static final float NaN = 0.0f / 0.0f;
    public static final float POSITIVE_INFINITY = 1.0f / 0.0f;
    public static final float NEGATIVE_INFINITY = -1.0f / 0.0f;
    public static final float MAX_VALUE = 3.4028235e+38f;
    public static final float MIN_VALUE = 1.4e-45f;
    private final float value;

    public Float(float v) { value = v; }
    public static Float valueOf(float v) { return new Float(v); }
    public static Float valueOf(String s) { return new Float(parseFloat(s)); }
    public static float parseFloat(String s) { return (float) Native.parseDouble(s); }
    public int intValue() { return (int) value; }
    public long longValue() { return (long) value; }
    public float floatValue() { return value; }
    public double doubleValue() { return value; }
    public String toString() { return Native.floatToString(value); }
    public static String toString(float v) { return Native.floatToString(v); }
    public int hashCode() { return Native.floatToIntBits(value); }
    public static int hashCode(float v) { return Native.floatToIntBits(v); }
    public boolean equals(Object o) { return o instanceof Float && Native.floatToIntBits(((Float) o).value) == Native.floatToIntBits(value); }
    public int compareTo(Object o) { return compare(value, ((Float) o).value); }
    public static int compare(float a, float b) {
        if (a < b) return -1;
        if (a > b) return 1;
        int x = Native.floatToIntBits(a), y = Native.floatToIntBits(b);
        return x == y ? 0 : (x < y ? -1 : 1);
    }
    public static boolean isNaN(float v) { return v != v; }
    public boolean isNaN() { return value != value; }
    public static boolean isInfinite(float v) { return v == POSITIVE_INFINITY || v == NEGATIVE_INFINITY; }
    public static boolean isFinite(float v) { return !isNaN(v) && !isInfinite(v); }
    public static int floatToIntBits(float v) { return Native.floatToIntBits(v); }
    public static int floatToRawIntBits(float v) { return Native.floatToIntBits(v); }
    public static float intBitsToFloat(int v) { return Native.intBitsToFloat(v); }
    public static float max(float a, float b) { return a > b ? a : b; }
    public static float min(float a, float b) { return a < b ? a : b; }
    public static float sum(float a, float b) { return a + b; }
}
