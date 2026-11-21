package ktshim.java.lang;

import ktshim.ktvm.Native;

public final class Byte extends Number implements Comparable {
    private final byte value;
    public Byte(byte v) { value = v; }
    public static Byte valueOf(byte v) { return new Byte(v); }
    public int intValue() { return value; }
    public long longValue() { return value; }
    public float floatValue() { return value; }
    public double doubleValue() { return value; }
    public byte byteValue() { return value; }
    public String toString() { return Native.intToString(value); }
    public int hashCode() { return value; }
    public static int hashCode(byte v) { return v; }
    public boolean equals(Object o) { return o instanceof Byte && ((Byte) o).value == value; }
    public int compareTo(Object o) { return value - ((Byte) o).value; }
    public static int compare(byte a, byte b) { return a - b; }
}
