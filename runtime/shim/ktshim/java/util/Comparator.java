package ktshim.java.util;

public interface Comparator {
    int compare(Object a, Object b);
    default Comparator reversed() { final Comparator self = this; return new Comparator() { public int compare(Object a, Object b) { return self.compare(b, a); } }; }
}
