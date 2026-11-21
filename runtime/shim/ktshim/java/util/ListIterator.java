package ktshim.java.util;

public interface ListIterator extends Iterator {
    boolean hasPrevious();
    Object previous();
    int nextIndex();
    int previousIndex();
    void set(Object o);
    void add(Object o);
}
