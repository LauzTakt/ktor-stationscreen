package ktshim.java.lang;

public interface CharSequence {
    int length();
    char charAt(int index);
    java.lang.CharSequence subSequence(int start, int end);
    String toString();
}
