package ktshim.java.util;

public class ArrayList implements List, RandomAccess {
    Object[] data;
    int size;

    public ArrayList() { data = new Object[8]; }
    public ArrayList(int capacity) { data = new Object[capacity < 4 ? 4 : capacity]; }
    public ArrayList(Collection c) { this(c.size()); addAll(c); }

    static boolean eq(Object a, Object b) { return a == b || (a != null && a.equals(b)); }

    private void grow(int n) {
        if (n > data.length) {
            int c = data.length * 2;
            if (c < n) c = n;
            Object[] nd = new Object[c];
            System.arraycopy(data, 0, nd, 0, size);
            data = nd;
        }
    }
    private void check(int i) {
        if (i < 0 || i >= size) throw new IndexOutOfBoundsException("Index " + i + " out of bounds for length " + size);
    }

    public int size() { return size; }
    public boolean isEmpty() { return size == 0; }
    public Object get(int i) { check(i); return data[i]; }
    public Object set(int i, Object o) { check(i); Object old = data[i]; data[i] = o; return old; }
    public boolean add(Object o) { grow(size + 1); data[size++] = o; return true; }
    public void add(int i, Object o) {
        if (i < 0 || i > size) throw new IndexOutOfBoundsException("Index " + i + " out of bounds for length " + size);
        grow(size + 1);
        System.arraycopy(data, i, data, i + 1, size - i);
        data[i] = o;
        size++;
    }
    public Object remove(int i) {
        check(i);
        Object old = data[i];
        System.arraycopy(data, i + 1, data, i, size - i - 1);
        data[--size] = null;
        return old;
    }
    public boolean remove(Object o) {
        int i = indexOf(o);
        if (i < 0) return false;
        remove(i);
        return true;
    }
    public int indexOf(Object o) { for (int i = 0; i < size; i++) if (eq(o, data[i])) return i; return -1; }
    public int lastIndexOf(Object o) { for (int i = size - 1; i >= 0; i--) if (eq(o, data[i])) return i; return -1; }
    public boolean contains(Object o) { return indexOf(o) >= 0; }
    public boolean addAll(Collection c) {
        Iterator it = c.iterator();
        boolean ch = false;
        while (it.hasNext()) { add(it.next()); ch = true; }
        return ch;
    }
    public boolean addAll(int index, Collection c) {
        Iterator it = c.iterator();
        int i = index;
        while (it.hasNext()) add(i++, it.next());
        return i != index;
    }
    public boolean containsAll(Collection c) {
        Iterator it = c.iterator();
        while (it.hasNext()) if (!contains(it.next())) return false;
        return true;
    }
    public boolean removeAll(Collection c) {
        boolean ch = false;
        for (int i = size - 1; i >= 0; i--) if (c.contains(data[i])) { remove(i); ch = true; }
        return ch;
    }
    public boolean retainAll(Collection c) {
        boolean ch = false;
        for (int i = size - 1; i >= 0; i--) if (!c.contains(data[i])) { remove(i); ch = true; }
        return ch;
    }
    public void clear() { for (int i = 0; i < size; i++) data[i] = null; size = 0; }
    public Object[] toArray() { Object[] r = new Object[size]; System.arraycopy(data, 0, r, 0, size); return r; }
    public List subList(int from, int to) {
        ArrayList r = new ArrayList(to - from);
        for (int i = from; i < to; i++) r.add(get(i));
        return r;
    }
    public Iterator iterator() { return new Itr(this, 0); }
    public ListIterator listIterator() { return new Itr(this, 0); }
    public ListIterator listIterator(int i) { return new Itr(this, i); }

    public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof List)) return false;
        List l = (List) o;
        if (l.size() != size) return false;
        for (int i = 0; i < size; i++) if (!eq(data[i], l.get(i))) return false;
        return true;
    }
    public int hashCode() {
        int h = 1;
        for (int i = 0; i < size; i++) h = 31 * h + (data[i] == null ? 0 : data[i].hashCode());
        return h;
    }
    public String toString() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < size; i++) {
            if (i > 0) sb.append(", ");
            sb.append(data[i] == this ? "(this Collection)" : data[i]);
        }
        return sb.append(']').toString();
    }

    static final class Itr implements ListIterator {
        final ArrayList list;
        int cursor, last = -1;
        Itr(ArrayList l, int start) { list = l; cursor = start; }
        public boolean hasNext() { return cursor < list.size; }
        public Object next() {
            if (cursor >= list.size) throw new NoSuchElementException();
            last = cursor;
            return list.data[cursor++];
        }
        public boolean hasPrevious() { return cursor > 0; }
        public Object previous() { last = --cursor; return list.get(cursor); }
        public int nextIndex() { return cursor; }
        public int previousIndex() { return cursor - 1; }
        public void remove() {
            if (last < 0) throw new IllegalStateException();
            list.remove(last);
            cursor = last;
            last = -1;
        }
        public void set(Object o) { list.set(last, o); }
        public void add(Object o) { list.add(cursor++, o); last = -1; }
    }
}
