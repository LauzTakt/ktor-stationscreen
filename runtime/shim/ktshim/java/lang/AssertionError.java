package ktshim.java.lang;

public class AssertionError extends Error {
    public AssertionError() { super(); }
    public AssertionError(Object detail) { super(String.valueOf(detail)); }
    public AssertionError(String message, Throwable cause) { super(message, cause); }
}
