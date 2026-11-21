package ktshim.kotlin.internal;

public final class ProgressionUtilKt {
    private static int mod(int a, int b) { int m = a % b; return m >= 0 ? m : m + b; }
    private static int diffMod(int a, int b, int c) { return mod(mod(a, c) - mod(b, c), c); }
    public static int getProgressionLastElement(int start, int end, int step) {
        if (step > 0) return start >= end ? end : end - diffMod(end, start, step);
        if (step < 0) return start <= end ? end : end + diffMod(start, end, -step);
        throw new IllegalArgumentException("Step is zero.");
    }
}
