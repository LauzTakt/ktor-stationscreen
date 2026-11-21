package ktshim.kotlin.text;

import ktshim.java.util.ArrayList;
import ktshim.java.util.List;

public final class StringsKt {
    private StringsKt() {}

    public static Integer toIntOrNull(String s) { return toIntOrNull(s, 10); }
    public static Integer toIntOrNull(String s, int radix) {
        Long v = toLongOrNull(s, radix);
        if (v == null) return null;
        long l = v.longValue();
        return l < Integer.MIN_VALUE || l > Integer.MAX_VALUE ? null : Integer.valueOf((int) l);
    }
    public static Long toLongOrNull(String s) { return toLongOrNull(s, 10); }
    public static Long toLongOrNull(String s, int radix) {
        int n = s.length(), i = 0;
        if (n == 0) return null;
        boolean neg = false;
        if (s.charAt(0) == '-' || s.charAt(0) == '+') { neg = s.charAt(0) == '-'; i = 1; if (n == 1) return null; }
        long v = 0;
        for (; i < n; i++) {
            int d = Character.digit(s.charAt(i), radix);
            if (d < 0) return null;
            v = v * radix + d;
        }
        return Long.valueOf(neg ? -v : v);
    }
    public static Double toDoubleOrNull(String s) {
        if (!isNumberLike(s)) return null;
        return Double.valueOf(Double.parseDouble(s));
    }
    public static Float toFloatOrNull(String s) {
        if (!isNumberLike(s)) return null;
        return Float.valueOf(Float.parseFloat(s));
    }
    private static boolean isNumberLike(String s) {
        String t = s.trim();
        if (t.length() == 0) return false;
        boolean digit = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= '0' && c <= '9') digit = true;
            else if (c != '.' && c != '-' && c != '+' && c != 'e' && c != 'E') return false;
        }
        return digit;
    }

    public static String repeat(java.lang.CharSequence cs, int n) {
        if (n < 0) throw new IllegalArgumentException("Count 'n' must be non-negative, but was " + n + ".");
        String s = cs.toString();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }
    public static String padStart(String s, int len, char c) {
        if (s.length() >= len) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = s.length(); i < len; i++) sb.append(c);
        return sb.append(s).toString();
    }
    public static String padEnd(String s, int len, char c) {
        StringBuilder sb = new StringBuilder(s);
        for (int i = s.length(); i < len; i++) sb.append(c);
        return sb.toString();
    }
    public static String padStart$default(String s, int len, char c, int mask, Object u) { return padStart(s, len, (mask & 2) != 0 ? ' ' : c); }
    public static String padEnd$default(String s, int len, char c, int mask, Object u) { return padEnd(s, len, (mask & 2) != 0 ? ' ' : c); }

    public static boolean isBlank(java.lang.CharSequence cs) {
        for (int i = 0; i < cs.length(); i++) if (!Character.isWhitespace(cs.charAt(i))) return false;
        return true;
    }
    public static java.lang.CharSequence trim(java.lang.CharSequence cs) { return cs.toString().trim(); }
    public static String trimStart(String s) { int i = 0; while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; return s.substring(i); }
    public static String trimEnd(String s) { int i = s.length(); while (i > 0 && Character.isWhitespace(s.charAt(i - 1))) i--; return s.substring(0, i); }
    public static java.lang.CharSequence reversed(java.lang.CharSequence cs) { return new StringBuilder(cs.toString()).reverse(); }

    public static List split(java.lang.CharSequence cs, String[] delims, boolean ignoreCase, int limit) {
        String s = cs.toString();
        ArrayList out = new ArrayList();
        int start = 0;
        while (true) {
            if (limit > 0 && out.size() == limit - 1) break;
            int best = -1, blen = 0;
            for (int d = 0; d < delims.length; d++) {
                if (delims[d].length() == 0) continue;
                int i = s.indexOf(delims[d], start);
                if (i >= 0 && (best < 0 || i < best)) { best = i; blen = delims[d].length(); }
            }
            if (best < 0) break;
            out.add(s.substring(start, best));
            start = best + blen;
        }
        out.add(s.substring(start));
        return out;
    }
    public static List split$default(java.lang.CharSequence cs, String[] delims, boolean ignoreCase, int limit, int mask, Object u) {
        return split(cs, delims, false, (mask & 4) != 0 ? 0 : limit);
    }
    public static List split(java.lang.CharSequence cs, char[] delims, boolean ignoreCase, int limit) {
        String[] d = new String[delims.length];
        for (int i = 0; i < d.length; i++) d[i] = String.valueOf(delims[i]);
        return split(cs, d, ignoreCase, limit);
    }
    public static List split$default(java.lang.CharSequence cs, char[] delims, boolean ignoreCase, int limit, int mask, Object u) {
        return split(cs, delims, false, (mask & 4) != 0 ? 0 : limit);
    }
    public static List lines(java.lang.CharSequence cs) {
        List l = split(cs.toString().replace('\r', '\n').replace("\n\n", "\n"), new String[]{"\n"}, false, 0);
        return l;
    }

    public static boolean contains(java.lang.CharSequence cs, java.lang.CharSequence other, boolean ignoreCase) {
        if (ignoreCase) return cs.toString().toLowerCase().contains(other.toString().toLowerCase());
        return cs.toString().contains(other);
    }
    public static boolean contains$default(java.lang.CharSequence cs, java.lang.CharSequence other, boolean ignoreCase, int mask, Object u) {
        return contains(cs, other, (mask & 2) == 0 && ignoreCase);
    }
    public static boolean contains(java.lang.CharSequence cs, char c, boolean ignoreCase) { return cs.toString().indexOf(c) >= 0; }
    public static boolean contains$default(java.lang.CharSequence cs, char c, boolean ignoreCase, int mask, Object u) { return cs.toString().indexOf(c) >= 0; }
    public static boolean startsWith(String s, String p, boolean ignoreCase) {
        return ignoreCase ? s.toLowerCase().startsWith(p.toLowerCase()) : s.startsWith(p);
    }
    public static boolean startsWith$default(String s, String p, boolean ignoreCase, int mask, Object u) { return startsWith(s, p, (mask & 2) == 0 && ignoreCase); }
    public static boolean endsWith(String s, String p, boolean ignoreCase) {
        return ignoreCase ? s.toLowerCase().endsWith(p.toLowerCase()) : s.endsWith(p);
    }
    public static boolean endsWith$default(String s, String p, boolean ignoreCase, int mask, Object u) { return endsWith(s, p, (mask & 2) == 0 && ignoreCase); }
    public static boolean startsWith(java.lang.CharSequence s, char c, boolean ignoreCase) { return s.length() > 0 && s.charAt(0) == c; }
    public static boolean startsWith$default(java.lang.CharSequence s, char c, boolean ignoreCase, int mask, Object u) { return s.length() > 0 && s.charAt(0) == c; }
    public static boolean endsWith(java.lang.CharSequence s, char c, boolean ignoreCase) { return s.length() > 0 && s.charAt(s.length() - 1) == c; }
    public static boolean endsWith$default(java.lang.CharSequence s, char c, boolean ignoreCase, int mask, Object u) { return endsWith(s, c, false); }
    public static int indexOf$default(java.lang.CharSequence cs, String s, int from, boolean ic, int mask, Object u) {
        return cs.toString().indexOf(s, (mask & 2) != 0 ? 0 : from);
    }
    public static int indexOf$default(java.lang.CharSequence cs, char c, int from, boolean ic, int mask, Object u) {
        return cs.toString().indexOf(c, (mask & 2) != 0 ? 0 : from);
    }
    public static int lastIndexOf$default(java.lang.CharSequence cs, String s, int from, boolean ic, int mask, Object u) { return cs.toString().lastIndexOf(s); }
    public static int lastIndexOf$default(java.lang.CharSequence cs, char c, int from, boolean ic, int mask, Object u) { return cs.toString().lastIndexOf(c); }
    public static String replace(String s, String a, String b, boolean ignoreCase) { return s.replace(a, b); }
    public static String replace$default(String s, String a, String b, boolean ic, int mask, Object u) { return s.replace(a, b); }
    public static String replace(String s, char a, char b, boolean ignoreCase) { return s.replace(a, b); }
    public static String replace$default(String s, char a, char b, boolean ic, int mask, Object u) { return s.replace(a, b); }
    public static String substringBefore(String s, String d, String missing) { int i = s.indexOf(d); return i < 0 ? missing : s.substring(0, i); }
    public static String substringBefore$default(String s, String d, String missing, int mask, Object u) { return substringBefore(s, d, (mask & 2) != 0 ? s : missing); }
    public static String substringAfter(String s, String d, String missing) { int i = s.indexOf(d); return i < 0 ? missing : s.substring(i + d.length()); }
    public static String substringAfter$default(String s, String d, String missing, int mask, Object u) { return substringAfter(s, d, (mask & 2) != 0 ? s : missing); }
    public static String substringBeforeLast(String s, String d, String missing) { int i = s.lastIndexOf(d); return i < 0 ? missing : s.substring(0, i); }
    public static String substringBeforeLast$default(String s, String d, String missing, int mask, Object u) { return substringBeforeLast(s, d, (mask & 2) != 0 ? s : missing); }
    public static String substringAfterLast(String s, String d, String missing) { int i = s.lastIndexOf(d); return i < 0 ? missing : s.substring(i + d.length()); }
    public static String substringAfterLast$default(String s, String d, String missing, int mask, Object u) { return substringAfterLast(s, d, (mask & 2) != 0 ? s : missing); }
    public static String removePrefix(String s, java.lang.CharSequence p) { String q = p.toString(); return s.startsWith(q) ? s.substring(q.length()) : s; }
    public static String removeSuffix(String s, java.lang.CharSequence p) { String q = p.toString(); return s.endsWith(q) ? s.substring(0, s.length() - q.length()) : s; }
    public static String removeSurrounding(String s, java.lang.CharSequence d) { return removeSuffix(removePrefix(s, d), d); }
    public static String take(String s, int n) { return s.substring(0, Math.min(n, s.length())); }
    public static String drop(String s, int n) { return s.substring(Math.min(n, s.length())); }
    public static String takeLast(String s, int n) { return s.substring(s.length() - Math.min(n, s.length())); }
    public static String dropLast(String s, int n) { return take(s, Math.max(0, s.length() - n)); }
    public static char first(java.lang.CharSequence s) { if (s.length() == 0) throw new ktshim.java.util.NoSuchElementException("Char sequence is empty."); return s.charAt(0); }
    public static char last(java.lang.CharSequence s) { if (s.length() == 0) throw new ktshim.java.util.NoSuchElementException("Char sequence is empty."); return s.charAt(s.length() - 1); }
    public static int getLastIndex(java.lang.CharSequence s) { return s.length() - 1; }
    public static ktshim.kotlin.ranges.IntRange getIndices(java.lang.CharSequence s) { return new ktshim.kotlin.ranges.IntRange(0, s.length() - 1); }
    public static boolean equals(String a, String b, boolean ignoreCase) {
        if (a == null) return b == null;
        return ignoreCase ? a.equalsIgnoreCase(b) : a.equals(b);
    }
    public static boolean equals$default(String a, String b, boolean ic, int mask, Object u) { return equals(a, b, (mask & 4) == 0 && ic); }
    public static String trimIndent(String s) {
        List ls = split(s, new String[]{"\n"}, false, 0);
        int min = Integer.MAX_VALUE;
        for (int i = 0; i < ls.size(); i++) {
            String l = (String) ls.get(i);
            if (isBlank(l)) continue;
            int k = 0;
            while (k < l.length() && Character.isWhitespace(l.charAt(k))) k++;
            if (k < min) min = k;
        }
        if (min == Integer.MAX_VALUE) min = 0;
        StringBuilder sb = new StringBuilder();
        int first = 0, last = ls.size() - 1;
        if (last >= 0 && isBlank((String) ls.get(0))) first = 1;
        if (last >= first && isBlank((String) ls.get(last))) last--;
        for (int i = first; i <= last; i++) {
            String l = (String) ls.get(i);
            if (i > first) sb.append('\n');
            sb.append(l.length() >= min ? l.substring(min) : "");
        }
        return sb.toString();
    }
    public static String trimMargin(String s, String margin) {
        List ls = split(s, new String[]{"\n"}, false, 0);
        StringBuilder sb = new StringBuilder();
        boolean firstOut = true;
        for (int i = 0; i < ls.size(); i++) {
            String l = (String) ls.get(i);
            if ((i == 0 || i == ls.size() - 1) && isBlank(l)) continue;
            String t = trimStart(l);
            if (!firstOut) sb.append('\n');
            firstOut = false;
            sb.append(t.startsWith(margin) ? t.substring(margin.length()) : l);
        }
        return sb.toString();
    }
    public static String trimMargin$default(String s, String margin, int mask, Object u) { return trimMargin(s, (mask & 1) != 0 ? "|" : margin); }
}
