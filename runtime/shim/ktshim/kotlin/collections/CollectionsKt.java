package ktshim.kotlin.collections;

import ktshim.java.util.*;
import ktshim.kotlin.jvm.functions.Function1;
import ktshim.kotlin.ranges.IntRange;
import ktshim.kotlin.random.Random;

public final class CollectionsKt {
    private CollectionsKt() {}

    // ---- builders
    public static List listOf(Object[] a) { return Arrays.asList(a); }
    public static List listOf(Object a) { ArrayList l = new ArrayList(1); l.add(a); return l; }
    public static List listOfNotNull(Object a) { ArrayList l = new ArrayList(1); if (a != null) l.add(a); return l; }
    public static List listOfNotNull(Object[] a) { ArrayList l = new ArrayList(a.length); for (int i = 0; i < a.length; i++) if (a[i] != null) l.add(a[i]); return l; }
    public static List emptyList() { return new ArrayList(0); }
    public static List mutableListOf(Object[] a) { return Arrays.asList(a); }
    public static ArrayList arrayListOf(Object[] a) { return (ArrayList) Arrays.asList(a); }
    public static List optimizeReadOnlyList(List l) { return l; }
    public static int collectionSizeOrDefault(ktshim.java.lang.Iterable it, int def) { return it instanceof Collection ? ((Collection) it).size() : def; }
    public static void throwIndexOverflow() { throw new ArithmeticException("Index overflow has happened."); }
    public static void throwCountOverflow() { throw new ArithmeticException("Count overflow has happened."); }

    // ---- access
    public static int getLastIndex(List l) { return l.size() - 1; }
    public static IntRange getIndices(Collection c) { return new IntRange(0, c.size() - 1); }
    public static Object first(List l) { if (l.isEmpty()) throw new NoSuchElementException("List is empty."); return l.get(0); }
    public static Object first(ktshim.java.lang.Iterable it) {
        if (it instanceof List) return first((List) it);
        Iterator i = it.iterator();
        if (!i.hasNext()) throw new NoSuchElementException("Collection is empty.");
        return i.next();
    }
    public static Object last(List l) { if (l.isEmpty()) throw new NoSuchElementException("List is empty."); return l.get(l.size() - 1); }
    public static Object last(ktshim.java.lang.Iterable it) { return last(toList(it)); }
    public static Object firstOrNull(List l) { return l.isEmpty() ? null : l.get(0); }
    public static Object firstOrNull(ktshim.java.lang.Iterable it) { Iterator i = it.iterator(); return i.hasNext() ? i.next() : null; }
    public static Object lastOrNull(List l) { return l.isEmpty() ? null : l.get(l.size() - 1); }
    public static Object single(List l) {
        if (l.size() != 1) throw new IllegalArgumentException(l.isEmpty() ? "List is empty." : "List has more than one element.");
        return l.get(0);
    }
    public static Object getOrNull(List l, int i) { return i >= 0 && i < l.size() ? l.get(i) : null; }
    public static Object elementAt(ktshim.java.lang.Iterable it, int i) { return toList(it).get(i); }
    public static Object random(Collection c, Random r) {
        if (c.isEmpty()) throw new NoSuchElementException("Collection is empty.");
        return toList(c).get(r.nextInt(c.size()));
    }
    public static boolean contains(ktshim.java.lang.Iterable it, Object o) {
        if (it instanceof Collection) return ((Collection) it).contains(o);
        return indexOf(it, o) >= 0;
    }
    public static int indexOf(ktshim.java.lang.Iterable it, Object o) {
        int i = 0;
        Iterator x = it.iterator();
        while (x.hasNext()) { if (Objects.equals(x.next(), o)) return i; i++; }
        return -1;
    }
    public static int indexOf(List l, Object o) { return l.indexOf(o); }
    public static int count(ktshim.java.lang.Iterable it) {
        if (it instanceof Collection) return ((Collection) it).size();
        int n = 0;
        Iterator x = it.iterator();
        while (x.hasNext()) { x.next(); n++; }
        return n;
    }

    // ---- aggregates
    public static int sumOfInt(ktshim.java.lang.Iterable it) {
        int s = 0;
        Iterator x = it.iterator();
        while (x.hasNext()) s += ((Number) x.next()).intValue();
        return s;
    }
    public static long sumOfLong(ktshim.java.lang.Iterable it) {
        long s = 0;
        Iterator x = it.iterator();
        while (x.hasNext()) s += ((Number) x.next()).longValue();
        return s;
    }
    public static double sumOfDouble(ktshim.java.lang.Iterable it) {
        double s = 0;
        Iterator x = it.iterator();
        while (x.hasNext()) s += ((Number) x.next()).doubleValue();
        return s;
    }
    public static float sumOfFloat(ktshim.java.lang.Iterable it) {
        float s = 0;
        Iterator x = it.iterator();
        while (x.hasNext()) s += ((Number) x.next()).floatValue();
        return s;
    }
    public static double averageOfInt(ktshim.java.lang.Iterable it) {
        double s = 0;
        int n = 0;
        Iterator x = it.iterator();
        while (x.hasNext()) { s += ((Number) x.next()).doubleValue(); n++; }
        return n == 0 ? Double.NaN : s / n;
    }
    public static double averageOfDouble(ktshim.java.lang.Iterable it) { return averageOfInt(it); }
    public static double averageOfFloat(ktshim.java.lang.Iterable it) { return averageOfInt(it); }
    public static java.lang.Comparable maxOrNull(ktshim.java.lang.Iterable it) {
        Iterator x = it.iterator();
        if (!x.hasNext()) return null;
        java.lang.Comparable m = (java.lang.Comparable) x.next();
        while (x.hasNext()) { java.lang.Comparable e = (java.lang.Comparable) x.next(); if (m.compareTo(e) < 0) m = e; }
        return m;
    }
    public static java.lang.Comparable minOrNull(ktshim.java.lang.Iterable it) {
        Iterator x = it.iterator();
        if (!x.hasNext()) return null;
        java.lang.Comparable m = (java.lang.Comparable) x.next();
        while (x.hasNext()) { java.lang.Comparable e = (java.lang.Comparable) x.next(); if (m.compareTo(e) > 0) m = e; }
        return m;
    }
    public static java.lang.Comparable maxOrThrow(ktshim.java.lang.Iterable it) {
        java.lang.Comparable m = maxOrNull(it);
        if (m == null) throw new NoSuchElementException();
        return m;
    }
    public static java.lang.Comparable minOrThrow(ktshim.java.lang.Iterable it) {
        java.lang.Comparable m = minOrNull(it);
        if (m == null) throw new NoSuchElementException();
        return m;
    }

    // ---- transforms
    public static List toList(ktshim.java.lang.Iterable it) { return toMutableList(it); }
    public static List toMutableList(ktshim.java.lang.Iterable it) {
        ArrayList l = new ArrayList(collectionSizeOrDefault(it, 10));
        Iterator x = it.iterator();
        while (x.hasNext()) l.add(x.next());
        return l;
    }
    public static List toMutableList(Collection c) { return new ArrayList(c); }
    public static Set toSet(ktshim.java.lang.Iterable it) { return toMutableSet(it); }
    public static Set toMutableSet(ktshim.java.lang.Iterable it) {
        LinkedHashSet s = new LinkedHashSet();
        Iterator x = it.iterator();
        while (x.hasNext()) s.add(x.next());
        return s;
    }
    public static HashSet toHashSet(ktshim.java.lang.Iterable it) { return (HashSet) toMutableSet(it); }
    public static Collection toCollection(ktshim.java.lang.Iterable it, Collection dst) {
        Iterator x = it.iterator();
        while (x.hasNext()) dst.add(x.next());
        return dst;
    }
    public static int[] toIntArray(Collection c) {
        int[] r = new int[c.size()];
        int i = 0;
        Iterator x = c.iterator();
        while (x.hasNext()) r[i++] = ((Number) x.next()).intValue();
        return r;
    }
    public static List reversed(ktshim.java.lang.Iterable it) { List l = toMutableList(it); Collections.reverse(l); return l; }
    public static List asReversed(List l) { return reversed(l); }
    public static List sorted(ktshim.java.lang.Iterable it) { List l = toMutableList(it); Collections.sort(l); return l; }
    public static List sortedDescending(ktshim.java.lang.Iterable it) { List l = sorted(it); Collections.reverse(l); return l; }
    public static List sortedWith(ktshim.java.lang.Iterable it, Comparator c) { List l = toMutableList(it); Collections.sort(l, c); return l; }
    public static void sort(List l) { Collections.sort(l); }
    public static void sortDescending(List l) { Collections.sort(l); Collections.reverse(l); }
    public static void sortWith(List l, Comparator c) { Collections.sort(l, c); }
    public static void reverse(List l) { Collections.reverse(l); }
    public static void shuffle(List l) { Collections.shuffle(l); }
    public static List shuffled(ktshim.java.lang.Iterable it) { List l = toMutableList(it); Collections.shuffle(l); return l; }
    public static List distinct(ktshim.java.lang.Iterable it) { return new ArrayList(toMutableSet(it)); }
    public static List take(ktshim.java.lang.Iterable it, int n) {
        ArrayList l = new ArrayList();
        Iterator x = it.iterator();
        while (n-- > 0 && x.hasNext()) l.add(x.next());
        return l;
    }
    public static List drop(ktshim.java.lang.Iterable it, int n) {
        ArrayList l = new ArrayList();
        Iterator x = it.iterator();
        while (x.hasNext()) { Object o = x.next(); if (n-- <= 0) l.add(o); }
        return l;
    }
    public static List takeLast(List l, int n) { int s = l.size(); return l.subList(Math.max(0, s - n), s); }
    public static List dropLast(List l, int n) { return take(l, Math.max(0, l.size() - n)); }
    public static List plus(Collection c, Object o) { ArrayList l = new ArrayList(c); l.add(o); return l; }
    public static List plus(Collection c, ktshim.java.lang.Iterable other) { ArrayList l = new ArrayList(c); toCollection(other, l); return l; }
    public static List plus(ktshim.java.lang.Iterable c, Object o) { List l = toMutableList(c); l.add(o); return l; }
    public static List minus(ktshim.java.lang.Iterable c, Object o) { List l = toMutableList(c); l.remove(o); return l; }
    public static boolean addAll(Collection c, Object[] a) { boolean ch = false; for (int i = 0; i < a.length; i++) ch |= c.add(a[i]); return ch; }
    public static boolean addAll(Collection c, ktshim.java.lang.Iterable it) { boolean ch = false; Iterator x = it.iterator(); while (x.hasNext()) ch |= c.add(x.next()); return ch; }
    public static Object removeLast(List l) { if (l.isEmpty()) throw new NoSuchElementException("List is empty."); return l.remove(l.size() - 1); }
    public static Object removeFirst(List l) { if (l.isEmpty()) throw new NoSuchElementException("List is empty."); return l.remove(0); }
    public static Object removeLastOrNull(List l) { return l.isEmpty() ? null : l.remove(l.size() - 1); }
    public static Object removeFirstOrNull(List l) { return l.isEmpty() ? null : l.remove(0); }
    public static List chunked(ktshim.java.lang.Iterable it, int size) {
        ArrayList out = new ArrayList();
        ArrayList cur = null;
        Iterator x = it.iterator();
        while (x.hasNext()) {
            if (cur == null || cur.size() == size) { cur = new ArrayList(size); out.add(cur); }
            cur.add(x.next());
        }
        return out;
    }
    public static List zip(ktshim.java.lang.Iterable a, ktshim.java.lang.Iterable b) {
        ArrayList out = new ArrayList();
        Iterator x = a.iterator(), y = b.iterator();
        while (x.hasNext() && y.hasNext()) out.add(new ktshim.kotlin.Pair(x.next(), y.next()));
        return out;
    }

    // ---- joinToString
    public static String joinToString(ktshim.java.lang.Iterable it, java.lang.CharSequence sep, java.lang.CharSequence prefix,
                                      java.lang.CharSequence postfix, int limit, java.lang.CharSequence truncated, Function1 transform) {
        StringBuilder sb = new StringBuilder();
        sb.append(prefix);
        int n = 0;
        Iterator x = it.iterator();
        while (x.hasNext()) {
            Object e = x.next();
            if (++n > 1) sb.append(sep);
            if (limit >= 0 && n > limit) { sb.append(truncated); break; }
            if (transform != null) sb.append((java.lang.CharSequence) transform.invoke(e));
            else if (e instanceof java.lang.CharSequence || e == null) sb.append((java.lang.CharSequence) e);
            else if (e instanceof Character) sb.append(((Character) e).charValue());
            else sb.append(String.valueOf(e));
        }
        sb.append(postfix);
        return sb.toString();
    }
    public static String joinToString$default(ktshim.java.lang.Iterable it, java.lang.CharSequence sep, java.lang.CharSequence prefix,
                                              java.lang.CharSequence postfix, int limit, java.lang.CharSequence truncated,
                                              Function1 transform, int mask, Object unused) {
        if ((mask & 1) != 0) sep = ", ";
        if ((mask & 2) != 0) prefix = "";
        if ((mask & 4) != 0) postfix = "";
        if ((mask & 8) != 0) limit = -1;
        if ((mask & 16) != 0) truncated = "...";
        if ((mask & 32) != 0) transform = null;
        return joinToString(it, sep, prefix, postfix, limit, truncated, transform);
    }
}
