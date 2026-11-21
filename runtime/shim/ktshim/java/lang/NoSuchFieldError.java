package ktshim.java.lang;

public class NoSuchFieldError extends Error {
    public NoSuchFieldError() { super(); }
    public NoSuchFieldError(String message) { super(message); }
    public NoSuchFieldError(String message, Throwable cause) { super(message, cause); }
    public NoSuchFieldError(Throwable cause) { super(cause); }
}
