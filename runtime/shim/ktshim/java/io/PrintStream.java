package ktshim.java.io;

import ktshim.ktvm.Native;

public class PrintStream {
    public void print(String s) { Native.print(s == null ? "null" : s); }
    public void print(Object o) { Native.print(String.valueOf(o)); }
    public void print(int v) { Native.print(Native.intToString(v)); }
    public void print(long v) { Native.print(Native.longToString(v)); }
    public void print(float v) { Native.print(Native.floatToString(v)); }
    public void print(double v) { Native.print(Native.doubleToString(v)); }
    public void print(char c) { Native.print(Native.charToString(c)); }
    public void print(boolean b) { Native.print(b ? "true" : "false"); }
    public void print(char[] cs) { Native.print(String.valueOf(cs)); }
    public void println() { Native.print("\n"); }
    public void println(String s) { print(s); println(); }
    public void println(Object o) { print(o); println(); }
    public void println(int v) { print(v); println(); }
    public void println(long v) { print(v); println(); }
    public void println(float v) { print(v); println(); }
    public void println(double v) { print(v); println(); }
    public void println(char c) { print(c); println(); }
    public void println(boolean b) { print(b); println(); }
    public void println(char[] cs) { print(cs); println(); }
    public void flush() {}
}
