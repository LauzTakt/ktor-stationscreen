package ktshim.kotlin.ranges;

import ktshim.kotlin.random.Random;

public final class RangesKt {
    private RangesKt() {}
    public static IntProgression step(IntProgression p, int step) {
        if (step <= 0) throw new IllegalArgumentException("Step must be positive, was: " + step + ".");
        return new IntProgression(p.first, p.last, p.step > 0 ? step : -step);
    }
    public static IntProgression downTo(int from, int to) { return new IntProgression(from, to, -1); }
    public static IntRange until(int from, int to) { return to <= Integer.MIN_VALUE ? new IntRange(0, -1) : new IntRange(from, to - 1); }
    public static IntProgression reversed(IntProgression p) { return new IntProgression(p.last, p.first, -p.step); }
    public static int random(IntRange r, Random rnd) {
        if (r.isEmpty()) throw new IllegalArgumentException("Cannot get random in empty range: " + r);
        return rnd.nextInt(r.first, r.last + 1);
    }
    public static int coerceIn(int v, int lo, int hi) {
        if (lo > hi) throw new IllegalArgumentException("Cannot coerce value to an empty range: maximum " + hi + " is less than minimum " + lo + ".");
        return v < lo ? lo : (v > hi ? hi : v);
    }
    public static long coerceIn(long v, long lo, long hi) { return v < lo ? lo : (v > hi ? hi : v); }
    public static float coerceIn(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
    public static double coerceIn(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }
    public static int coerceIn(int v, IntRange r) { return coerceIn(v, r.first, r.last); }
    public static int coerceAtLeast(int v, int lo) { return v < lo ? lo : v; }
    public static long coerceAtLeast(long v, long lo) { return v < lo ? lo : v; }
    public static float coerceAtLeast(float v, float lo) { return v < lo ? lo : v; }
    public static double coerceAtLeast(double v, double lo) { return v < lo ? lo : v; }
    public static int coerceAtMost(int v, int hi) { return v > hi ? hi : v; }
    public static long coerceAtMost(long v, long hi) { return v > hi ? hi : v; }
    public static float coerceAtMost(float v, float hi) { return v > hi ? hi : v; }
    public static double coerceAtMost(double v, double hi) { return v > hi ? hi : v; }
}
