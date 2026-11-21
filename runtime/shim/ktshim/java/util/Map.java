package ktshim.java.util;

public interface Map {
    int size();
    boolean isEmpty();
    Object get(Object key);
    Object put(Object key, Object value);
    Object remove(Object key);
    boolean containsKey(Object key);
    boolean containsValue(Object value);
    void clear();
    void putAll(Map m);
    Set keySet();
    Collection values();
    Set entrySet();
    default Object getOrDefault(Object key, Object def) { Object v = get(key); return v != null || containsKey(key) ? v : def; }
    default Object putIfAbsent(Object key, Object value) { Object v = get(key); if (v == null) put(key, value); return v; }

    interface Entry {
        Object getKey();
        Object getValue();
        Object setValue(Object v);
    }
}
