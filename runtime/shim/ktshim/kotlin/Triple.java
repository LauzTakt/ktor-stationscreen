package ktshim.kotlin;

public final class Triple {
    private final Object first, second, third;
    public Triple(Object a, Object b, Object c) { first = a; second = b; third = c; }
    public Object getFirst() { return first; }
    public Object getSecond() { return second; }
    public Object getThird() { return third; }
    public Object component1() { return first; }
    public Object component2() { return second; }
    public Object component3() { return third; }
    public String toString() { return "(" + first + ", " + second + ", " + third + ")"; }
}
