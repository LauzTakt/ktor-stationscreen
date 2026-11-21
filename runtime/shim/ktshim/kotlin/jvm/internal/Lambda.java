package ktshim.kotlin.jvm.internal;

public abstract class Lambda implements FunctionBase {
    private final int arity;
    public Lambda(int arity) { this.arity = arity; }
    public int getArity() { return arity; }
    public String toString() { return "(lambda)"; }
}
