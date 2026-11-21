package ktshim.java.util;

public class HashSet implements Set {
    private static final Object PRESENT = Boolean.TRUE;
    final HashMap map;

    public HashSet() { map = new HashMap(); }
    public HashSet(int capacity) { map = new HashMap(capacity); }
    public HashSet(int capacity, float load) { map = new HashMap(capacity); }
    public HashSet(Collection c) { map = new HashMap(c.size()); addAll(c); }

    public int size() { return map.size(); }
    public boolean isEmpty() { return map.isEmpty(); }
    public boolean contains(Object o) { return map.containsKey(o); }
    public boolean add(Object o) { return map.put(o, PRESENT) == null; }
    public boolean remove(Object o) { return map.remove(o) != null; }
    public void clear() { map.clear(); }
    public Iterator iterator() { return map.keySet().iterator(); }
    public Object[] toArray() { return ((ArrayList) map.keySet()).toArray(); }
    public boolean addAll(Collection c) {
        boolean ch = false;
        Iterator it = c.iterator();
        while (it.hasNext()) ch |= add(it.next());
        return ch;
    }
    public boolean containsAll(Collection c) {
        Iterator it = c.iterator();
        while (it.hasNext()) if (!contains(it.next())) return false;
        return true;
    }
    public boolean removeAll(Collection c) {
        boolean ch = false;
        Iterator it = c.iterator();
        while (it.hasNext()) ch |= remove(it.next());
        return ch;
    }
    public boolean retainAll(Collection c) {
        boolean ch = false;
        Iterator it = map.keySet().iterator();
        while (it.hasNext()) { Object o = it.next(); if (!c.contains(o)) { remove(o); ch = true; } }
        return ch;
    }
    public boolean equals(Object o) {
        if (!(o instanceof Set)) return false;
        Set s = (Set) o;
        return s.size() == size() && containsAll(s);
    }
    public int hashCode() {
        int h = 0;
        Iterator it = iterator();
        while (it.hasNext()) h += Objects.hashCode(it.next());
        return h;
    }
    public String toString() { return map.keySet().toString(); }
}
