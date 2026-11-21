package ktshim.java.lang;

import ktshim.ktvm.Native;

public class Thread {
    public static void sleep(long ms) { Native.sleep(ms); }
    public static void yield() { Native.sleep(0); }
}
