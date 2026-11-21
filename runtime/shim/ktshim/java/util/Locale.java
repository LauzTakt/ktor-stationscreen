package ktshim.java.util;

public final class Locale {
    public static final Locale ROOT = new Locale();
    public static final Locale US = ROOT;
    public static final Locale ENGLISH = ROOT;
    public static Locale getDefault() { return ROOT; }
}
