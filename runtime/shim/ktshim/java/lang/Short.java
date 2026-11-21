package ktshim.java.lang;

import ktshim.ktvm.Native;

public final class Short extends Number implements Comparable {
    private final short value;
    public Short(short v) { value = v; }
    public static Short valueOf(short v) { return new Short(v); }
    public int intValue() { return value; }
    public long longValue() { return value; }
    public float floatValue() { return value; }
    public double doubleValue() { return value; }
    public short shortValue() { return value; }
    public String toString() { return Native.intToString(value); }
    public int hashCode() { return value; }
    public static int hashCode(short v) { return v; }
    public boolean equals(Object o) { return o instanceof Short && ((Short) o).value == value; }
    public int compareTo(Object o) { return value - ((Short) o).value; }
    public static int compare(short a, short b) { return a - b; }
}
