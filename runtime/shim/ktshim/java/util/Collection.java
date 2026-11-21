package ktshim.java.util;

public interface Collection extends ktshim.java.lang.Iterable {
    int size();
    boolean isEmpty();
    boolean contains(Object o);
    boolean add(Object o);
    boolean remove(Object o);
    boolean addAll(Collection c);
    boolean containsAll(Collection c);
    boolean removeAll(Collection c);
    void clear();
    Object[] toArray();
}
