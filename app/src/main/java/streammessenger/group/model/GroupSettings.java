package streammessenger.group.model;

public record GroupSettings(
        boolean onlyAdminsCanSend,
        boolean onlyAdminsCanEditInfo,
        boolean onlyAdminsCanAdd,
        int disappearingSeconds,
        boolean approvalRequired
) {
    public static GroupSettings defaults() {
        return new GroupSettings(
                false,    // anyone can send
                true,     // only admins edit info
                false,    // anyone can add members
                0,        // disappearing off
                false     // no approval required
        );
    }
}