package ktshim.kotlin.jvm.internal;

import ktshim.kotlin.UninitializedPropertyAccessException;

public class Intrinsics {
    public static void checkNotNull(Object o) { if (o == null) throw new NullPointerException(); }
    public static void checkNotNull(Object o, String message) { if (o == null) throw new NullPointerException(message); }
    public static void checkNotNullParameter(Object o, String name) {
        if (o == null) throw new NullPointerException("Parameter specified as non-null is null: " + name);
    }
    public static void checkNotNullExpressionValue(Object o, String expr) {
        if (o == null) throw new NullPointerException(expr + " must not be null");
    }
    public static void checkParameterIsNotNull(Object o, String name) { checkNotNullParameter(o, name); }
    public static void checkExpressionValueIsNotNull(Object o, String expr) { checkNotNullExpressionValue(o, expr); }
    public static void checkReturnedValueIsNotNull(Object o, String cls, String method) {
        if (o == null) throw new IllegalStateException("Method specified as non-null returned null: " + cls + "." + method);
    }
    public static void checkFieldIsNotNull(Object o, String cls, String field) {
        if (o == null) throw new IllegalStateException("Field specified as non-null is null: " + cls + "." + field);
    }
    public static void throwNpe() { throw new NullPointerException(); }
    public static void throwJavaNpe() { throw new NullPointerException(); }
    public static void throwJavaNpe(String m) { throw new NullPointerException(m); }
    public static void throwUninitializedProperty(String name) { throw new UninitializedPropertyAccessException(name); }
    public static void throwUninitializedPropertyAccessException(String name) {
        throw new UninitializedPropertyAccessException("lateinit property " + name + " has not been initialized");
    }
    public static void throwAssert() { throw new AssertionError(); }
    public static void throwIllegalArgument() { throw new IllegalArgumentException(); }
    public static void throwIllegalState() { throw new IllegalStateException(); }
    public static void throwUndefinedForReified() { throw new UnsupportedOperationException("reified"); }
    public static void checkHasClass(String name) {}
    public static void needClassReification() {}
    public static void reifiedOperationMarker(int id, String name) {}

    public static boolean areEqual(Object a, Object b) { return a == null ? b == null : a.equals(b); }
    public static boolean areEqual(Double a, Double b) { return a == null ? b == null : (b != null && a.doubleValue() == b.doubleValue()); }
    public static boolean areEqual(Double a, double b) { return a != null && a.doubleValue() == b; }
    public static boolean areEqual(double a, Double b) { return b != null && a == b.doubleValue(); }
    public static boolean areEqual(Float a, Float b) { return a == null ? b == null : (b != null && a.floatValue() == b.floatValue()); }
    public static boolean areEqual(Float a, float b) { return a != null && a.floatValue() == b; }
    public static boolean areEqual(float a, Float b) { return b != null && a == b.floatValue(); }
    public static String stringPlus(String a, Object b) { return a + b; }
    public static int compare(int a, int b) { return a < b ? -1 : (a == b ? 0 : 1); }
    public static int compare(long a, long b) { return a < b ? -1 : (a == b ? 0 : 1); }
}
