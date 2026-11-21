package ktshim.kotlin;

import ktshim.java.util.ArrayList;
import ktshim.java.util.List;

public final class TuplesKt {
    public static Pair to(Object a, Object b) { return new Pair(a, b); }
    public static List toList(Pair p) { ArrayList l = new ArrayList(2); l.add(p.getFirst()); l.add(p.getSecond()); return l; }
}
