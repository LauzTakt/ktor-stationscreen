package ktshim.java.lang;

import ktshim.java.io.PrintStream;
import ktshim.ktvm.Native;

public final class System {
    public static final PrintStream out = new PrintStream();
    public static final PrintStream err = out;

    public static long currentTimeMillis() { return Native.millis(); }
    public static long nanoTime() { return Native.nanos(); }
    public static void arraycopy(Object src, int sp, Object dst, int dp, int n) { Native.arraycopy(src, sp, dst, dp, n); }
    public static int identityHashCode(Object o) { return Native.identityHash(o); }
    public static void gc() { Native.gc(); }
    public static String lineSeparator() { return "\n"; }
}
