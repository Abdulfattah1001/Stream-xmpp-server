package streammessenger.muc1;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Application service for invite links. Bridges the bearer-token UX onto the
 * standard XEP-0045 affiliation model.
 *
 * <p>Flow:</p>
 * <ol>
 *   <li>Admin calls {@link #createLink} -> gets a token -> renders to a URL
 *       like {@code https://app.example.com/i/<token>}.</li>
 *   <li>Invitee opens the URL; the front-end calls {@link #redeem} with the
 *       invitee's JID.</li>
 *   <li>{@code redeem} verifies the token then performs a standard
 *       {@code grantMembership}, after which the user can JOIN normally.</li>
 * </ol>
 */
public final class InviteLinkService {

    private final MucService mucService;
    private final String linkBaseUrl; // e.g. https://app.example.com/i/

    public InviteLinkService(MucService mucService, String linkBaseUrl) {
        this.mucService = mucService;
        this.linkBaseUrl = linkBaseUrl;
    }

    /**
     * Admin creates an invite link.
     *
     * @param actorJid must be admin/owner (enforced against the room)
     * @param ttl      time-to-live (null = no expiry)
     * @param maxUses  0 = unlimited
     * @return the full shareable URL
     */
    public String createLink(String roomJid, String actorJid, Duration ttl, int maxUses)
            throws MucException {
        Room room = requireRoom(roomJid);
        // Reuse the affiliation check: only admins+ may create links.
        if (!room.affiliationOf(actorJid).atLeast(Affiliation.ADMIN))
            throw MucException.forbidden("Only admins can create invite links");

        Instant expiry = (ttl == null) ? null : Instant.now().plus(ttl);
        InviteLink link = new InviteLink(roomJid, bare(actorJid), expiry, maxUses);
        room.inviteLinks().put(link.token, link);
        return linkBaseUrl + link.token;
    }

    /** Admin revokes (rotates) a link by token. */
    public void revokeLink(String roomJid, String actorJid, String token)
            throws MucException {
        Room room = requireRoom(roomJid);
        if (!room.affiliationOf(actorJid).atLeast(Affiliation.ADMIN))
            throw MucException.forbidden("Only admins can revoke invite links");
        InviteLink link = room.inviteLinks().get(token);
        if (link != null) link.revoke();
    }

    /**
     * Invitee redeems a link. On success they receive {@code member} affiliation
     * and may immediately join. We dispatch any resulting presence updates.
     *
     * @throws MucException {@code item-not-found} for unknown/expired tokens.
     */
    public void redeem(String token, String inviteeJid) throws MucException {
        // We don't know the room from the token alone unless we index globally.
        // For clarity we scan; in production keep a token->roomJid index map.
        InviteLink link = findLink(token);
        if (link == null || !link.isUsable())
            throw MucException.itemNotFound("Invite link is invalid or expired");

        Room room = requireRoom(link.roomJid);

        // Don't re-add banned users via a leaked link.
        if (room.affiliationOf(inviteeJid) == Affiliation.OUTCAST)
            throw MucException.forbidden("You are banned from this room");

        // Grant membership *as the link creator* (so privilege checks pass),
        // then record the use. The user still has to send a JOIN presence.
        List<Room.Outbound> out =
                room.grantMembership(link.createdByBareJid, inviteeJid);
        link.recordUse();
        mucService.dispatch(out);
    }

    // --- helpers ---
    private InviteLink findLink(String token) {
        // NOTE: O(rooms). Replace with a ConcurrentHashMap<token, roomJid> index
        // for O(1) lookup at scale. Kept simple here for readability.
        // (Pseudo — MucService would expose an iterator over rooms.)
        throw new UnsupportedOperationException(
                "Wire to a global token index in MucService for production.");
    }
    private Room requireRoom(String roomJid) throws MucException {
        Room r = mucService.room(roomJid);
        if (r == null) throw MucException.itemNotFound("No such room: " + roomJid);
        return r;
    }
    private static String bare(String jid) {
        int s = jid.indexOf('/');
        return s < 0 ? jid : jid.substring(0, s);
    }

    /**
     * Preview the group behind an invite link <b>without</b> joining or consuming
     * a use. This powers the "You're invited to <Group> · 24 members [Join]" screen.
     *
     * @param token      the invite token from the URL
     * @param viewerJid  the prospective joiner's bare JID (may be unknown -> null)
     * @return a privacy-aware preview
     * @throws MucException item-not-found for invalid/expired/revoked links
     */
    public RoomPreview previewByLink(String token, String viewerJid) throws MucException {
        InviteLink link = findLink(token);              // global token index lookup
        if (link == null || !link.isUsable())
            throw MucException.itemNotFound("Invite link is invalid or expired");

        Room room = mucService.room(link.roomJid);
        if (room == null) throw MucException.itemNotFound("Room no longer exists");

        // NOTE: we do NOT call link.recordUse() and we do NOT grant membership.
        // A preview is read-only by contract.
        return room.preview(viewerJid == null ? null : bare(viewerJid));
    }
}