package ktshim.kotlin.jvm.internal;

public class Ref {
    public static final class IntRef {
        public int element;
        public IntRef() {}
        public String toString() { return String.valueOf(element); }
    }
    public static final class LongRef {
        public long element;
        public LongRef() {}
        public String toString() { return String.valueOf(element); }
    }
    public static final class FloatRef {
        public float element;
        public FloatRef() {}
        public String toString() { return String.valueOf(element); }
    }
    public static final class DoubleRef {
        public double element;
        public DoubleRef() {}
        public String toString() { return String.valueOf(element); }
    }
    public static final class BooleanRef {
        public boolean element;
        public BooleanRef() {}
        public String toString() { return String.valueOf(element); }
    }
    public static final class CharRef {
        public char element;
        public CharRef() {}
        public String toString() { return String.valueOf(element); }
    }
    public static final class ByteRef {
        public byte element;
        public ByteRef() {}
        public String toString() { return String.valueOf(element); }
    }
    public static final class ShortRef {
        public short element;
        public ShortRef() {}
        public String toString() { return String.valueOf(element); }
    }
    public static final class ObjectRef {
        public Object element;
        public ObjectRef() {}
        public String toString() { return String.valueOf(element); }
    }
}
