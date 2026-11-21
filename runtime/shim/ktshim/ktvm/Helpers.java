package ktshim.ktvm;

import ktshim.java.util.ArrayList;

/** Static helpers that replace some java.lang.String methods (see linker REDIRECTS). */
public final class Helpers {
    private Helpers() {}

    public static String valueOf(Object o) { return o == null ? "null" : o.toString(); }

    public static String[] split(String s, String sep) {
        ArrayList parts = new ArrayList();
        int start = 0;
        if (sep.length() == 0) {
            for (int i = 0; i < s.length(); i++) parts.add(s.substring(i, i + 1));
        } else {
            int i;
            while ((i = s.indexOf(sep, start)) >= 0) { parts.add(s.substring(start, i)); start = i + sep.length(); }
            parts.add(s.substring(start));
            while (parts.size() > 1 && ((String) parts.get(parts.size() - 1)).length() == 0) parts.remove(parts.size() - 1);
        }
        String[] out = new String[parts.size()];
        for (int i = 0; i < out.length; i++) out[i] = (String) parts.get(i);
        return out;
    }

    public static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    public static String replace(String s, CharSequence a, CharSequence b) {
        String from = a.toString(), to = b.toString();
        if (from.length() == 0) return s;
        StringBuilder sb = new StringBuilder();
        int start = 0, i;
        while ((i = s.indexOf(from, start)) >= 0) { sb.append(s.substring(start, i)).append(to); start = i + from.length(); }
        sb.append(s.substring(start));
        return sb.toString();
    }

    public static byte[] getBytes(String s) {
        byte[] b = new byte[s.length()];
        Native.strToBytes(s, b, 0);
        return b;
    }

    /** Minimal String.format: %d %i %s %f %.Nf %x %X %c %b %% and widths like %5d / %-5s / %05d. */
    public static String format(String fmt, Object[] args) {
        StringBuilder sb = new StringBuilder();
        int ai = 0, n = fmt.length();
        for (int i = 0; i < n; i++) {
            char c = fmt.charAt(i);
            if (c != '%' || i + 1 >= n) { sb.append(c); continue; }
            i++;
            boolean left = false, zero = false;
            while (i < n && (fmt.charAt(i) == '-' || fmt.charAt(i) == '0')) {
                if (fmt.charAt(i) == '-') left = true; else zero = true;
                i++;
            }
            int width = 0, prec = -1;
            while (i < n && fmt.charAt(i) >= '0' && fmt.charAt(i) <= '9') width = width * 10 + (fmt.charAt(i++) - '0');
            if (i < n && fmt.charAt(i) == '.') {
                i++; prec = 0;
                while (i < n && fmt.charAt(i) >= '0' && fmt.charAt(i) <= '9') prec = prec * 10 + (fmt.charAt(i++) - '0');
            }
            if (i >= n) break;
            char conv = fmt.charAt(i);
            String s;
            if (conv == '%') { sb.append('%'); continue; }
            if (conv == 'n') { sb.append('\n'); continue; }
            Object a = ai < args.length ? args[ai++] : null;
            switch (conv) {
                case 'd': case 'i': s = a instanceof Number ? Long.toString(((Number) a).longValue()) : String.valueOf(a); break;
                case 'f': s = Native.formatDouble(a instanceof Number ? ((Number) a).doubleValue() : 0.0, prec < 0 ? 6 : prec); break;
                case 'x': s = Integer.toHexString(a instanceof Number ? ((Number) a).intValue() : 0); break;
                case 'X': s = Integer.toHexString(a instanceof Number ? ((Number) a).intValue() : 0).toUpperCase(); break;
                case 'c': s = a instanceof Character ? a.toString() : String.valueOf((char) ((Number) a).intValue()); break;
                case 'b': s = String.valueOf(a != null && (!(a instanceof Boolean) || ((Boolean) a).booleanValue())); break;
                default: s = String.valueOf(a); if (prec >= 0 && prec < s.length()) s = s.substring(0, prec);
            }
            int pad = width - s.length();
            if (pad > 0 && !left) {
                boolean neg = zero && s.length() > 0 && s.charAt(0) == '-';
                if (neg) { sb.append('-'); s = s.substring(1); }
                for (int k = 0; k < pad; k++) sb.append(zero ? '0' : ' ');
            }
            sb.append(s);
            if (pad > 0 && left) for (int k = 0; k < pad; k++) sb.append(' ');
        }
        return sb.toString();
    }
}
