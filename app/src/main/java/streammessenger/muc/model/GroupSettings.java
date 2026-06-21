package streammessenger.muc.model;

public record GroupSettings(
        boolean onlyAdminsCanSend,
        boolean onlyAdminsCanEditMeta,
        boolean onlyAdminsCanAdd,
        boolean membershipApproval,
        boolean announcementMode,
        boolean allowHistory,
        int historyMaxMessages,
        int disappearingSeconds
) {
    public static GroupSettings defaults() {
        return new GroupSettings(
                false, true, false, false, false,
                true, 50, 0
        );
    }
}