package ktshim.java.util;

/**
 * Insertion-ordered hash map (open addressing index over dense entry arrays).
 * Also serves as LinkedHashMap, which is what Kotlin's mutableMapOf() creates.
 */
public class HashMap implements Map {
    private static final Object TOMB = new Object();
    Object[] keys, vals;
    int[] hashes;
    int[] table; // entry index + 1, 0 = empty
    int used, size;

    public HashMap() { init(8); }
    public HashMap(int capacity) { init(capacity < 4 ? 4 : capacity); }
    public HashMap(int capacity, float load) { this(capacity); }
    public HashMap(Map m) { this(m.size()); putAll(m); }

    private void init(int cap) {
        keys = new Object[cap];
        vals = new Object[cap];
        hashes = new int[cap];
        int t = 8;
        while (t < cap * 2) t <<= 1;
        table = new int[t];
        used = 0;
        size = 0;
    }

    static int hash(Object k) {
        int h = k == null ? 0 : k.hashCode();
        return h ^ (h >>> 16);
    }

    private int find(Object k, int h) {
        int mask = table.length - 1;
        int i = h & mask;
        while (true) {
            int e = table[i];
            if (e == 0) return -1;
            e--;
            Object ek = keys[e];
            if (hashes[e] == h && ek != TOMB && (ek == k || (k != null && k.equals(ek)))) return e;
            i = (i + 1) & mask;
        }
    }

    private void rehash() {
        Object[] ok = keys, ov = vals;
        int n = used;
        init(size * 2 < 8 ? 8 : size * 2);
        for (int i = 0; i < n; i++) if (ok[i] != TOMB) put(ok[i], ov[i]);
    }

    public int size() { return size; }
    public boolean isEmpty() { return size == 0; }
    public Object get(Object k) { int e = find(k, hash(k)); return e < 0 ? null : vals[e]; }
    public boolean containsKey(Object k) { return find(k, hash(k)) >= 0; }
    public boolean containsValue(Object v) {
        for (int i = 0; i < used; i++) if (keys[i] != TOMB && ArrayList.eq(v, vals[i])) return true;
        return false;
    }
    public Object put(Object k, Object v) {
        int h = hash(k);
        int e = find(k, h);
        if (e >= 0) { Object old = vals[e]; vals[e] = v; return old; }
        if (used == keys.length) { rehash(); }
        e = used++;
        keys[e] = k; vals[e] = v; hashes[e] = h;
        int mask = table.length - 1;
        int i = h & mask;
        while (table[i] != 0) i = (i + 1) & mask;
        table[i] = e + 1;
        size++;
        return null;
    }
    public Object remove(Object k) {
        int e = find(k, hash(k));
        if (e < 0) return null;
        Object old = vals[e];
        keys[e] = TOMB;
        vals[e] = null;
        size--;
        return old;
    }
    public void clear() { init(8); }
    public void putAll(Map m) {
        Iterator it = m.entrySet().iterator();
        while (it.hasNext()) { Map.Entry en = (Map.Entry) it.next(); put(en.getKey(), en.getValue()); }
    }

    // views are snapshots (enough for iteration, which is how Kotlin uses them)
    public Set keySet() {
        Snapshot s = new Snapshot(size);
        for (int i = 0; i < used; i++) if (keys[i] != TOMB) s.add(keys[i]);
        return s;
    }
    public Collection values() {
        ArrayList s = new ArrayList(size);
        for (int i = 0; i < used; i++) if (keys[i] != TOMB) s.add(vals[i]);
        return s;
    }
    public Set entrySet() {
        Snapshot s = new Snapshot(size);
        for (int i = 0; i < used; i++) if (keys[i] != TOMB) s.add(new Node(this, keys[i], vals[i]));
        return s;
    }

    public boolean equals(Object o) {
        if (!(o instanceof Map)) return false;
        Map m = (Map) o;
        if (m.size() != size) return false;
        for (int i = 0; i < used; i++)
            if (keys[i] != TOMB && (!m.containsKey(keys[i]) || !ArrayList.eq(vals[i], m.get(keys[i])))) return false;
        return true;
    }
    public int hashCode() {
        int h = 0;
        for (int i = 0; i < used; i++)
            if (keys[i] != TOMB) h += Objects.hashCode(keys[i]) ^ Objects.hashCode(vals[i]);
        return h;
    }
    public String toString() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (int i = 0; i < used; i++) {
            if (keys[i] == TOMB) continue;
            if (!first) sb.append(", ");
            first = false;
            sb.append(keys[i]).append('=').append(vals[i]);
        }
        return sb.append('}').toString();
    }

    static final class Snapshot extends ArrayList implements Set {
        Snapshot(int n) { super(n); }
    }

    static final class Node implements Map.Entry {
        final HashMap map;
        final Object key;
        Object value;
        Node(HashMap m, Object k, Object v) { map = m; key = k; value = v; }
        public Object getKey() { return key; }
        public Object getValue() { return value; }
        public Object setValue(Object v) { Object o = value; value = v; map.put(key, v); return o; }
        public String toString() { return String.valueOf(key) + "=" + String.valueOf(value); }
        public boolean equals(Object o) {
            if (!(o instanceof Map.Entry)) return false;
            Map.Entry e = (Map.Entry) o;
            return ArrayList.eq(key, e.getKey()) && ArrayList.eq(value, e.getValue());
        }
        public int hashCode() { return Objects.hashCode(key) ^ Objects.hashCode(value); }
    }
}
