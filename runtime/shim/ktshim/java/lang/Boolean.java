package ktshim.java.lang;

public final class Boolean implements Comparable {
    public static final Boolean TRUE = new Boolean(true);
    public static final Boolean FALSE = new Boolean(false);
    private final boolean value;

    public Boolean(boolean v) { value = v; }
    public static Boolean valueOf(boolean v) { return v ? TRUE : FALSE; }
    public static Boolean valueOf(String s) { return valueOf(parseBoolean(s)); }
    public static boolean parseBoolean(String s) { return s != null && s.equalsIgnoreCase("true"); }
    public boolean booleanValue() { return value; }
    public String toString() { return value ? "true" : "false"; }
    public static String toString(boolean v) { return v ? "true" : "false"; }
    public int hashCode() { return value ? 1231 : 1237; }
    public static int hashCode(boolean v) { return v ? 1231 : 1237; }
    public boolean equals(Object o) { return o instanceof Boolean && ((Boolean) o).value == value; }
    public int compareTo(Object o) { return compare(value, ((Boolean) o).value); }
    public static int compare(boolean a, boolean b) { return a == b ? 0 : (a ? 1 : -1); }
    public static boolean logicalAnd(boolean a, boolean b) { return a && b; }
    public static boolean logicalOr(boolean a, boolean b) { return a || b; }
    public static boolean logicalXor(boolean a, boolean b) { return a ^ b; }
}
