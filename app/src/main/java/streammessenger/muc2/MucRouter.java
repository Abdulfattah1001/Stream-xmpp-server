package streammessenger.muc;

import com.example.xmpp.muc.*;
import com.example.xmpp.muc.server.invite.InviteLinkService;
import com.example.xmpp.muc.server.net.Session;

import java.time.Duration;

/**
 * The single entry point from your XML stream handlers into MUC.
 *
 * Your handleIq / handlePresence / handleMessage PARSE the stanza (with your
 * XMLEventReader), then call ONE method here with plain arguments. This class
 * runs the engine command and dispatches / replies. It hides all internals.
 *
 * Nick convention: nick == the user's uid (bare-JID local part).
 */
public final class MucRouter {

    private final MucService muc;
    private final InviteLinkService invites;

    public MucRouter(MucService muc, InviteLinkService invites) {
        this.muc = muc; this.invites = invites;
    }

    // ── CREATE GROUP (from <iq><create/>) ──
    public void createGroup(Session session, String iqId, String groupName) {
        try {
            Room room = muc.createRoom(groupName, session.getJid(), RoomConfig.whatsappStyle());
            session.writeXML(Stanzas.iqResult(session.getJid(), iqId,
                    "<created xmlns='" + Muc.NS_CREATE + "' jid='" + room.roomJid() + "'/>"));
        } catch (MucException e) {
            session.writeXML(Stanzas.iqError(session.getJid(), iqId, e));
        }
    }

    // ── JOIN (from <presence to='room/uid'><x muc/></presence>) ──
    public void joinGroup(Session session, String roomJid, String nick) {
        Room room = muc.room(roomJid);
        if (room == null) {
            session.writeXML(Stanzas.presenceError(session.getJid(), roomJid + "/" + nick,
                    MucException.itemNotFound("No such group")));
            return;
        }
        try {
            muc.dispatch(room.join(session.getJid(), nick));
        } catch (MucException e) {
            session.writeXML(Stanzas.presenceError(session.getJid(), roomJid + "/" + nick, e));
        }
    }

    // ── LEAVE (from <presence type='unavailable'>) ──
    public void leaveGroup(Session session, String roomJid, String nick) {
        Room room = muc.room(roomJid);
        if (room != null) muc.dispatch(room.leave(nick));
    }

    // ── SEND PLAINTEXT MESSAGE (from <message type='groupchat'><body/>) ──
    public void sendGroupMessage(Session session, String roomJid, String body) {
        Room room = muc.room(roomJid);
        if (room == null) return;
        try {
            muc.dispatch(room.relayMessage(session.getJid(), body));
        } catch (MucException ignored) { /* sender not in group / no voice */ }
    }

    // ── SEND ENCRYPTED MESSAGE (Signal: opaque payload) ──
    public void sendEncrypted(Session session, String roomJid, String encryptedXml) {
        Room room = muc.room(roomJid);
        if (room == null) return;
        try {
            muc.dispatch(room.relayEncrypted(session.getJid(), encryptedXml));
        } catch (MucException ignored) { }
    }

    // ── ADMIN: add / remove / ban / kick (from <iq query muc#admin>) ──
    public void addMember(Session session, String iqId, String roomJid, String targetJid) {
        runAdmin(session, iqId, roomJid, r -> r.grantMembership(session.getJid(), targetJid));
    }
    public void removeMember(Session session, String iqId, String roomJid, String targetJid) {
        runAdmin(session, iqId, roomJid, r -> r.removeMember(session.getJid(), targetJid));
    }
    public void banMember(Session session, String iqId, String roomJid, String targetJid, String reason) {
        runAdmin(session, iqId, roomJid, r -> r.ban(session.getJid(), targetJid, reason));
    }
    public void kickOccupant(Session session, String iqId, String roomJid, String nick, String reason) {
        runAdmin(session, iqId, roomJid, r -> r.kick(session.getJid(), nick, reason));
    }
    public void setSubject(Session session, String iqId, String roomJid, String subject) {
        runAdmin(session, iqId, roomJid, r -> r.setSubject(session.getJid(), subject));
    }

    // ── INVITE LINK: create / redeem / preview ──
    public void createInviteLink(Session session, String iqId, String roomJid) {
        try {
            String url = invites.createLink(roomJid, session.getJid(), Duration.ofDays(7), 0);
            session.writeXML(Stanzas.iqResult(session.getJid(), iqId,
                    "<link xmlns='" + Muc.NS_INVITE_LINK + "'>" + Xml.esc(url) + "</link>"));
        } catch (MucException e) {
            session.writeXML(Stanzas.iqError(session.getJid(), iqId, e));
        }
    }
    public void redeemInviteLink(Session session, String iqId, String token) {
        try {
            String roomJid = invites.redeem(token, session.getJid()); // grants membership
            session.writeXML(Stanzas.iqResult(session.getJid(), iqId,
                    "<joined xmlns='" + Muc.NS_INVITE_LINK + "' jid='" + roomJid + "'/>"));
        } catch (MucException e) {
            session.writeXML(Stanzas.iqError(session.getJid(), iqId, e));
        }
    }

    // shared helper for admin commands that return Outbound + reply success/error
    private interface RoomCmd { java.util.List<Room.Outbound> run(Room r) throws MucException; }
    private void runAdmin(Session session, String iqId, String roomJid, RoomCmd cmd) {
        Room room = muc.room(roomJid);
        if (room == null) {
            session.writeXML(Stanzas.iqError(session.getJid(), iqId,
                    MucException.itemNotFound("No such group")));
            return;
        }
        try {
            muc.dispatch(cmd.run(room));
            session.writeXML(Stanzas.iqResult(session.getJid(), iqId, null));
        } catch (MucException e) {
            session.writeXML(Stanzas.iqError(session.getJid(), iqId, e));
        }
    }
}