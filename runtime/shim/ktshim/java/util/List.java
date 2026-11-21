package ktshim.java.util;

public interface List extends Collection {
    Object get(int i);
    Object set(int i, Object o);
    void add(int i, Object o);
    Object remove(int i);
    int indexOf(Object o);
    int lastIndexOf(Object o);
    List subList(int from, int to);
    ListIterator listIterator();
    default void sort(Comparator c) { Collections.sort(this, c); }
}
