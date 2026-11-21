package ktshim.java.lang;

import ktshim.ktvm.Native;

/** Byte-buffer StringBuilder (UTF-8). */
public final class StringBuilder implements CharSequence {
    private byte[] buf;
    private int len;

    public StringBuilder() { buf = new byte[16]; }
    public StringBuilder(int capacity) { buf = new byte[capacity < 1 ? 1 : capacity]; }
    public StringBuilder(String s) { buf = new byte[s.length() + 16]; append(s); }
    public StringBuilder(java.lang.CharSequence s) { this(s.toString()); }

    private void ensure(int n) {
        if (n > buf.length) {
            int c = buf.length * 2 + 2;
            if (c < n) c = n;
            byte[] nb = new byte[c];
            Native.arraycopy(buf, 0, nb, 0, len);
            buf = nb;
        }
    }

    public StringBuilder append(String s) {
        if (s == null) s = "null";
        int n = s.length();
        ensure(len + n);
        Native.strToBytes(s, buf, len);
        len += n;
        return this;
    }
    public StringBuilder append(Object o) { return append(o == null ? "null" : o.toString()); }
    public StringBuilder append(java.lang.CharSequence s) { return append(s == null ? "null" : s.toString()); }
    public StringBuilder append(java.lang.CharSequence s, int start, int end) { return append(s.toString().substring(start, end)); }
    public StringBuilder append(StringBuilder sb) { return append(sb == null ? "null" : sb.toString()); }
    public StringBuilder append(int v) { return append(Native.intToString(v)); }
    public StringBuilder append(long v) { return append(Native.longToString(v)); }
    public StringBuilder append(float v) { return append(Native.floatToString(v)); }
    public StringBuilder append(double v) { return append(Native.doubleToString(v)); }
    public StringBuilder append(boolean v) { return append(v ? "true" : "false"); }
    public StringBuilder append(char c) {
        if (c < 0x80) { ensure(len + 1); buf[len++] = (byte) c; return this; }
        return append(Native.charToString(c));
    }
    public StringBuilder append(char[] cs) { return append(String.valueOf(cs)); }

    public int length() { return len; }
    public char charAt(int i) {
        if (i < 0 || i >= len) Native.raise(new java.lang.StringIndexOutOfBoundsException("index " + i + ", length " + len));
        return (char) (buf[i] & 0xff);
    }
    public void setCharAt(int i, char c) {
        if (i < 0 || i >= len) Native.raise(new java.lang.StringIndexOutOfBoundsException("index " + i + ", length " + len));
        buf[i] = (byte) c;
    }
    public void setLength(int n) {
        ensure(n);
        for (int i = len; i < n; i++) buf[i] = 0;
        len = n;
    }
    public StringBuilder reverse() {
        for (int i = 0, j = len - 1; i < j; i++, j--) { byte t = buf[i]; buf[i] = buf[j]; buf[j] = t; }
        return this;
    }
    public StringBuilder insert(int off, String s) {
        String t = toString();
        len = 0;
        return append(t.substring(0, off)).append(s).append(t.substring(off));
    }
    public StringBuilder insert(int off, char c) { return insert(off, Native.charToString(c)); }
    public StringBuilder insert(int off, int v) { return insert(off, Native.intToString(v)); }
    public StringBuilder insert(int off, Object o) { return insert(off, String.valueOf(o)); }
    public StringBuilder delete(int start, int end) {
        if (end > len) end = len;
        if (start < 0 || start > end) Native.raise(new java.lang.StringIndexOutOfBoundsException("delete " + start + ", " + end));
        Native.arraycopy(buf, end, buf, start, len - end);
        len -= end - start;
        return this;
    }
    public StringBuilder deleteCharAt(int i) { return delete(i, i + 1); }
    public int indexOf(String s) { return toString().indexOf(s); }
    public int lastIndexOf(String s) { return toString().lastIndexOf(s); }
    public String substring(int s) { return toString().substring(s); }
    public String substring(int s, int e) { return toString().substring(s, e); }
    public java.lang.CharSequence subSequence(int s, int e) { return substring(s, e); }
    public int capacity() { return buf.length; }
    public String toString() { return Native.bytesToString(buf, 0, len); }
}
