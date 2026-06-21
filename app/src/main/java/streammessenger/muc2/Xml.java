package streammessenger.muc;

/** Escapes user-supplied values so they're safe inside XML strings. */
public final class Xml {
    private Xml() {}
    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&':  b.append("&amp;");  break;
                case '<':  b.append("&lt;");   break;
                case '>':  b.append("&gt;");   break;
                case '"':  b.append("&quot;"); break;
                case '\'': b.append("&apos;"); break;
                default:   b.append(c);
            }
        }
        return b.toString();
    }
}