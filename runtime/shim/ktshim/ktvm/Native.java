package ktshim.ktvm;

/** Natives implemented in C by the firmware (see tools/natives.py). */
public final class Native {
    private Native() {}
    public static native void print(String s);
    public static native String intToString(int v);
    public static native String longToString(long v);
    public static native String floatToString(float v);
    public static native String doubleToString(double v);
    public static native String charToString(char c);
    public static native String className(Object o);
    public static native int identityHash(Object o);
    public static native void raise(Object throwable);
    public static native void strToBytes(String s, byte[] dst, int off);
    public static native String bytesToString(byte[] b, int off, int len);
    public static native void arraycopy(Object src, int srcPos, Object dst, int dstPos, int len);
    public static native long millis();
    public static native long nanos();
    public static native void sleep(long ms);
    public static native int floatToIntBits(float f);
    public static native float intBitsToFloat(int i);
    public static native long doubleToLongBits(double d);
    public static native double longBitsToDouble(long l);
    public static native double parseDouble(String s);
    public static native double mathD(int op, double x);
    public static native double mathD2(int op, double x, double y);
    public static native String formatDouble(double v, int decimals);
    public static native int randomInt();
    public static native int freeMemory();
    public static native void gc();
}
