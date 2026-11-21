package ktshim.java.lang;

import ktshim.ktvm.Native;

public class Throwable {
    String message; // must stay the FIRST field: the VM writes it directly
    Throwable cause;

    public Throwable() {}
    public Throwable(String message) { this.message = message; }
    public Throwable(String message, Throwable cause) { this.message = message; this.cause = cause; }
    public Throwable(Throwable cause) { this.cause = cause; if (cause != null) this.message = cause.toString(); }

    public String getMessage() { return message; }
    public String getLocalizedMessage() { return getMessage(); }
    public Throwable getCause() { return cause; }
    public Throwable initCause(Throwable c) { cause = c; return this; }
    public void addSuppressed(Throwable t) {}
    public Throwable fillInStackTrace() { return this; }

    public String toString() {
        String n = Native.className(this);
        String m = getLocalizedMessage();
        return m == null ? n : n + ": " + m;
    }

    public void printStackTrace() {
        Native.print(toString());
        Native.print("\n");
        if (cause != null) { Native.print("Caused by: "); cause.printStackTrace(); }
    }
}
