package ktshim.java.lang;

import ktshim.ktvm.Native;

public final class Math {
    public static final double PI = 3.141592653589793;
    public static final double E = 2.718281828459045;

    public static double sqrt(double x) { return Native.mathD(0, x); }
    public static double sin(double x) { return Native.mathD(1, x); }
    public static double cos(double x) { return Native.mathD(2, x); }
    public static double tan(double x) { return Native.mathD(3, x); }
    public static double asin(double x) { return Native.mathD(4, x); }
    public static double acos(double x) { return Native.mathD(5, x); }
    public static double atan(double x) { return Native.mathD(6, x); }
    public static double exp(double x) { return Native.mathD(7, x); }
    public static double log(double x) { return Native.mathD(8, x); }
    public static double log10(double x) { return Native.mathD(9, x); }
    public static double floor(double x) { return Native.mathD(10, x); }
    public static double ceil(double x) { return Native.mathD(11, x); }
    public static double rint(double x) { return Native.mathD(12, x); }
    public static double cbrt(double x) { return Native.mathD(13, x); }
    public static double sinh(double x) { return Native.mathD(14, x); }
    public static double cosh(double x) { return Native.mathD(15, x); }
    public static double tanh(double x) { return Native.mathD(16, x); }
    public static double log1p(double x) { return log(1 + x); }
    public static double expm1(double x) { return exp(x) - 1; }
    public static double pow(double x, double y) { return Native.mathD2(0, x, y); }
    public static double atan2(double y, double x) { return Native.mathD2(1, y, x); }
    public static double hypot(double x, double y) { return Native.mathD2(2, x, y); }
    public static double IEEEremainder(double x, double y) { return Native.mathD2(3, x, y); }
    public static double toRadians(double d) { return d / 180.0 * PI; }
    public static double toDegrees(double r) { return r * 180.0 / PI; }
    public static double random() { return (Native.randomInt() >>> 8) / 16777216.0; }

    public static int abs(int a) { return a < 0 ? -a : a; }
    public static long abs(long a) { return a < 0 ? -a : a; }
    public static float abs(float a) { return a <= 0.0f ? 0.0f - a : a; }
    public static double abs(double a) { return a <= 0.0 ? 0.0 - a : a; }
    public static int max(int a, int b) { return a >= b ? a : b; }
    public static long max(long a, long b) { return a >= b ? a : b; }
    public static float max(float a, float b) { return a != a ? a : (a >= b ? a : b); }
    public static double max(double a, double b) { return a != a ? a : (a >= b ? a : b); }
    public static int min(int a, int b) { return a <= b ? a : b; }
    public static long min(long a, long b) { return a <= b ? a : b; }
    public static float min(float a, float b) { return a != a ? a : (a <= b ? a : b); }
    public static double min(double a, double b) { return a != a ? a : (a <= b ? a : b); }
    public static int round(float a) { return (int) floor(a + 0.5); }
    public static long round(double a) { return (long) floor(a + 0.5); }
    public static float signum(float a) { return a > 0 ? 1.0f : (a < 0 ? -1.0f : a); }
    public static double signum(double a) { return a > 0 ? 1.0 : (a < 0 ? -1.0 : a); }
    public static int floorDiv(int a, int b) { int q = a / b; if ((a % b != 0) && ((a ^ b) < 0)) q--; return q; }
    public static int floorMod(int a, int b) { int m = a % b; if (m != 0 && ((m ^ b) < 0)) m += b; return m; }
    public static long floorDiv(long a, long b) { long q = a / b; if ((a % b != 0) && ((a ^ b) < 0)) q--; return q; }
    public static long floorMod(long a, long b) { long m = a % b; if (m != 0 && ((m ^ b) < 0)) m += b; return m; }
    public static int addExact(int a, int b) { return a + b; }
    public static int multiplyExact(int a, int b) { return a * b; }
}
