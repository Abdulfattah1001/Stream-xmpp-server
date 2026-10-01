package streammessenger.profile;

public class ProfileFields {
    public final static int DISPLAY_NAME = 1;
    public final static int AVATAR = 1 << 1;
    public final static int STATUS = 1 << 2;
    public final static int METADATA = 1 << 3;
    public final static int ALL = DISPLAY_NAME | AVATAR | STATUS | METADATA;

    private ProfileFields() {}

    public static String describe(int mask) {
        StringBuilder sb = new StringBuilder();
        if ((mask & DISPLAY_NAME) != 0) sb.append("name,");
        if ((mask & AVATAR) != 0) sb.append("avatar,");
        if ((mask & STATUS) != 0) sb.append("status,");
        if ((mask & METADATA) != 0) sb.append("meta,");
        if (sb.length() > 0) sb.setLength(sb.length() - 1);
        return sb.toString();
    }
}
