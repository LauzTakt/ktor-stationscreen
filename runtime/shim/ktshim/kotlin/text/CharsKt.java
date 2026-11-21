package ktshim.kotlin.text;

public final class CharsKt {
    private CharsKt() {}
    public static int digitToInt(char c) {
        int d = Character.digit(c, 10);
        if (d < 0) throw new IllegalArgumentException("Char " + c + " is not a decimal digit");
        return d;
    }
    public static int digitToInt(char c, int radix) {
        int d = Character.digit(c, radix);
        if (d < 0) throw new IllegalArgumentException("Char " + c + " is not a digit in the given radix=" + radix);
        return d;
    }
    public static Integer digitToIntOrNull(char c) { int d = Character.digit(c, 10); return d < 0 ? null : Integer.valueOf(d); }
    public static boolean isWhitespace(char c) { return Character.isWhitespace(c); }
    public static int checkRadix(int radix) {
        if (radix < 2 || radix > 36) throw new IllegalArgumentException("radix " + radix + " was not in valid range 2..36");
        return radix;
    }
    public static String titlecase(char c) { return String.valueOf(Character.toUpperCase(c)); }
    public static char titlecaseChar(char c) { return Character.toUpperCase(c); }
    public static boolean equals(char a, char b, boolean ignoreCase) {
        return a == b || (ignoreCase && Character.toLowerCase(a) == Character.toLowerCase(b));
    }
}
