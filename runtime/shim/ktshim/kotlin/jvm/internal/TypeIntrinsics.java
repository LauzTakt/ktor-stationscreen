package ktshim.kotlin.jvm.internal;

public class TypeIntrinsics {
    public static Object asMutableList(Object o) { return o; }
    public static Object asMutableCollection(Object o) { return o; }
    public static Object asMutableIterable(Object o) { return o; }
    public static Object asMutableIterator(Object o) { return o; }
    public static Object asMutableMap(Object o) { return o; }
    public static Object asMutableSet(Object o) { return o; }
    public static Object asMutableMapEntry(Object o) { return o; }
    public static boolean isMutableList(Object o) { return o instanceof ktshim.java.util.List; }
    public static boolean isMutableCollection(Object o) { return o instanceof ktshim.java.util.Collection; }
    public static boolean isMutableMap(Object o) { return o instanceof ktshim.java.util.Map; }
    public static boolean isMutableSet(Object o) { return o instanceof ktshim.java.util.Set; }
    public static Object beforeCheckcastToFunctionOfArity(Object o, int arity) { return o; }
    public static boolean isFunctionOfArity(Object o, int arity) { return o instanceof FunctionBase && ((FunctionBase) o).getArity() == arity; }
}
