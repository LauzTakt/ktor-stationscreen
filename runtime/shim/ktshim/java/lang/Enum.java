package ktshim.java.lang;

import ktshim.ktvm.Native;

public abstract class Enum implements Comparable {
    private final String name;
    private final int ordinal;

    protected Enum(String name, int ordinal) { this.name = name; this.ordinal = ordinal; }
    public final String name() { return name; }
    public final int ordinal() { return ordinal; }
    public String toString() { return name; }
    public final boolean equals(Object o) { return this == o; }
    public final int hashCode() { return Native.identityHash(this); }
    public final int compareTo(Object o) { return ordinal - ((Enum) o).ordinal; }
    public static Enum valueOf(Class c, String name) {
        Native.raise(new java.lang.UnsupportedOperationException("Enum.valueOf(\"" + name + "\") is not supported; use entries.first { it.name == x }"));
        return null;
    }
}
