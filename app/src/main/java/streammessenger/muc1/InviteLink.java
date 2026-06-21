package streammessenger.muc1;

import java.time.Instant;
import java.util.UUID;

/**
 * A shareable invite link token (WhatsApp "Invite to group via link").
 *
 * <p>Security model:</p>
 * <ul>
 *   <li>The token is a high-entropy random string (128-bit), URL-safe.</li>
 *   <li>It is a <b>bearer capability</b>: anyone who has it can redeem it,
 *       so admins can revoke and rotate.</li>
 *   <li>Optional expiry and max-use count limit blast radius if leaked.</li>
 * </ul>
 */
public final class InviteLink {
    public final String token;
    public final String roomJid;
    public final String createdByBareJid;
    public final Instant createdAt;
    public final Instant expiresAt;     // null = never
    public final int maxUses;           // 0 = unlimited
    private volatile int uses;
    private volatile boolean revoked;

    InviteLink(String roomJid, String creator, Instant expiresAt, int maxUses) {
        // URL-safe 128-bit token. Use SecureRandom in production (see factory).
        this.token = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        this.roomJid = roomJid;
        this.createdByBareJid = creator;
        this.createdAt = Instant.now();
        this.expiresAt = expiresAt;
        this.maxUses = maxUses;
    }

    public synchronized boolean isUsable() {
        if (revoked) return false;
        if (expiresAt != null && Instant.now().isAfter(expiresAt)) return false;
        if (maxUses > 0 && uses >= maxUses) return false;
        return true;
    }

    synchronized void recordUse() { uses++; }
    public void revoke() { revoked = true; }
}