package ktshim.kotlin.math;

public final class MathKt {
    private MathKt() {}
    public static int roundToInt(float x) {
        if (x != x) throw new IllegalArgumentException("Cannot round NaN value.");
        return Math.round(x);
    }
    public static int roundToInt(double x) {
        if (x != x) throw new IllegalArgumentException("Cannot round NaN value.");
        long r = Math.round(x);
        return r > Integer.MAX_VALUE ? Integer.MAX_VALUE : (r < Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) r);
    }
    public static long roundToLong(double x) { return Math.round(x); }
    public static long roundToLong(float x) { return Math.round((double) x); }
    public static int getSign(int x) { return x > 0 ? 1 : (x < 0 ? -1 : 0); }
    public static int getSign(long x) { return x > 0 ? 1 : (x < 0 ? -1 : 0); }
    public static double log(double x, double base) { return Math.log(x) / Math.log(base); }
    public static double log2(double x) { return Math.log(x) / 0.6931471805599453; }
    public static float log2(float x) { return (float) (Math.log(x) / 0.6931471805599453); }
    public static int getAbsoluteValue(int x) { return x < 0 ? -x : x; }
}
