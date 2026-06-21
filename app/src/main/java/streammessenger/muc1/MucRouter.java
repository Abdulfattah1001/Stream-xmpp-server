package streammessenger.muc1;


/**
 * THE FRONT DOOR for all MUC actions.
 *
 * This class is the bridge: it takes those two facts and calls the right
 * method on MucService / Room. Nothing magic — it's a glorified switch statement.
 */
public final class MucRouter {

    private final MucService mucService;

    public MucRouter(MucService mucService) {
        this.mucService = mucService;
    }

    /**
     * Call this from your read-loop whenever a MUC-related message arrives.
     *
     * @param senderJid the logged-in user who sent it (your auth layer provides this)
     * @param action    what they want to do ("create_group", "add_member", ...)
     * @param params    the details (group name, target user, etc.)
     */
    public void handle(String senderJid, String action, Params params) throws MucException {
        switch (action) {

            case "create_group": {
                // ── THIS is the create-group entry point ──
                Room room = mucService.createRoom(
                        params.groupId,                 // e.g. "weekend-trip"
                        senderJid,                       // the creator becomes owner
                        RoomConfig.whatsappStyle());

                System.out.println("Created group: " + room.roomJid());
                break;
            }

            case "add_member": {
                Room room = mucService.room(params.roomJid);
                mucService.dispatch(room.grantMembership(senderJid, params.targetJid));
                break;
            }

            case "remove_member": {
                Room room = mucService.room(params.roomJid);
                mucService.dispatch(room.removeMember(senderJid, params.targetJid));
                break;
            }

            case "join_group": {
                Room room = mucService.room(params.roomJid);
                mucService.dispatch(
                        room.join(senderJid, params.nick));
                break;
            }

            default:
                System.out.println("Unknown MUC action: " + action);
        }
    }

    /** Simple bag of parameters. Fill from your parsed message. */
    public static final class Params {
        public String groupId;   // for create
        public String roomJid;   // for actions on an existing room
        public String targetJid; // for add/remove member
        public String nick;      // for join
    }
}
